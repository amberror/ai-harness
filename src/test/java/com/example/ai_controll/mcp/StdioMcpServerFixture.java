package com.example.ai_controll.mcp;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.json.JsonMapper;

/**
 * A real MCP server, spoken over this process's own standard input and output.
 *
 * <p>This exists to be launched as a child process, which is the only way to exercise the stdio path
 * properly. Everything the harness does differently for stdio -- resolving the executable, spawning
 * it, framing JSON-RPC over pipes, draining its standard error, and shutting it down without leaking
 * the process -- happens outside the JVM the test runs in, so an in-process server proves none of it.
 *
 * <p>Written as a fixture rather than depending on an off-the-shelf server because that would make
 * the test suite require Node, uv or Docker on every machine that builds this project. The one thing
 * already guaranteed to be present is a JVM.
 *
 * <p><strong>Nothing may be printed to standard output</strong> except protocol messages: this
 * process's stdout <em>is</em> the transport, and a stray line of logging corrupts the stream. Any
 * diagnostics go to standard error, which the harness drains and logs separately.
 */
public final class StdioMcpServerFixture {

	private StdioMcpServerFixture() {
	}

	public static void main(String[] args) throws InterruptedException {
		// The transport takes the real standard output, and System.out is then pointed at standard
		// error. Without this the first line any library logs -- logback's default appender writes to
		// System.out -- lands in the middle of the JSON-RPC stream and the handshake dies with a
		// parse error that names a character rather than a cause. It is the single most common way a
		// stdio MCP server fails to work, and it fails this way whether the stray line came from the
		// fixture or from something three dependencies down.
		java.io.PrintStream protocolOut = System.out;
		System.setOut(System.err);

		JsonMapper mapper = JsonMapper.builder().build();
		StdioServerTransportProvider transport = new StdioServerTransportProvider(
				new JacksonMcpJsonMapper(mapper), System.in, protocolOut);

		McpSyncServer server = McpServer.sync(transport)
				.serverInfo("ai-controll-stdio-fixture", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.toolCall(tool("shout", "Uppercases the message.", """
						{"type":"object","properties":{"message":{"type":"string"}},
						 "required":["message"]}"""),
						(exchange, request) -> {
							Object message = request.arguments().get("message");
							return new McpSchema.CallToolResult(List.of(
									new McpSchema.TextContent(String.valueOf(message).toUpperCase())), false,
									null, null);
						})
				.toolCall(tool("explode", "Always reports a failure.", "{\"type\":\"object\"}"),
						(exchange, request) -> new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("this tool always fails")), true, null, null))
				.build();

		// Announces itself where it cannot corrupt the protocol. The harness logs this line, which is
		// also what proves its standard-error draining works.
		System.err.println("stdio fixture ready");

		// Stays up until the harness reaps it, which is itself part of what this fixture verifies: a
		// stdio server that outlived its client would leak a JVM per connection.
		new java.util.concurrent.CountDownLatch(1).await();
		server.close();
	}

	private static McpSchema.Tool tool(String name, String description, String schemaJson) {
		@SuppressWarnings("unchecked")
		Map<String, Object> schema = JsonMapper.builder().build().readValue(schemaJson, Map.class);
		return new McpSchema.Tool(name, null, description, schema, null, null, null);
	}
}
