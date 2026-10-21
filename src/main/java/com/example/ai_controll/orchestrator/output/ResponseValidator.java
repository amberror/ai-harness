package com.example.ai_controll.orchestrator.output;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.schema.JsonSchemas;
import com.example.ai_controll.tool.schema.ValidationOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Holds the model to a contract on its final answer, the way tool arguments are already held to one.
 *
 * <p>Until now the harness stopped being strict exactly where it mattered most to anything
 * downstream. A tool call was parsed, validated against a schema and corrected until it was right;
 * the answer -- the thing another system would actually consume -- was whatever the model felt like
 * writing, and evaluation could do no better than search it for a substring. For a harness meant to
 * drive real work that is the wrong way round.
 *
 * <p>The correction is written for the model rather than for a person, on the same principle as the
 * tool feedback: quote what it wrote back at it, name the failing field, and repeat the schema. A
 * small model recovers from a concrete instruction far more reliably than from a complaint.
 *
 * <p>Fenced answers are unwrapped before parsing. Instructed to answer in JSON, a small model very
 * often wraps it in a Markdown code fence, and failing that is pedantry rather than measurement --
 * the model did produce the object asked for. What it wrote is still recorded exactly as it came.
 */
@Component
public class ResponseValidator {

	private final ObjectMapper objectMapper;

	private final JsonSchemas schemas;

	public ResponseValidator(ObjectMapper objectMapper, JsonSchemas schemas) {
		this.objectMapper = objectMapper;
		this.schemas = schemas;
	}

	/**
	 * Checks one answer against the schema its run requires.
	 *
	 * @return the parsed value when it satisfies the schema, otherwise the correction to send back
	 */
	public Result validate(String answer, JsonNode schema) {
		String text = unwrap(answer);
		if (text.isBlank()) {
			return Result.invalid("empty_answer", feedback("empty_answer",
					"The run requires a JSON answer matching the schema, but nothing was produced.",
					answer, schema, List.of()));
		}

		JsonNode parsed;
		try {
			parsed = this.objectMapper.readTree(text);
		}
		catch (RuntimeException ex) {
			return Result.invalid("malformed_json", feedback("malformed_json",
					"The answer was not valid JSON. Reply with the JSON object only, and nothing else.",
					answer, schema, List.of()));
		}

		List<ValidationOutcome.Violation> violations = this.schemas.violations(schema, parsed);
		if (violations.isEmpty()) {
			return Result.valid(parsed);
		}
		String summary = violations.stream()
				.map(violation -> "%s %s".formatted(violation.path(), violation.message()))
				.reduce((a, b) -> a + "; " + b).orElse("");
		return Result.invalid("schema_violation", feedback("invalid_answer",
				"The answer does not match the required schema: " + summary, answer, schema,
				violations));
	}

	private ObjectNode feedback(String error, String message, String answer, JsonNode schema,
			List<ValidationOutcome.Violation> violations) {
		ObjectNode node = this.objectMapper.createObjectNode();
		node.put("error", error);
		node.put("message", message);
		node.put("received", truncate(answer));
		if (!violations.isEmpty()) {
			ArrayNode details = node.putArray("violations");
			for (ValidationOutcome.Violation violation : violations) {
				ObjectNode detail = details.addObject();
				detail.put("path", violation.path());
				detail.put("message", violation.message());
				detail.put("keyword", violation.keyword());
			}
		}
		node.set("expected_schema", schema);
		node.put("hint", "Reply with a single JSON object matching the schema. No prose, no code fence.");
		return node;
	}

	/** Strips a Markdown code fence, which a small model adds unbidden more often than not. */
	static String unwrap(String answer) {
		if (answer == null) {
			return "";
		}
		String text = answer.strip();
		if (!text.startsWith("```")) {
			return text;
		}
		int firstBreak = text.indexOf('\n');
		if (firstBreak < 0) {
			return text;
		}
		String body = text.substring(firstBreak + 1);
		int close = body.lastIndexOf("```");
		return (close < 0 ? body : body.substring(0, close)).strip();
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 800 ? text : text.substring(0, 800) + "…";
	}

	/**
	 * The verdict on one answer.
	 *
	 * @param value    the parsed answer when valid, so a caller need not parse it a second time
	 * @param reason   a short machine-readable cause, for the journal
	 * @param feedback the correction to put back into the conversation
	 */
	public record Result(boolean valid, JsonNode value, String reason, JsonNode feedback) {

		static Result valid(JsonNode value) {
			return new Result(true, value, null, null);
		}

		static Result invalid(String reason, JsonNode feedback) {
			return new Result(false, null, reason, feedback);
		}
	}
}
