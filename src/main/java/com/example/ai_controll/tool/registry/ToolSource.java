package com.example.ai_controll.tool.registry;

/** Where a tool definition came from, which governs how it may be edited. */
public enum ToolSource {

	/** Created through the API or console. */
	USER,

	/** Seeded from a compiled built-in on first start. */
	BUILTIN,

	/** Imported from an MCP server and refreshed by syncing it. */
	MCP_IMPORT
}
