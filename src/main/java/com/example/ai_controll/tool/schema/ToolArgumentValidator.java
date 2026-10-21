package com.example.ai_controll.tool.schema;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.conversation.ToolCallRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The gate every tool call passes through before it is allowed to run.
 *
 * <p>This is where a raw model becomes predictable. A small model will invent tool names, omit
 * required fields and quote numbers; none of that is exceptional and none of it should reach an
 * executor. Each rejection produces a correction aimed at the model rather than at a human, because
 * the correction is fed straight back into the conversation and the model has to be able to act on
 * it. Concrete beats polite: naming the missing field and the tools that do exist gets a 4B model
 * back on track far more reliably than "invalid arguments".
 */
@Component
public class ToolArgumentValidator {

	/** Beyond this edit distance a suggestion is more likely to mislead than to help. */
	private static final int MAX_SUGGESTION_DISTANCE = 3;

	private final ObjectMapper objectMapper;

	private final ArgumentCoercer coercer;

	private final JsonSchemas schemas;

	public ToolArgumentValidator(ObjectMapper objectMapper, ArgumentCoercer coercer,
			JsonSchemas schemas) {
		this.objectMapper = objectMapper;
		this.coercer = coercer;
		this.schemas = schemas;
	}

	/**
	 * Checks one tool call against the tools the agent was actually offered.
	 *
	 * @param call      the call as the model emitted it
	 * @param available the tools exposed on this step; a name outside this list is unknown even if the
	 *                  registry knows it, because the model was never shown it
	 * @param lenient   whether to attempt repairs before rejecting
	 */
	public ValidationOutcome validate(ToolCallRequest call, List<ToolSchema> available,
			boolean lenient) {
		Optional<ToolSchema> match = available.stream()
				.filter(tool -> tool.name().equals(call.name()))
				.findFirst();

		if (match.isEmpty()) {
			return unknownTool(call, available);
		}
		ToolSchema tool = match.get();

		ArgumentCoercer.Result parsed = lenient ? coercer.parse(call.rawArguments())
				: strictParse(call.rawArguments());
		if (!parsed.succeeded()) {
			return malformedJson(call, tool);
		}

		JsonNode arguments = parsed.value();
		List<ValidationOutcome.Violation> violations = check(tool, arguments);

		if (!violations.isEmpty() && lenient) {
			// Retry once after nudging scalars towards the declared types. Quoting a number is the
			// single most common schema failure from a quantised model.
			ArgumentCoercer.Result coerced = coercer.coerceToSchema(arguments, tool.parametersSchema());
			if (coerced.changedAnything()) {
				List<ValidationOutcome.Violation> after = check(tool, coerced.value());
				if (after.isEmpty()) {
					return ValidationOutcome.valid(coerced.value());
				}
				violations = after;
			}
		}

		if (violations.isEmpty()) {
			return ValidationOutcome.valid(arguments);
		}
		return schemaViolation(tool, arguments, violations);
	}

	// -------------------------------------------------------------------------------------------
	// Rejections. Each builds the message the model will actually receive.
	// -------------------------------------------------------------------------------------------

	private ValidationOutcome unknownTool(ToolCallRequest call, List<ToolSchema> available) {
		List<String> names = available.stream().map(ToolSchema::name).sorted().toList();
		String suggestion = closestName(call.name(), names);

		ObjectNode feedback = objectMapper.createObjectNode();
		feedback.put("error", "unknown_tool");
		feedback.put("message", suggestion == null
				? "No tool named '%s' is available. Use one of the tools listed in 'available'."
						.formatted(call.name())
				: "No tool named '%s' is available. Did you mean '%s'?".formatted(call.name(), suggestion));
		if (suggestion != null) {
			feedback.put("did_you_mean", suggestion);
		}
		ArrayNode list = feedback.putArray("available");
		names.forEach(list::add);

		return ValidationOutcome.invalid(ValidationOutcome.Status.UNKNOWN_TOOL, feedback,
				List.of(new ValidationOutcome.Violation("$", "unknown tool '%s'".formatted(call.name()),
						"toolName")));
	}

	private ValidationOutcome malformedJson(ToolCallRequest call, ToolSchema tool) {
		ObjectNode feedback = objectMapper.createObjectNode();
		feedback.put("error", "malformed_arguments");
		feedback.put("message", "The arguments for '%s' were not valid JSON.".formatted(tool.name()));
		// Quoting the text back is what makes this actionable: the model can see its own mistake.
		feedback.put("received", truncate(call.rawArguments()));
		feedback.set("expected_schema", tool.parametersSchema());
		feedback.put("hint", "Reply with the same tool call, with arguments as a single valid JSON object.");

		return ValidationOutcome.invalid(ValidationOutcome.Status.MALFORMED_JSON, feedback,
				List.of(new ValidationOutcome.Violation("$", "arguments were not valid JSON", "json")));
	}

	private ValidationOutcome schemaViolation(ToolSchema tool, JsonNode arguments,
			List<ValidationOutcome.Violation> violations) {
		ObjectNode feedback = objectMapper.createObjectNode();
		feedback.put("error", "invalid_arguments");
		feedback.put("message", "The arguments for '%s' do not match its schema: %s".formatted(
				tool.name(),
				violations.stream().map(v -> "%s %s".formatted(v.path(), v.message()))
						.reduce((a, b) -> a + "; " + b).orElse("")));

		ArrayNode details = feedback.putArray("violations");
		for (ValidationOutcome.Violation violation : violations) {
			ObjectNode node = details.addObject();
			node.put("path", violation.path());
			node.put("message", violation.message());
			node.put("keyword", violation.keyword());
		}
		feedback.set("received", arguments);
		feedback.set("expected_schema", tool.parametersSchema());

		return ValidationOutcome.invalid(ValidationOutcome.Status.SCHEMA_VIOLATION, feedback, violations);
	}

	// -------------------------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------------------------

	private List<ValidationOutcome.Violation> check(ToolSchema tool, JsonNode arguments) {
		return schemas.violations(tool.parametersSchema(), arguments);
	}

	/** Strict mode parses exactly what the model sent, with no repair attempted. */
	private ArgumentCoercer.Result strictParse(String rawArguments) {
		String text = rawArguments == null ? "" : rawArguments.strip();
		if (text.isEmpty()) {
			// A zero-argument tool legitimately gets no arguments; that is not a defect.
			return new ArgumentCoercer.Result(objectMapper.createObjectNode(), List.of());
		}
		try {
			return new ArgumentCoercer.Result(objectMapper.readTree(text), List.of());
		}
		catch (RuntimeException ex) {
			return new ArgumentCoercer.Result(null, List.of());
		}
	}

	/** The nearest available tool name, or null when nothing is close enough to suggest. */
	static String closestName(String emitted, List<String> candidates) {
		if (emitted == null || candidates.isEmpty()) {
			return null;
		}
		String best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (String candidate : candidates) {
			int distance = editDistance(emitted.toLowerCase(), candidate.toLowerCase());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return bestDistance <= MAX_SUGGESTION_DISTANCE ? best : null;
	}

	/** Levenshtein distance, two rows rather than a full matrix. */
	private static int editDistance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 500 ? text : text.substring(0, 500) + "…";
	}
}
