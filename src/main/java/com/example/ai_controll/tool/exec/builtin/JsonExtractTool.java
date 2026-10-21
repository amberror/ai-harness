package com.example.ai_controll.tool.exec.builtin;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads one value out of a JSON document by JSON Pointer.
 *
 * <p>This is the tool that makes the others compose. Every tool in the harness answers in JSON, so
 * a model that can pull {@code /result} out of one answer can feed it into the next call without
 * having to restate it correctly from memory -- and restating it from memory is where a small model
 * quietly corrupts a number it had right a moment ago.
 *
 * <p>It also has the only <strong>nested</strong> argument in the built-in set: {@code document} may
 * be a JSON string or an actual object or array. Whether a quantised model can emit a nested object
 * as a tool argument, rather than a string containing one, is a distinct capability from emitting
 * scalars, and worth being able to measure on its own.
 *
 * <p>A pointer that matches nothing is a successful call reporting {@code found: false}, not a
 * failure. The model asked a reasonable question and the answer is "there is nothing there"; dressing
 * that up as an error invites it to retry the same call rather than to think again.
 */
@Component
public class JsonExtractTool implements BuiltinTool {

	/** Beyond this the answer is not an extraction, it is the document again. */
	private static final int MAX_VALUE_CHARS = 4000;

	private final ObjectMapper objectMapper;

	public JsonExtractTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "json_extract";
	}

	@Override
	public String defaultDescription() {
		return "Reads a value out of a JSON document using a JSON Pointer, for example '/result' or "
				+ "'/items/0/name'. Use an empty pointer to get the whole document. Useful for taking "
				+ "one field out of another tool's answer.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "document":{"type":["object","array","string"],
				     "description":"The JSON document, either as an object or as a JSON string"},
				   "pointer":{"type":"string",
				     "description":"JSON Pointer to the value, e.g. /result or /items/0/name"}},
				 "required":["document","pointer"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		JsonNode document = arguments == null ? null : arguments.get("document");
		if (document == null || document.isNull()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("document", "a JSON object, array, or a string containing JSON"), 0L);
		}

		JsonNode parsed;
		if (document.isString()) {
			try {
				parsed = objectMapper.readTree(document.stringValue());
			}
			catch (RuntimeException ex) {
				return ToolOutcome.failure("invalid_json",
						"'document' was a string but not valid JSON: " + ex.getMessage(), 0L);
			}
		}
		else {
			parsed = document;
		}

		Optional<String> pointer = Args.text(arguments, "pointer");
		if (pointer.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("pointer", "a JSON Pointer such as /result"), 0L);
		}
		String path = normalise(pointer.get());

		JsonNode value;
		try {
			value = parsed.at(path);
		}
		catch (IllegalArgumentException ex) {
			return ToolOutcome.failure("invalid_pointer",
					"'%s' is not a valid JSON Pointer. Use a leading slash, e.g. /result."
							.formatted(pointer.get()),
					0L);
		}

		ObjectNode result = objectMapper.createObjectNode();
		result.put("pointer", path);
		if (value.isMissingNode()) {
			result.put("found", false);
			// Naming what is there turns a dead end into the next call the model can make.
			ArrayNode available = result.putArray("availableKeys");
			parsed.propertyNames().forEach(available::add);
			return ToolOutcome.success(result.toString(), 0L);
		}

		result.put("found", true);
		String rendered = value.isString() ? value.stringValue() : value.toString();
		if (rendered.length() > MAX_VALUE_CHARS) {
			result.put("value", rendered.substring(0, MAX_VALUE_CHARS));
			result.put("truncated", true);
		}
		else {
			result.set("value", value);
		}
		result.put("type", value.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
		return ToolOutcome.success(result.toString(), 0L);
	}

	/**
	 * Accepts the pointer spellings a model actually produces.
	 *
	 * <p>{@code result}, {@code $.result} and {@code /result} all mean the same thing to whoever
	 * wrote them, and only the last is a JSON Pointer. Refusing the other two would measure whether
	 * the model knows RFC 6901 rather than whether it can find a field.
	 */
	private static String normalise(String pointer) {
		String text = pointer.strip();
		if (text.isEmpty() || "/".equals(text)) {
			return "";
		}
		if (text.startsWith("$.")) {
			text = text.substring(2).replace('.', '/');
		}
		else if (text.startsWith("$")) {
			text = text.substring(1);
		}
		else {
			text = text.replace('.', '/');
		}
		return text.startsWith("/") ? text : "/" + text;
	}
}
