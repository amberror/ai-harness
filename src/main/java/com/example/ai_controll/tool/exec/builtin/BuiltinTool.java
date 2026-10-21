package com.example.ai_controll.tool.exec.builtin;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;

/**
 * A tool whose behaviour is compiled Java.
 *
 * <p>The code is fixed, but everything the model sees -- name, description, argument schema, whether
 * the tool is offered at all -- stays editable at runtime through the registry. That split is
 * deliberate: rewording a description to see whether a 4B model then calls the tool correctly is one
 * of the most useful experiments available, and it should never require a rebuild.
 */
public interface BuiltinTool {

	/** Registry name; also the identifier the model emits. */
	String name();

	/** Default description, seeded on first start and editable thereafter. */
	String defaultDescription();

	/** Default JSON Schema for the arguments, as a JSON string. */
	String defaultSchema();

	ToolOutcome run(ToolInvocation invocation, JsonNode arguments);
}
