package com.example.ai_controll.tool.registry;

/** How a tool's work actually gets done. */
public enum ExecutorType {

	/** A compiled Spring bean. Its code is fixed, but name, description and schema stay editable. */
	BUILTIN,

	/** Declared entirely as data: method, URL template, headers, body, response extraction. */
	HTTP,

	/** Proxied to a tool discovered on an MCP server. */
	MCP
}
