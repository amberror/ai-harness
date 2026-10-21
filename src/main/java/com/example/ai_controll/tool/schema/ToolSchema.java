package com.example.ai_controll.tool.schema;

import tools.jackson.databind.JsonNode;

/**
 * A tool as presented to the model: a name, a description, and a JSON Schema for its arguments.
 *
 * <p>This is the resolved, ready-to-send form. What the registry stores is richer (versions,
 * executor configuration, policy); by the time a schema reaches the model none of that is relevant.
 *
 * @param name             the identifier the model must emit to call this tool
 * @param description      what the tool does; for a small model this is the single most influential
 *                         piece of text in the whole request
 * @param parametersSchema JSON Schema describing the arguments object
 */
public record ToolSchema(String name, String description, JsonNode parametersSchema) {
}
