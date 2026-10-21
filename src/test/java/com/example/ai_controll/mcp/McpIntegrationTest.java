package com.example.ai_controll.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolRegistryService;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end MCP coverage against a real MCP server running inside the test JVM.
 *
 * <p>Nothing on the development machine can currently launch an off-the-shelf MCP server -- there is
 * no Node, no uv, no Docker -- so rather than leaving the integration unverified, the test stands one
 * up itself using the SDK's server side over the same streamable HTTP transport a remote server would
 * use. This is a genuine protocol exchange: handshake, tool listing, tool invocation and error
 * reporting all cross the wire.
 *
 * <p>What it does not cover is the stdio path's child-process handling, which needs a real external
 * process. {@link McpStdioPolicyTest} covers the decision that gates it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(McpIntegrationTest.TestMcpServer.class)
class McpIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private McpServerRepository servers;

	@Autowired
	private McpClientManager clientManager;

	@Autowired
	private McpToolSyncService syncService;

	@Autowired
	private ToolRegistryService registry;

	@Autowired
	private McpToolExecutor executor;

	@Autowired
	private com.example.ai_controll.tool.registry.ToolDefinitionRepository toolDefinitions;

	private McpServerEntity server;

	@BeforeEach
	void setUp() {
		server = new McpServerEntity(UUID.randomUUID(), "testsrv", McpTransport.STREAMABLE_HTTP,
				Instant.now());
		server.setUrl("http://localhost:" + port);
		servers.save(server);
	}

	@AfterEach
	void tearDown() {
		clientManager.disconnect(server.getId());
		servers.deleteAll();
		// The registry outlives an individual test in a shared context, and these tests assert on the
		// difference between "imported" and "unchanged" -- which is precisely what leftover rows from
		// a previous test would erase.
		toolDefinitions.deleteAll();
	}

	@Test
	@DisplayName("connects and lists the tools the server offers")
	void discoversTools() {
		List<McpSchema.Tool> tools = clientManager.listTools(server);

		assertThat(tools).extracting(McpSchema.Tool::name)
				.contains("echo", "always_fails", "no_args");
	}

	@Test
	@DisplayName("imports discovered tools into the registry, namespaced by server label")
	void importsToolsIntoRegistry() {
		McpToolSyncService.SyncResult result = syncService.sync(server, Set.of());

		assertThat(result.succeeded()).isTrue();
		// Namespaced because two servers may both offer 'search' and the model emits one flat name.
		assertThat(result.imported()).contains("testsrv_echo", "testsrv_always_fails");

		ToolDefinitionEntity imported = registry.findCurrent("testsrv_echo").orElseThrow();
		assertThat(imported.getExecutorType()).isEqualTo(ExecutorType.MCP);
		assertThat(imported.getDescription()).contains("Echoes");
		// The schema must survive import, or validation would be checking against nothing.
		assertThat(registry.parseSchema(imported).get("properties").has("message")).isTrue();
	}

	@Test
	@DisplayName("an allow-list imports only the named tools")
	void honoursAllowList() {
		// Importing a subset is the common case: putting twenty tools in front of a small model
		// measurably hurts its ability to choose among them.
		McpToolSyncService.SyncResult result = syncService.sync(server, Set.of("echo"));

		assertThat(result.imported()).containsExactly("testsrv_echo");
		assertThat(result.skipped()).contains("always_fails", "no_args");
		assertThat(registry.findCurrent("testsrv_always_fails")).isEmpty();
	}

	@Test
	@DisplayName("re-syncing an unchanged server creates no new versions")
	void reSyncCreatesNoVersionChurn() {
		syncService.sync(server, Set.of("echo"));
		int versionAfterFirst = registry.findCurrent("testsrv_echo").orElseThrow().getVersion();

		McpToolSyncService.SyncResult second = syncService.sync(server, Set.of("echo"));

		// Every sync creating a version would make the history meaningless, and the history is the
		// whole reason the registry is versioned.
		assertThat(second.imported()).isEmpty();
		assertThat(second.unchanged()).containsExactly("testsrv_echo");
		assertThat(registry.findCurrent("testsrv_echo").orElseThrow().getVersion())
				.isEqualTo(versionAfterFirst);
	}

	@Test
	@DisplayName("an imported tool can be invoked through the executor")
	void invokesImportedTool() {
		syncService.sync(server, Set.of("echo"));
		ToolDefinitionEntity tool = registry.findCurrent("testsrv_echo").orElseThrow();

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 1, "call_1", tool,
				JsonMapper.builder().build().readTree("{\"message\":\"hello mcp\"}")));

		assertThat(outcome.succeeded()).isTrue();
		assertThat(outcome.output()).contains("hello mcp");
	}

	@Test
	@DisplayName("a tool that reports an error becomes a failed outcome, not an exception")
	void reportsToolErrorAsOutcome() {
		// MCP signals a tool's own failure with isError rather than a protocol fault, which is right:
		// "the file does not exist" is information for the model. It must reach the model as a normal
		// tool failure it can react to.
		syncService.sync(server, Set.of("always_fails"));
		ToolDefinitionEntity tool = registry.findCurrent("testsrv_always_fails").orElseThrow();

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 1, "call_1", tool,
				JsonMapper.builder().build().createObjectNode()));

		assertThat(outcome.succeeded()).isFalse();
		assertThat(outcome.errorKind()).isEqualTo("mcp_tool_error");
		assertThat(outcome.output()).contains("deliberately");
	}

	@Test
	@DisplayName("a zero-argument tool gets a usable object schema")
	void normalisesEmptySchema() {
		// Servers are inconsistent here; a tool with no arguments must still validate rather than
		// being rejected for having no schema.
		syncService.sync(server, Set.of("no_args"));

		ToolDefinitionEntity tool = registry.findCurrent("testsrv_no_args").orElseThrow();
		assertThat(registry.parseSchema(tool).get("type").asString()).isEqualTo("object");
	}

	@Test
	@DisplayName("a disabled server refuses calls without reaching the network")
	void refusesCallsToDisabledServer() {
		syncService.sync(server, Set.of("echo"));
		ToolDefinitionEntity tool = registry.findCurrent("testsrv_echo").orElseThrow();

		server.setEnabled(false);
		servers.save(server);

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 1, "call_1", tool,
				JsonMapper.builder().build().readTree("{\"message\":\"x\"}")));

		assertThat(outcome.succeeded()).isFalse();
		assertThat(outcome.errorKind()).isEqualTo("mcp_server_disabled");
	}

	@Test
	@DisplayName("an unreachable server is reported as an outcome the model can act on")
	void reportsUnreachableServer() {
		McpServerEntity dead = new McpServerEntity(UUID.randomUUID(), "dead",
				McpTransport.STREAMABLE_HTTP, Instant.now());
		// A port nothing is listening on.
		dead.setUrl("http://localhost:1");
		servers.save(dead);

		McpToolSyncService.SyncResult result = syncService.sync(dead, Set.of());

		// Recorded on the server rather than thrown, so the console can explain why it is not working.
		assertThat(result.succeeded()).isFalse();
		assertThat(result.error()).isNotBlank();
		assertThat(servers.findById(dead.getId()).orElseThrow().getStatus()).isEqualTo("ERROR");
	}

	/**
	 * A real MCP server, in this JVM, reachable over the same transport a remote one would use.
	 */
	@TestConfiguration
	static class TestMcpServer {

		@Bean
		HttpServletStreamableServerTransportProvider mcpTransportProvider() {
			return HttpServletStreamableServerTransportProvider.builder()
					.jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
					.mcpEndpoint("/mcp")
					.build();
		}

		/**
		 * Mapped at /mcp, not /, because Spring's own DispatcherServlet already owns the root and would
		 * answer the MCP handshake with a 404.
		 */
		@Bean
		ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
				HttpServletStreamableServerTransportProvider provider) {
			ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
					new ServletRegistrationBean<>(provider, "/mcp", "/mcp/*");
			registration.setAsyncSupported(true);
			registration.setLoadOnStartup(1);
			return registration;
		}

		@Bean
		McpSyncServer mcpTestServer(HttpServletStreamableServerTransportProvider provider) {
			return McpServer.sync(provider)
					.serverInfo("ai-controll-test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
					.toolCall(tool("echo", "Echoes the message back.",
							"""
									{"type":"object","properties":{"message":{"type":"string"}},
									 "required":["message"]}"""),
							(exchange, request) -> {
								Object message = request.arguments().get("message");
								return new McpSchema.CallToolResult(
										List.of(new McpSchema.TextContent("echo: " + message)), false, null, null);
							})
					.toolCall(tool("always_fails", "A tool that always reports an error.",
							"""
									{"type":"object","properties":{}}"""),
							(exchange, request) -> new McpSchema.CallToolResult(
									List.of(new McpSchema.TextContent("This tool deliberately fails.")), true,
									null, null))
					.toolCall(tool("no_args", "Takes no arguments at all.", null),
							(exchange, request) -> new McpSchema.CallToolResult(
									List.of(new McpSchema.TextContent("ok")), false, null, null))
					.build();
		}

		private static McpSchema.Tool tool(String name, String description, String schemaJson) {
			Map<String, Object> schema = schemaJson == null ? Map.of()
					: JsonMapper.builder().build().readValue(schemaJson, Map.class);
			return new McpSchema.Tool(name, null, description, schema, null, null, null);
		}
	}
}
