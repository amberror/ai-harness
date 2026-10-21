package com.example.ai_controll.tool.parse;

import java.util.List;
import java.util.Optional;

import com.example.ai_controll.conversation.ToolCallRequest;

import tools.jackson.databind.JsonNode;

/**
 * Normalises the various shapes a model uses when it writes a tool call as text.
 *
 * <p>There is no single convention. Across chat templates and fine-tunes the same call appears as
 * {@code {"name":…, "arguments":…}}, {@code {"name":…, "parameters":…}}, {@code {"tool":…, "args":…}}
 * or wrapped in an OpenAI-style {@code {"function":{…}}}. All of them mean the same thing, and a
 * harness that only recognised one would silently discard the rest.
 */
final class ToolCallShape {

	/** Keys a model might use for the tool's name. */
	private static final List<String> NAME_KEYS = List.of("name", "tool", "tool_name", "function_name");

	/** Keys a model might use for the arguments object. */
	private static final List<String> ARGUMENT_KEYS =
			List.of("arguments", "parameters", "args", "params", "input");

	private ToolCallShape() {
	}

	/**
	 * Interprets one JSON node as a tool call, if it looks like one.
	 *
	 * @param node       the candidate
	 * @param syntheticId id to assign, since a call recovered from text has none of its own
	 */
	static Optional<ToolCallRequest> from(JsonNode node, String syntheticId) {
		if (node == null || !node.isObject()) {
			return Optional.empty();
		}

		// OpenAI's nested form: {"type":"function","function":{"name":…,"arguments":…}}
		JsonNode function = node.get("function");
		if (function != null && function.isObject()) {
			return from(function, syntheticId);
		}

		String name = firstString(node, NAME_KEYS);
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}

		JsonNode arguments = firstPresent(node, ARGUMENT_KEYS);
		String rawArguments;
		if (arguments == null) {
			// A call with no arguments at all is legitimate for a zero-argument tool.
			rawArguments = "{}";
		}
		else if (arguments.isString()) {
			// Already encoded as a string, exactly as the native channel does it.
			rawArguments = arguments.stringValue();
		}
		else {
			rawArguments = arguments.toString();
		}

		return Optional.of(new ToolCallRequest(syntheticId, name, rawArguments));
	}

	private static String firstString(JsonNode node, List<String> keys) {
		for (String key : keys) {
			JsonNode value = node.get(key);
			if (value != null && value.isString()) {
				return value.stringValue();
			}
		}
		return null;
	}

	private static JsonNode firstPresent(JsonNode node, List<String> keys) {
		for (String key : keys) {
			JsonNode value = node.get(key);
			if (value != null && !value.isNull()) {
				return value;
			}
		}
		return null;
	}
}
