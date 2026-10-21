package com.example.ai_controll.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolExecutor;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolRegistryService;

import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a tool that lives on an MCP server.
 *
 * <p>Because MCP tools are imported into the registry rather than proxied straight through, by the
 * time a call arrives here it has already been validated against the schema the model was shown,
 * checked against the toolset it was offered, and journalled. This class is only the transport step,
 * which is precisely the point of running the MCP client inside the harness.
 */
@Component
public class McpToolExecutor implements ToolExecutor {

	private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

	private final McpClientManager clientManager;

	private final McpServerRepository servers;

	private final ToolRegistryService registry;

	private final ObjectMapper objectMapper;

	public McpToolExecutor(McpClientManager clientManager, McpServerRepository servers,
			ToolRegistryService registry, ObjectMapper objectMapper) {
		this.clientManager = clientManager;
		this.servers = servers;
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	@Override
	public ExecutorType type() {
		return ExecutorType.MCP;
	}

	@Override
	public ToolOutcome execute(ToolInvocation invocation) {
		long startedAt = System.nanoTime();

		JsonNode config;
		try {
			config = registry.parseExecutorConfig(invocation.tool());
		}
		catch (RuntimeException ex) {
			return ToolOutcome.failure("bad_tool_config",
					"The tool's MCP configuration is not valid JSON", elapsedMs(startedAt));
		}

		Optional<McpServerEntity> server = findServer(config);
		if (server.isEmpty()) {
			// The server was deleted while an imported tool referencing it survived.
			return ToolOutcome.failure("mcp_server_missing",
					"The MCP server this tool came from is no longer configured", elapsedMs(startedAt));
		}
		if (!server.get().isEnabled()) {
			return ToolOutcome.failure("mcp_server_disabled",
					"The MCP server '%s' is disabled".formatted(server.get().getLabel()),
					elapsedMs(startedAt));
		}

		String upstreamName = text(config, "upstreamName");
		if (upstreamName == null) {
			return ToolOutcome.failure("bad_tool_config",
					"The tool's MCP configuration does not name an upstream tool", elapsedMs(startedAt));
		}

		try {
			McpSchema.CallToolResult result = clientManager.callTool(server.get(), upstreamName,
					toArguments(invocation.arguments()));
			return interpret(result, elapsedMs(startedAt));
		}
		catch (McpClientManager.McpConnectionException ex) {
			// A refused launch or an unreachable server is an outcome the model is told about, so it
			// can try something else rather than the run simply dying.
			return ToolOutcome.failure("mcp_unavailable", ex.getMessage(), elapsedMs(startedAt));
		}
		catch (RuntimeException ex) {
			log.debug("MCP call to '{}' failed", upstreamName, ex);
			return ToolOutcome.failure("mcp_call_failed",
					ex.getClass().getSimpleName() + ": " + ex.getMessage(), elapsedMs(startedAt));
		}
	}

	/**
	 * Turns an MCP result into a tool outcome.
	 *
	 * <p>MCP reports a tool's own failure with {@code isError} rather than a protocol error, which is
	 * the right design: "the file does not exist" is information for the model, not a transport
	 * fault. It is mapped to a failed outcome so the model sees it the same way it sees any other
	 * tool failure.
	 */
	private ToolOutcome interpret(McpSchema.CallToolResult result, long durationMs) {
		if (result == null) {
			return ToolOutcome.failure("mcp_empty_result", "The server returned no result", durationMs);
		}

		String text = renderContent(result);
		if (Boolean.TRUE.equals(result.isError())) {
			return ToolOutcome.failure("mcp_tool_error",
					text.isBlank() ? "The tool reported an error" : text, durationMs);
		}

		// Structured content is preferred when present: it is already JSON, and a model handles a
		// JSON object more reliably than prose describing one.
		if (result.structuredContent() != null) {
			try {
				return ToolOutcome.success(
						objectMapper.writeValueAsString(result.structuredContent()), durationMs);
			}
			catch (RuntimeException ex) {
				log.debug("Could not serialise structured MCP content; falling back to text", ex);
			}
		}
		return ToolOutcome.success(text, durationMs);
	}

	/** Flattens the content blocks into text, which is all the model can consume. */
	private String renderContent(McpSchema.CallToolResult result) {
		if (result.content() == null || result.content().isEmpty()) {
			return "";
		}
		return result.content().stream()
				.map(content -> content instanceof McpSchema.TextContent textContent
						? textContent.text()
						// Images and embedded resources cannot go into a text transcript; naming the type
						// is more useful to the model than dropping the block silently.
						: "[%s content omitted]".formatted(content.getClass().getSimpleName()))
				.collect(Collectors.joining("\n"));
	}

	private Optional<McpServerEntity> findServer(JsonNode config) {
		String serverId = text(config, "serverId");
		if (serverId != null) {
			try {
				return servers.findById(UUID.fromString(serverId));
			}
			catch (IllegalArgumentException ex) {
				// Not a UUID; fall through to the label.
			}
		}
		String label = text(config, "serverLabel");
		return label == null ? Optional.empty() : servers.findByLabel(label);
	}

	/** MCP takes arguments as a plain map; the validated JSON is converted once, here. */
	private Map<String, Object> toArguments(JsonNode arguments) {
		if (arguments == null || !arguments.isObject()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (String name : arguments.propertyNames()) {
			result.put(name, objectMapper.convertValue(arguments.get(name), Object.class));
		}
		return result;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return (value != null && value.isString()) ? value.stringValue() : null;
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
