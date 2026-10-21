package com.example.ai_controll.lmstudio.port;

import tools.jackson.databind.JsonNode;

/**
 * Asks the server to constrain what the model may emit to a JSON Schema.
 *
 * <p>This is a genuinely different mechanism from the harness checking the answer afterwards. Here
 * llama.cpp compiles the schema into a grammar and rejects any token that would take the output off
 * it, so the answer cannot be malformed -- at the cost of pushing the model down a path it might not
 * have chosen, which on a small model sometimes costs accuracy to buy syntax.
 *
 * <p>The harness supports both and treats the choice as measurable rather than settled: run the same
 * scenario with the constraint on and off, and the pass rates say which one this model does better
 * under. That comparison is the reason both paths exist.
 *
 * @param name   a name for the schema; required by the wire format, otherwise unused
 * @param schema the JSON Schema the answer must satisfy
 * @param strict whether the server should refuse anything the schema does not allow
 */
public record ResponseFormat(String name, JsonNode schema, boolean strict) {

	public static ResponseFormat jsonSchema(JsonNode schema) {
		return new ResponseFormat("harness_response", schema, true);
	}
}
