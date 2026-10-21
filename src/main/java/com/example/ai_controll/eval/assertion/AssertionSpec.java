package com.example.ai_controll.eval.assertion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One check to make against a completed run.
 *
 * <p>A single loosely-typed {@code value} rather than a field per kind: scenarios are edited as JSON
 * and exported as YAML, and a shape with twelve mostly-null fields is unpleasant to write by hand
 * and worse to read in a diff.
 *
 * @param kind   what to check
 * @param value  the expected string, pattern, tool name or event type, depending on the kind
 * @param number the numeric limit for the ceiling kinds
 * @param tool   the tool name for {@link AssertionKind#TOOL_CALLED_WITH}
 * @param label  optional human name, so a failure reads as something other than a bare expression
 */
// Nulls omitted: an exported scenario is meant to be committed and read in a diff, and
// 'number: null, tool: null, label: null' on every assertion buries the two fields that matter.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssertionSpec(AssertionKind kind, String value, Integer number, String tool,
		String label) {

	public static AssertionSpec of(AssertionKind kind, String value) {
		return new AssertionSpec(kind, value, null, null, null);
	}

	public static AssertionSpec limit(AssertionKind kind, int number) {
		return new AssertionSpec(kind, null, number, null, null);
	}

	/** What to call this check when reporting it. */
	public String describe() {
		if (label != null && !label.isBlank()) {
			return label;
		}
		return switch (kind) {
			case STATUS -> "status is " + value;
			case OUTPUT_CONTAINS -> "output contains '%s'".formatted(value);
			case OUTPUT_NOT_CONTAINS -> "output does not contain '%s'".formatted(value);
			case OUTPUT_MATCHES -> "output matches /%s/".formatted(value);
			case TOOL_CALLED -> "called '%s'".formatted(value);
			case TOOL_NOT_CALLED -> "did not call '%s'".formatted(value);
			case TOOL_CALLED_WITH -> "called '%s' with %s".formatted(tool, value);
			case MAX_TOOL_CALLS -> "at most %d tool calls".formatted(number);
			case NO_INVALID_TOOL_CALLS -> "no invalid tool calls";
			case MAX_STEPS -> "at most %d steps".formatted(number);
			case MAX_TOKENS -> "at most %d tokens".formatted(number);
			case NO_EVENT -> "no %s event".formatted(value);
			case JUDGE -> "a model judged: %s".formatted(value);
		};
	}
}
