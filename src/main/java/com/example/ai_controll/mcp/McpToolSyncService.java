package com.example.ai_controll.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolRegistryService;
import com.example.ai_controll.tool.registry.ToolSource;

import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Imports the tools an MCP server offers into the harness registry.
 *
 * <p>Imported rather than proxied on the fly, and this is the important design choice. If MCP tools
 * were passed straight through to the model, they would bypass everything the registry provides: no
 * version history when a server changes a schema underneath you, no ability to reword a description
 * that a small model keeps misreading, no way to disable one tool from a server that offers twenty.
 * Importing puts an MCP tool on exactly the same footing as a built-in.
 *
 * <p>Re-syncing creates a new version whenever the upstream definition has actually changed, so an
 * upstream schema change is recorded as an event rather than silently swapped in. A sync that finds
 * nothing new writes nothing, which keeps the history meaningful.
 */
@Service
public class McpToolSyncService {

	private static final Logger log = LoggerFactory.getLogger(McpToolSyncService.class);

	private final McpClientManager clientManager;

	private final McpServerRepository servers;

	private final ToolRegistryService registry;

	private final ObjectMapper objectMapper;

	public McpToolSyncService(McpClientManager clientManager, McpServerRepository servers,
			ToolRegistryService registry, ObjectMapper objectMapper) {
		this.clientManager = clientManager;
		this.servers = servers;
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	/**
	 * Connects to a server, lists its tools and imports them.
	 *
	 * @param allowedTools when non-empty, only these upstream tool names are imported. Exposing
	 *                     twenty tools at once measurably hurts a small model's ability to pick the
	 *                     right one, so importing a subset is the common case, not an edge case
	 */
	@Transactional
	public SyncResult sync(McpServerEntity server, Set<String> allowedTools) {
		List<String> imported = new ArrayList<>();
		List<String> unchanged = new ArrayList<>();
		List<String> skipped = new ArrayList<>();

		try {
			List<McpSchema.Tool> discovered = clientManager.listTools(server);

			for (McpSchema.Tool tool : discovered) {
				if (!allowedTools.isEmpty() && !allowedTools.contains(tool.name())) {
					skipped.add(tool.name());
					continue;
				}

				String localName = localName(server, tool.name());
				JsonNode schema = normaliseSchema(tool.inputSchema());
				String description = describe(server, tool);

				JsonNode executorConfig = executorConfig(server, tool.name());
				if (isUnchanged(localName, description, schema, executorConfig)) {
					unchanged.add(localName);
					continue;
				}

				registry.save(new ToolRegistryService.ToolDefinition(localName, description, schema,
						ExecutorType.MCP, executorConfig, true, ToolSource.MCP_IMPORT, null),
						"imported from MCP server '%s'".formatted(server.getLabel()));
				imported.add(localName);
			}

			server.recordStatus("CONNECTED", null);
			server.recordSynced();
			servers.save(server);

			log.info("Synced MCP server '{}': {} imported, {} unchanged, {} skipped",
					server.getLabel(), imported.size(), unchanged.size(), skipped.size());
			return new SyncResult(true, imported, unchanged, skipped, null);
		}
		catch (RuntimeException ex) {
			// A failed sync is recorded on the server rather than thrown at the caller: the console
			// needs to show why a server is not working, and an exception page does not.
			server.recordStatus("ERROR", ex.getMessage());
			servers.save(server);
			log.warn("Could not sync MCP server '{}'", server.getLabel(), ex);
			return new SyncResult(false, List.of(), List.of(), List.of(), ex.getMessage());
		}
	}

	/**
	 * Namespaces an upstream tool with its server label.
	 *
	 * <p>Two servers may both offer {@code search}, and the model emits a single flat name, so
	 * collisions have to be resolved before the model ever sees them. The separator is an underscore
	 * because that is what a model reproduces reliably; a dot or a slash invites mangling.
	 */
	static String localName(McpServerEntity server, String upstreamName) {
		String prefix = sanitise(server.getLabel());
		String name = sanitise(upstreamName);
		String combined = prefix.isEmpty() ? name : prefix + "_" + name;
		// The registry caps names at 64 characters.
		return combined.length() <= 64 ? combined : combined.substring(0, 64);
	}

	private static String sanitise(String value) {
		if (value == null) {
			return "";
		}
		String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
				.replaceAll("_+", "_").replaceAll("^_|_$", "");
		return cleaned;
	}

	/**
	 * The description shown to the model.
	 *
	 * <p>An MCP server's own description is used as-is where it has one. Where it does not, a
	 * placeholder naming the tool is better than an empty string: for a small model the description
	 * is the strongest signal about when to use a tool, and nothing at all is close to unusable.
	 */
	private static String describe(McpServerEntity server, McpSchema.Tool tool) {
		if (tool.description() != null && !tool.description().isBlank()) {
			return tool.description();
		}
		if (tool.title() != null && !tool.title().isBlank()) {
			return tool.title();
		}
		return "The '%s' tool from the MCP server '%s'. No description was provided by the server; "
				.formatted(tool.name(), server.getLabel())
				+ "edit this to describe when the model should use it.";
	}

	/**
	 * Converts the upstream schema into the object-typed schema the harness expects.
	 *
	 * <p>Servers are inconsistent about this: some omit {@code type}, some send no schema at all for
	 * a zero-argument tool. Both are normalised rather than rejected, since a tool that works is more
	 * useful than a strict reading of a spec the server did not follow.
	 */
	private JsonNode normaliseSchema(java.util.Map<String, Object> inputSchema) {
		if (inputSchema == null || inputSchema.isEmpty()) {
			ObjectNode empty = objectMapper.createObjectNode();
			empty.put("type", "object");
			empty.set("properties", objectMapper.createObjectNode());
			return empty;
		}
		JsonNode node = objectMapper.valueToTree(inputSchema);
		if (node.isObject() && !node.has("type")) {
			((ObjectNode) node).put("type", "object");
		}
		return node;
	}

	private JsonNode executorConfig(McpServerEntity server, String upstreamName) {
		ObjectNode config = objectMapper.createObjectNode();
		config.put("serverId", server.getId().toString());
		config.put("serverLabel", server.getLabel());
		// The upstream name is kept because the local one is namespaced; calling the server with the
		// namespaced name would fail.
		config.put("upstreamName", upstreamName);
		return config;
	}

	/**
	 * Whether the stored definition already matches what the server is offering.
	 *
	 * <p>The executor configuration is part of the comparison, not just the description and schema.
	 * It carries the server's id, so a server deleted and recreated under the same label produces
	 * tools that look identical while pointing at an id that no longer exists. Comparing only the
	 * model-facing fields would leave those tools silently broken, failing at call time with
	 * "the MCP server this tool came from is no longer configured".
	 */
	private boolean isUnchanged(String localName, String description, JsonNode schema,
			JsonNode executorConfig) {
		return registry.findCurrent(localName)
				.map(current -> current.getDescription().equals(description)
						&& registry.parseSchema(current).equals(schema)
						&& registry.parseExecutorConfig(current).equals(executorConfig))
				.orElse(false);
	}

	/**
	 * @param imported  tools written as a new version because they were new or had changed upstream
	 * @param unchanged tools already matching what the server offers; no version was created
	 * @param skipped   tools the server offers that the allow-list excluded
	 */
	public record SyncResult(boolean succeeded, List<String> imported, List<String> unchanged,
			List<String> skipped, String error) {

		public int total() {
			return imported.size() + unchanged.size();
		}
	}
}
