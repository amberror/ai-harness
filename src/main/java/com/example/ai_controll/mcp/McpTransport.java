package com.example.ai_controll.mcp;

/** How the harness reaches an MCP server. */
public enum McpTransport {

	/**
	 * A child process speaking JSON-RPC over stdin and stdout. What almost every existing MCP server
	 * uses, and the reason the executable allowlist exists: launching one means running a command.
	 */
	STDIO,

	/** HTTP with streaming responses, the current remote transport. */
	STREAMABLE_HTTP,

	/** The older HTTP transport, kept for servers that have not moved. */
	SSE
}
