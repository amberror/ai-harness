package com.example.ai_controll.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the live connections to MCP servers.
 *
 * <p>The harness runs the MCP client itself rather than delegating to LM Studio's {@code
 * integrations} field. That is the same decision as owning the agentic loop, and for the same
 * reason: a call made by someone else can be watched but not governed. Running the client here means
 * every MCP tool call passes through the same validation, the same policy and the same journal as a
 * built-in one, instead of arriving as a fait accompli.
 *
 * <p>Connections are cached per server and opened lazily. A stdio connection is a child process, so
 * they are closed on shutdown -- an orphaned server process outliving the harness is a real
 * nuisance, not a theoretical one.
 */
@Component
public class McpClientManager {

	private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

	private final McpStdioPolicy stdioPolicy;

	private final ObjectMapper objectMapper;

	private final McpJsonMapper mcpJsonMapper;

	private final HarnessProperties.Mcp config;

	/** Live clients by server id. */
	private final Map<UUID, McpSyncClient> clients = new ConcurrentHashMap<>();

	public McpClientManager(McpStdioPolicy stdioPolicy, ObjectMapper objectMapper,
			HarnessProperties properties) {
		this.stdioPolicy = stdioPolicy;
		this.objectMapper = objectMapper;
		this.config = properties.mcp();
		// The SDK needs its own mapper abstraction; wrapping the application's Jackson 3 mapper keeps
		// one JSON configuration throughout.
		this.mcpJsonMapper = new JacksonMcpJsonMapper(
				objectMapper instanceof JsonMapper jsonMapper ? jsonMapper : JsonMapper.builder().build());
	}

	/**
	 * Returns the client for a server, connecting if necessary.
	 *
	 * @throws McpConnectionException when the server cannot be reached, or policy refuses to launch it
	 */
	public McpSyncClient client(McpServerEntity server) {
		return clients.computeIfAbsent(server.getId(), id -> connect(server));
	}

	/** Lists the tools a server offers. */
	public List<McpSchema.Tool> listTools(McpServerEntity server) {
		McpSchema.ListToolsResult result = client(server).listTools();
		return result == null || result.tools() == null ? List.of() : result.tools();
	}

	/** Invokes a tool on a server. */
	public McpSchema.CallToolResult callTool(McpServerEntity server, String toolName,
			Map<String, Object> arguments) {
		return client(server).callTool(new McpSchema.CallToolRequest(toolName, arguments));
	}

	/**
	 * Drops the cached connection for a server, closing it.
	 *
	 * <p>Called when a server's configuration changes: a client still speaking to the old command or
	 * URL would silently ignore the edit.
	 */
	public void disconnect(UUID serverId) {
		McpSyncClient client = clients.remove(serverId);
		closeQuietly(serverId, client);
	}

	/** Whether a live connection is currently held. */
	public boolean isConnected(UUID serverId) {
		return clients.containsKey(serverId);
	}

	@PreDestroy
	public void closeAll() {
		// stdio servers are child processes; leaving them running after shutdown is a real nuisance.
		clients.forEach(this::closeQuietly);
		clients.clear();
	}

	// -------------------------------------------------------------------------------------------

	private McpSyncClient connect(McpServerEntity server) {
		McpClientTransport transport = transportFor(server);
		try {
			McpSyncClient client = McpClient.sync(transport)
					.requestTimeout(config.requestTimeout())
					.initializationTimeout(config.initializeTimeout())
					.clientInfo(new McpSchema.Implementation("ai-controll", "0.0.1-SNAPSHOT"))
					.build();
			client.initialize();
			log.info("Connected to MCP server '{}' over {}", server.getLabel(), server.getTransport());
			return client;
		}
		catch (RuntimeException ex) {
			throw new McpConnectionException(
					"Could not connect to MCP server '%s': %s".formatted(server.getLabel(),
							ex.getMessage()),
					ex);
		}
	}

	private McpClientTransport transportFor(McpServerEntity server) {
		return switch (server.getTransport()) {
			case STDIO -> stdioTransport(server);
			case STREAMABLE_HTTP -> HttpClientStreamableHttpTransport
					.builder(requireUrl(server))
					.jsonMapper(mcpJsonMapper)
					.build();
			case SSE -> HttpClientSseClientTransport
					.builder(requireUrl(server))
					.jsonMapper(mcpJsonMapper)
					.build();
		};
	}

	private McpClientTransport stdioTransport(McpServerEntity server) {
		McpStdioPolicy.Decision decision = stdioPolicy.check(server.getCommand());
		if (!decision.allowed()) {
			// Refused before anything is spawned. Reported as an ordinary failure so the console and
			// the journal can explain it rather than the harness simply not working.
			throw new McpConnectionException(
					"Refused to launch MCP server '%s': %s".formatted(server.getLabel(),
							decision.reason()),
					null);
		}

		ServerParameters.Builder parameters = ServerParameters.builder(server.getCommand());
		List<String> args = readStringList(server.getArgsJson());
		if (!args.isEmpty()) {
			parameters.args(args);
		}
		Map<String, String> env = readStringMap(server.getEnvJson());
		if (!env.isEmpty()) {
			parameters.env(env);
		}

		StdioClientTransport transport = new StdioClientTransport(parameters.build(), mcpJsonMapper);
		// A server's stderr is where its own diagnostics go, and losing them makes a failing server
		// impossible to debug from here.
		transport.setStdErrorHandler(line ->
				log.info("[mcp:{}] {}", server.getLabel(), line));
		return transport;
	}

	private static String requireUrl(McpServerEntity server) {
		if (server.getUrl() == null || server.getUrl().isBlank()) {
			throw new McpConnectionException(
					"MCP server '%s' uses %s but has no URL".formatted(server.getLabel(),
							server.getTransport()),
					null);
		}
		return server.getUrl();
	}

	private List<String> readStringList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			List<String> values = new ArrayList<>();
			if (node.isArray()) {
				node.forEach(element -> values.add(element.isString() ? element.stringValue()
						: element.toString()));
			}
			return values;
		}
		catch (RuntimeException ex) {
			throw new McpConnectionException("The server's args are not a JSON array: " + json, ex);
		}
	}

	private Map<String, String> readStringMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			Map<String, String> values = new LinkedHashMap<>();
			if (node.isObject()) {
				for (String name : node.propertyNames()) {
					JsonNode value = node.get(name);
					values.put(name, value.isString() ? value.stringValue() : value.toString());
				}
			}
			return values;
		}
		catch (RuntimeException ex) {
			throw new McpConnectionException("The server's env is not a JSON object: " + json, ex);
		}
	}

	private void closeQuietly(UUID serverId, McpSyncClient client) {
		if (client == null) {
			return;
		}
		try {
			client.closeGracefully();
		}
		catch (RuntimeException ex) {
			log.debug("Error closing MCP client for server {}", serverId, ex);
		}
	}

	/** Raised when a server cannot be reached or may not be launched. */
	public static class McpConnectionException extends RuntimeException {

		public McpConnectionException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
