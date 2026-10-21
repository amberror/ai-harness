package com.example.ai_controll.tool.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Repairs the specific ways a small quantised model mangles tool arguments.
 *
 * <p>This runs only when an agent profile opts into lenient mode. The default is strict, because
 * silently fixing the model's output destroys the measurement: a harness whose purpose is to say how
 * reliably a 4B model follows a schema cannot also be quietly correcting it. Every fix applied is
 * returned by name so the journal records exactly what the harness changed and why, and so an
 * experiment can compare strict and lenient runs of the same scenario.
 *
 * <p>The repairs here are the defects actually seen from quantised models, not a general-purpose
 * JSON5 parser. Anything beyond them is left to fail, which is the honest outcome.
 */
@Component
public class ArgumentCoercer {

	/** Arguments wrapped in a markdown code fence, sometimes with a language tag. */
	private static final Pattern FENCED = Pattern.compile(
			"\\A\\s*```(?:json|JSON)?\\s*(.*?)\\s*```\\s*\\z", Pattern.DOTALL);

	/** A trailing comma before a closing brace or bracket. */
	private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

	/** Unquoted object keys, e.g. {@code {city: "Kyiv"}}. */
	private static final Pattern UNQUOTED_KEY = Pattern.compile("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:");

	private final ObjectMapper objectMapper;

	public ArgumentCoercer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Attempts to turn raw argument text into a JSON object.
	 *
	 * @return the parsed node plus the names of the fixes that were needed, or a failure carrying the
	 *         text as far as it could be repaired
	 */
	public Result parse(String rawArguments) {
		List<String> fixes = new ArrayList<>();
		String text = rawArguments == null ? "" : rawArguments.strip();

		// An absent or empty argument string is the model's way of saying "no arguments", which is
		// legitimate for a zero-argument tool and must not be treated as malformed.
		if (text.isEmpty()) {
			return Result.parsed(objectMapper.createObjectNode(), fixes);
		}

		JsonNode direct = tryParse(text);
		if (direct != null) {
			return finish(direct, fixes);
		}

		Matcher fenced = FENCED.matcher(text);
		if (fenced.matches()) {
			text = fenced.group(1);
			fixes.add("removed_markdown_fence");
			JsonNode node = tryParse(text);
			if (node != null) {
				return finish(node, fixes);
			}
		}

		String repaired = TRAILING_COMMA.matcher(text).replaceAll("$1");
		if (!repaired.equals(text)) {
			fixes.add("removed_trailing_comma");
			text = repaired;
			JsonNode node = tryParse(text);
			if (node != null) {
				return finish(node, fixes);
			}
		}

		repaired = UNQUOTED_KEY.matcher(text).replaceAll("$1\"$2\":");
		if (!repaired.equals(text)) {
			fixes.add("quoted_object_keys");
			text = repaired;
			JsonNode node = tryParse(text);
			if (node != null) {
				return finish(node, fixes);
			}
		}

		// Python literals leak in from models trained heavily on Python.
		repaired = text.replaceAll("\\bTrue\\b", "true")
				.replaceAll("\\bFalse\\b", "false")
				.replaceAll("\\bNone\\b", "null");
		if (!repaired.equals(text)) {
			fixes.add("normalised_python_literals");
			text = repaired;
			JsonNode node = tryParse(text);
			if (node != null) {
				return finish(node, fixes);
			}
		}

		// Single-quoted strings. Applied last because it is the crudest substitution and would
		// corrupt any apostrophe inside a correctly double-quoted string.
		if (text.indexOf('\'') >= 0 && text.indexOf('"') < 0) {
			repaired = text.replace('\'', '"');
			fixes.add("converted_single_quotes");
			JsonNode node = tryParse(repaired);
			if (node != null) {
				return finish(node, fixes);
			}
		}

		return Result.failed(fixes);
	}

	/**
	 * Nudges scalar values towards the types the schema asks for.
	 *
	 * <p>The characteristic small-model mistake is quoting everything: {@code {"count":"5"}} where the
	 * schema wants a number. Only lossless conversions are performed -- a string that does not parse
	 * as the target type is left alone to fail validation honestly.
	 */
	public Result coerceToSchema(JsonNode arguments, JsonNode schema) {
		List<String> fixes = new ArrayList<>();
		if (arguments == null || !arguments.isObject() || schema == null) {
			return Result.parsed(arguments, fixes);
		}
		JsonNode properties = schema.get("properties");
		if (properties == null || !properties.isObject()) {
			return Result.parsed(arguments, fixes);
		}

		ObjectNode result = (ObjectNode) arguments.deepCopy();
		for (String field : properties.propertyNames()) {
			JsonNode value = result.get(field);
			JsonNode expected = properties.get(field).get("type");
			if (value == null || expected == null || !value.isTextual()) {
				continue;
			}
			String wanted = expected.isArray() ? firstNonNullType(expected) : expected.asString();
			String text = value.stringValue();

			switch (wanted == null ? "" : wanted) {
				case "integer" -> {
					try {
						result.put(field, Long.parseLong(text.trim()));
						fixes.add("coerced_%s_to_integer".formatted(field));
					}
					catch (NumberFormatException ignored) {
						// Leave it; validation will report it properly.
					}
				}
				case "number" -> {
					try {
						result.put(field, Double.parseDouble(text.trim()));
						fixes.add("coerced_%s_to_number".formatted(field));
					}
					catch (NumberFormatException ignored) {
						// Leave it.
					}
				}
				case "boolean" -> {
					String lower = text.trim().toLowerCase();
					if ("true".equals(lower) || "false".equals(lower)) {
						result.put(field, Boolean.parseBoolean(lower));
						fixes.add("coerced_%s_to_boolean".formatted(field));
					}
				}
				default -> {
					// Strings and structured types are left as they are.
				}
			}
		}
		return Result.parsed(result, fixes);
	}

	private static String firstNonNullType(JsonNode typeArray) {
		for (JsonNode entry : typeArray) {
			if (entry.isString() && !"null".equals(entry.asString())) {
				return entry.asString();
			}
		}
		return null;
	}

	private Result finish(JsonNode node, List<String> fixes) {
		// A model sometimes double-encodes: the arguments are a JSON string whose content is the real
		// JSON object. Unwrap one level, but only when it actually yields an object.
		if (node.isString()) {
			JsonNode inner = tryParse(node.stringValue());
			if (inner != null && inner.isObject()) {
				fixes.add("unwrapped_double_encoded_json");
				return Result.parsed(inner, fixes);
			}
		}
		return Result.parsed(node, fixes);
	}

	private JsonNode tryParse(String text) {
		try {
			return objectMapper.readTree(text);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * @param value       the repaired value, or null when it could not be parsed at all
	 * @param appliedFixes names of the repairs that were needed, in the order applied
	 */
	public record Result(JsonNode value, List<String> appliedFixes) {

		public Result {
			appliedFixes = appliedFixes == null ? List.of() : List.copyOf(appliedFixes);
		}

		static Result parsed(JsonNode value, List<String> fixes) {
			return new Result(value, fixes);
		}

		static Result failed(List<String> fixes) {
			return new Result(null, fixes);
		}

		public boolean succeeded() {
			return value != null;
		}

		public boolean changedAnything() {
			return !appliedFixes.isEmpty();
		}
	}
}
