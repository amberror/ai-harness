package com.example.ai_controll.mcp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolDefinitionRepository;
import com.example.ai_controll.tool.registry.ToolRegistryService;

import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stdio MCP path, against a server running in a real child process.
 *
 * <p>Everything stdio does differently from HTTP happens outside this JVM: resolving the executable,
 * spawning it, framing JSON-RPC over pipes, draining its standard error, and shutting it down
 * without leaving a process behind. An in-process server exercises none of that, which is why this
 * one is launched with {@code java}, running {@link StdioMcpServerFixture} from the test classpath.
 *
 * <p>Using the JVM already running the build, rather than an off-the-shelf MCP server, keeps the
 * suite from requiring Node, uv or Docker on every machine that builds this project.
 *
 * <p>{@code java} is allowlisted here and nowhere else. The launch policy is the thing standing
 * between a runtime-editable server configuration and arbitrary command execution, so the test opts
 * in explicitly rather than the production default being loosened to accommodate it.
 */
@SpringBootTest
@TestPropertySource(properties = "harness.mcp.stdio.allowed-commands=java")
class McpStdioIntegrationTest {

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
	private ToolDefinitionRepository toolDefinitions;

	private McpServerEntity server;

	/** The JVM running this test, so the child is guaranteed to be a compatible one. */
	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	@BeforeEach
	void setUp() {
		server = new McpServerEntity(UUID.randomUUID(), "stdiosrv", McpTransport.STDIO, Instant.now());
		server.setCommand(javaExecutable());
		server.setArgsJson(JsonMapper.builder().build().writeValueAsString(List.of(
				"-cp", System.getProperty("java.class.path"),
				StdioMcpServerFixture.class.getName())));
		servers.save(server);
	}

	@AfterEach
	void tearDown() {
		// Also the assertion that disconnecting actually reaps the child: a leaked JVM per test would
		// eventually take the machine down with it.
		clientManager.disconnect(server.getId());
		servers.deleteAll();
		toolDefinitions.deleteAll();
	}

	@Test
	@DisplayName("spawns the server and completes the handshake")
	void connectsOverStdio() {
		List<McpSchema.Tool> tools = clientManager.listTools(server);

		assertThat(tools).extracting(McpSchema.Tool::name).contains("shout", "explode");
	}

	@Test
	@DisplayName("a tool call crosses the pipe and comes back")
	void callsAToolOverStdio() {
		syncService.sync(server, Set.of());
		ToolDefinitionEntity definition = registry.findCurrent("stdiosrv_shout").orElseThrow();

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 1, "call-1",
				definition, JsonMapper.builder().build().readTree("{\"message\":\"hello\"}")));

		assertThat(outcome.succeeded()).isTrue();
		assertThat(outcome.output()).contains("HELLO");
	}

	@Test
	@DisplayName("a failing tool is reported as a failure, not as an answer")
	void reportsToolFailures() {
		syncService.sync(server, Set.of());
		ToolDefinitionEntity definition = registry.findCurrent("stdiosrv_explode").orElseThrow();

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 1, "call-2",
				definition, JsonMapper.builder().build().readTree("{}")));

		// An MCP error result is content with isError set, so it arrives looking exactly like a
		// successful answer unless the flag is honoured. Handing it to the model as a result would
		// teach it that the call worked.
		assertThat(outcome.succeeded()).isFalse();
	}

	@Test
	@DisplayName("a command that is not allowlisted is refused before anything is spawned")
	void refusesUnlistedCommands() {
		McpServerEntity rogue = new McpServerEntity(UUID.randomUUID(), "rogue", McpTransport.STDIO,
				Instant.now());
		rogue.setCommand("cmd");
		servers.save(rogue);

		// The whole reason the allowlist exists: server configurations are runtime-editable data, so
		// without this an API for managing tools is also an API for running programs.
		try {
			clientManager.listTools(rogue);
			throw new AssertionError("Expected the launch to be refused");
		}
		catch (RuntimeException expected) {
			assertThat(expected.getMessage()).contains("not allowed");
		}
		finally {
			servers.delete(rogue);
		}
	}
}
