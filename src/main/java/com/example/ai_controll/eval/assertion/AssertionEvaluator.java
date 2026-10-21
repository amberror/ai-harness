package com.example.ai_controll.eval.assertion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.observability.RunEventView;
import com.example.ai_controll.orchestrator.domain.RunEventType;

import tools.jackson.databind.JsonNode;

/**
 * Checks a finished run against what the scenario said "right" would look like.
 *
 * <p>Everything is read from the run's own record: its terminal status, its final answer, and its
 * journal. That is the payoff for having built the journal first — an assertion can ask which tools
 * were called and with what arguments without any special instrumentation, because the answer was
 * already written down.
 *
 * <p>Judge assertions are handled elsewhere, since they need a model and everything here is pure.
 */
@Component
public class AssertionEvaluator {

	private static final Logger log = LoggerFactory.getLogger(AssertionEvaluator.class);

	/**
	 * Evaluates every deterministic assertion.
	 *
	 * @param evidence the run's outcome and journal
	 * @return one verdict per assertion, in the order given
	 */
	public List<AssertionVerdict> evaluate(List<AssertionSpec> specs, RunEvidence evidence) {
		List<AssertionVerdict> verdicts = new ArrayList<>();
		for (AssertionSpec spec : specs) {
			if (spec.kind() == AssertionKind.JUDGE) {
				continue; // needs a model; the batch runner handles it
			}
			verdicts.add(evaluateOne(spec, evidence));
		}
		return verdicts;
	}

	private AssertionVerdict evaluateOne(AssertionSpec spec, RunEvidence evidence) {
		try {
			return switch (spec.kind()) {
				case STATUS -> check(spec, evidence.status().equalsIgnoreCase(nullSafe(spec.value())),
						"status was " + evidence.status());

				case OUTPUT_CONTAINS -> check(spec,
						lower(evidence.finalOutput()).contains(lower(spec.value())),
						preview(evidence.finalOutput()));

				case OUTPUT_NOT_CONTAINS -> check(spec,
						!lower(evidence.finalOutput()).contains(lower(spec.value())),
						preview(evidence.finalOutput()));

				case OUTPUT_MATCHES -> matches(spec, evidence.finalOutput());

				case TOOL_CALLED -> check(spec, evidence.toolsCalled().contains(spec.value()),
						"tools called: " + evidence.toolsCalled());

				case TOOL_NOT_CALLED -> check(spec, !evidence.toolsCalled().contains(spec.value()),
						"tools called: " + evidence.toolsCalled());

				case TOOL_CALLED_WITH -> calledWith(spec, evidence);

				case MAX_TOOL_CALLS -> check(spec, evidence.toolCallsTotal() <= orZero(spec.number()),
						"%d tool calls".formatted(evidence.toolCallsTotal()));

				case NO_INVALID_TOOL_CALLS -> check(spec, evidence.toolCallsInvalid() == 0,
						"%d invalid tool calls".formatted(evidence.toolCallsInvalid()));

				case MAX_STEPS -> check(spec, evidence.stepsUsed() <= orZero(spec.number()),
						"%d steps".formatted(evidence.stepsUsed()));

				case MAX_TOKENS -> check(spec, evidence.totalTokens() <= orZero(spec.number()),
						"%d tokens".formatted(evidence.totalTokens()));

				case NO_EVENT -> noEvent(spec, evidence);

				case JUDGE -> AssertionVerdict.fail(spec, "a judge assertion reached the pure evaluator");
			};
		}
		catch (RuntimeException ex) {
			// A broken assertion must not take down the batch: the scenario is data, and bad data is
			// a failing check rather than a crash.
			log.warn("Assertion '{}' could not be evaluated", spec.describe(), ex);
			return AssertionVerdict.fail(spec, "could not be evaluated: " + ex.getMessage());
		}
	}

	private AssertionVerdict matches(AssertionSpec spec, String output) {
		try {
			boolean found = Pattern.compile(nullSafe(spec.value()), Pattern.DOTALL)
					.matcher(nullSafe(output)).find();
			return check(spec, found, preview(output));
		}
		catch (PatternSyntaxException ex) {
			return AssertionVerdict.fail(spec, "the pattern is not valid: " + ex.getDescription());
		}
	}

	private AssertionVerdict calledWith(AssertionSpec spec, RunEvidence evidence) {
		// A containment check rather than equality: a scenario usually cares that the model passed the
		// right city, not that it passed exactly one field and nothing else.
		boolean found = evidence.toolCallArguments().stream()
				.anyMatch(call -> call.tool().equals(spec.tool())
						&& lower(call.argumentsJson()).contains(lower(spec.value())));
		return check(spec, found, "calls: " + evidence.toolCallArguments());
	}

	private AssertionVerdict noEvent(AssertionSpec spec, RunEvidence evidence) {
		RunEventType type;
		try {
			type = RunEventType.valueOf(nullSafe(spec.value()).toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return AssertionVerdict.fail(spec, "'%s' is not an event type".formatted(spec.value()));
		}
		boolean present = evidence.events().stream().anyMatch(event -> event.type() == type);
		return check(spec, !present, present ? type + " occurred" : "no " + type);
	}

	private static AssertionVerdict check(AssertionSpec spec, boolean passed, String detail) {
		return passed ? AssertionVerdict.pass(spec, detail) : AssertionVerdict.fail(spec, detail);
	}

	private static String lower(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT);
	}

	private static String nullSafe(String text) {
		return text == null ? "" : text;
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static String preview(String output) {
		if (output == null) {
			return "output was empty";
		}
		String flat = output.replaceAll("\\s+", " ").strip();
		return "output: " + (flat.length() <= 160 ? flat : flat.substring(0, 159) + "…");
	}

	/**
	 * Everything an assertion may look at.
	 *
	 * <p>Assembled once per run rather than queried per assertion, so twelve checks do not mean
	 * twelve passes over the journal.
	 */
	public record RunEvidence(String status, String finalOutput, int stepsUsed, int toolCallsTotal,
			int toolCallsInvalid, int totalTokens, List<String> toolsCalled,
			List<ToolCall> toolCallArguments, List<RunEventView> events) {

		public RunEvidence {
			toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
			toolCallArguments = toolCallArguments == null ? List.of() : List.copyOf(toolCallArguments);
			events = events == null ? List.of() : List.copyOf(events);
		}

		/** Builds the evidence from a run's journal. */
		public static RunEvidence from(String status, String finalOutput, int stepsUsed,
				int toolCallsTotal, int toolCallsInvalid, int totalTokens, List<RunEventView> events) {
			List<String> tools = new ArrayList<>();
			List<ToolCall> calls = new ArrayList<>();

			for (RunEventView event : events) {
				if (event.type() != RunEventType.TOOL_CALL_PARSED || event.payload() == null) {
					continue;
				}
				JsonNode payload = event.payload();
				JsonNode tool = payload.get("tool");
				if (tool == null || !tool.isString()) {
					continue;
				}
				tools.add(tool.stringValue());
				JsonNode arguments = payload.get("arguments");
				calls.add(new ToolCall(tool.stringValue(),
						arguments == null ? "{}" : arguments.toString()));
			}

			return new RunEvidence(status, finalOutput, stepsUsed, toolCallsTotal, toolCallsInvalid,
					totalTokens, tools, calls, events);
		}
	}

	/** One tool call as recorded, for argument assertions. */
	public record ToolCall(String tool, String argumentsJson) {

		@Override
		public String toString() {
			return tool + argumentsJson;
		}
	}
}
