package com.example.ai_controll.tool.schema;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The result of checking one tool call before it is allowed to run.
 *
 * <p>A rejection carries a machine-readable explanation rather than a human sentence. Small models
 * respond far better to a concrete correction ({@code {"error":"unknown_tool","did_you_mean":...}})
 * than to prose, and the structure lets the console and evaluation assertions read the reason
 * without parsing English.
 *
 * @param status     what kind of outcome this is
 * @param arguments  the parsed arguments, present only when valid
 * @param feedback   the correction to send back, present only when invalid
 * @param violations individual schema violations, for the journal and the console
 */
public record ValidationOutcome(Status status, JsonNode arguments, JsonNode feedback,
		List<Violation> violations) {

	public ValidationOutcome {
		violations = violations == null ? List.of() : List.copyOf(violations);
	}

	public enum Status {

		/** The call names a real tool and its arguments satisfy the schema. */
		VALID,

		/** No tool by that name is exposed to this agent. */
		UNKNOWN_TOOL,

		/** The arguments were not valid JSON at all. */
		MALFORMED_JSON,

		/** The arguments parsed but did not satisfy the schema. */
		SCHEMA_VIOLATION
	}

	/**
	 * One specific problem with the arguments.
	 *
	 * @param path    JSON path to the offending location, e.g. {@code $.city}
	 * @param message what is wrong with it
	 * @param keyword the schema keyword that failed, e.g. {@code required} or {@code type}
	 */
	public record Violation(String path, String message, String keyword) {
	}

	public boolean isValid() {
		return status == Status.VALID;
	}

	public static ValidationOutcome valid(JsonNode arguments) {
		return new ValidationOutcome(Status.VALID, arguments, null, List.of());
	}

	public static ValidationOutcome invalid(Status status, JsonNode feedback,
			List<Violation> violations) {
		return new ValidationOutcome(status, null, feedback, violations);
	}
}
