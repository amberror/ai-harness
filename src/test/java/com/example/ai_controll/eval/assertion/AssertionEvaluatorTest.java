package com.example.ai_controll.eval.assertion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.observability.RunEventView;
import com.example.ai_controll.orchestrator.domain.RunEventType;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "the model did the right thing" means, made checkable.
 *
 * <p>Every kind here is exactly reproducible: the same run yields the same verdict forever. That is
 * the property that lets a change in pass rate be blamed on the model rather than on the instrument
 * measuring it.
 */
class AssertionEvaluatorTest {

	private final AssertionEvaluator evaluator = new AssertionEvaluator();

	private final JsonMapper mapper = JsonMapper.builder().build();

	private RunEventView toolCallEvent(long seq, String tool, String argumentsJson) {
		return new RunEventView(UUID.randomUUID(), seq, 1, RunEventType.TOOL_CALL_PARSED,
				Instant.now(), null,
				mapper.readTree("{\"tool\":\"%s\",\"arguments\":%s}".formatted(tool, argumentsJson)));
	}

	private RunEventView event(long seq, RunEventType type) {
		return new RunEventView(UUID.randomUUID(), seq, 1, type, Instant.now(), null, null);
	}

	private AssertionEvaluator.RunEvidence evidence(String status, String output,
			RunEventView... events) {
		return AssertionEvaluator.RunEvidence.from(status, output, 2, 1, 0, 500, List.of(events));
	}

	private boolean passes(AssertionSpec spec, AssertionEvaluator.RunEvidence evidence) {
		return evaluator.evaluate(List.of(spec), evidence).getFirst().passed();
	}

	@Test
	@DisplayName("status is checked against the run's terminal state")
	void checksStatus() {
		AssertionEvaluator.RunEvidence finished = evidence("FINISHED", "done");

		assertThat(passes(AssertionSpec.of(AssertionKind.STATUS, "FINISHED"), finished)).isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.STATUS, "ABORTED_LOOP"), finished)).isFalse();
	}

	@Test
	@DisplayName("output matching is case-insensitive")
	void checksOutputContains() {
		AssertionEvaluator.RunEvidence run = evidence("FINISHED", "The answer is 16537907.");

		// Case-insensitive because a scenario cares whether the number is there, not how the model
		// capitalised the sentence around it.
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_CONTAINS, "16537907"), run)).isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_CONTAINS, "THE ANSWER"), run)).isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_CONTAINS, "wrong"), run)).isFalse();
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_NOT_CONTAINS, "wrong"), run)).isTrue();
	}

	@Test
	@DisplayName("a regular expression can match across lines")
	void checksOutputMatches() {
		AssertionEvaluator.RunEvidence run = evidence("FINISHED", "Result:\n  1664753\nDone.");

		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_MATCHES, "Result:.*\\d{7}"), run))
				.isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_MATCHES, "\\bnope\\b"), run)).isFalse();
	}

	@Test
	@DisplayName("an invalid regular expression fails the check rather than the batch")
	void survivesBadPattern() {
		// A scenario is data, and bad data should produce a failing check with an explanation, not an
		// exception that takes down a hundred repetitions.
		AssertionVerdict verdict = evaluator.evaluate(
				List.of(AssertionSpec.of(AssertionKind.OUTPUT_MATCHES, "[unclosed")),
				evidence("FINISHED", "x")).getFirst();

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.detail()).contains("not valid");
	}

	@Test
	@DisplayName("which tools were called is read straight from the journal")
	void checksToolCalled() {
		AssertionEvaluator.RunEvidence run = evidence("FINISHED", "done",
				toolCallEvent(1, "calc_eval", "{\"expression\":\"2+2\"}"));

		assertThat(passes(AssertionSpec.of(AssertionKind.TOOL_CALLED, "calc_eval"), run)).isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.TOOL_CALLED, "http_fetch"), run)).isFalse();
		assertThat(passes(AssertionSpec.of(AssertionKind.TOOL_NOT_CALLED, "http_fetch"), run)).isTrue();
	}

	@Test
	@DisplayName("arguments are matched by containment, not equality")
	void checksToolCalledWith() {
		// A scenario usually cares that the model passed the right city, not that it passed exactly
		// one field and nothing else.
		AssertionEvaluator.RunEvidence run = evidence("FINISHED", "done",
				toolCallEvent(1, "get_weather", "{\"city\":\"Kyiv\",\"days\":3}"));

		assertThat(passes(new AssertionSpec(AssertionKind.TOOL_CALLED_WITH, "Kyiv", null,
				"get_weather", null), run)).isTrue();
		assertThat(passes(new AssertionSpec(AssertionKind.TOOL_CALLED_WITH, "Lviv", null,
				"get_weather", null), run)).isFalse();
		// The tool name still has to match, or an argument check would pass on the wrong call.
		assertThat(passes(new AssertionSpec(AssertionKind.TOOL_CALLED_WITH, "Kyiv", null,
				"other_tool", null), run)).isFalse();
	}

	@Test
	@DisplayName("ceilings are inclusive")
	void checksCeilings() {
		AssertionEvaluator.RunEvidence run = evidence("FINISHED", "done");

		// "At most 2 steps" must pass on exactly 2, or every limit would be off by one in practice.
		assertThat(passes(AssertionSpec.limit(AssertionKind.MAX_STEPS, 2), run)).isTrue();
		assertThat(passes(AssertionSpec.limit(AssertionKind.MAX_STEPS, 1), run)).isFalse();
		assertThat(passes(AssertionSpec.limit(AssertionKind.MAX_TOOL_CALLS, 1), run)).isTrue();
		assertThat(passes(AssertionSpec.limit(AssertionKind.MAX_TOKENS, 500), run)).isTrue();
		assertThat(passes(AssertionSpec.limit(AssertionKind.MAX_TOKENS, 499), run)).isFalse();
	}

	@Test
	@DisplayName("invalid tool calls can be asserted away")
	void checksNoInvalidToolCalls() {
		assertThat(passes(AssertionSpec.of(AssertionKind.NO_INVALID_TOOL_CALLS, null),
				AssertionEvaluator.RunEvidence.from("FINISHED", "x", 1, 2, 0, 10, List.of()))).isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.NO_INVALID_TOOL_CALLS, null),
				AssertionEvaluator.RunEvidence.from("FINISHED", "x", 1, 2, 1, 10, List.of()))).isFalse();
	}

	@Test
	@DisplayName("a guardrail firing can be asserted against")
	void checksNoEvent() {
		// The most direct way to say "and it did not loop", which a status check alone cannot express
		// because a run can loop, be nudged, recover and still finish.
		AssertionEvaluator.RunEvidence looped = evidence("FINISHED", "done",
				event(1, RunEventType.LOOP_DETECTED));
		AssertionEvaluator.RunEvidence clean = evidence("FINISHED", "done");

		assertThat(passes(AssertionSpec.of(AssertionKind.NO_EVENT, "LOOP_DETECTED"), looped)).isFalse();
		assertThat(passes(AssertionSpec.of(AssertionKind.NO_EVENT, "LOOP_DETECTED"), clean)).isTrue();
	}

	@Test
	@DisplayName("an unknown event type fails the check with an explanation")
	void rejectsUnknownEventType() {
		AssertionVerdict verdict = evaluator.evaluate(
				List.of(AssertionSpec.of(AssertionKind.NO_EVENT, "NOT_A_REAL_EVENT")),
				evidence("FINISHED", "x")).getFirst();

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.detail()).contains("is not an event type");
	}

	@Test
	@DisplayName("a judge assertion is left for the evaluator that has a model")
	void skipsJudgeAssertions() {
		// The pure evaluator must not silently pass or fail a check it cannot perform.
		List<AssertionVerdict> verdicts = evaluator.evaluate(
				List.of(AssertionSpec.of(AssertionKind.JUDGE, "is it polite"),
						AssertionSpec.of(AssertionKind.STATUS, "FINISHED")),
				evidence("FINISHED", "done"));

		assertThat(verdicts).hasSize(1);
		assertThat(verdicts.getFirst().kind()).isEqualTo(AssertionKind.STATUS);
	}

	@Test
	@DisplayName("deterministic verdicts are marked reproducible")
	void marksReproducibility() {
		// A pass rate that moves should be attributable. Deterministic checks cannot be the cause, and
		// saying so on the verdict keeps that visible when a judge is mixed in with them.
		AssertionVerdict verdict = evaluator.evaluate(
				List.of(AssertionSpec.of(AssertionKind.STATUS, "FINISHED")),
				evidence("FINISHED", "done")).getFirst();

		assertThat(verdict.reproducible()).isTrue();
		assertThat(AssertionKind.JUDGE.isDeterministic()).isFalse();
	}

	@Test
	@DisplayName("a missing answer does not blow up the checks")
	void handlesMissingOutput() {
		AssertionEvaluator.RunEvidence noAnswer = evidence("ABORTED_BUDGET", null);

		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_CONTAINS, "anything"), noAnswer))
				.isFalse();
		assertThat(passes(AssertionSpec.of(AssertionKind.OUTPUT_NOT_CONTAINS, "anything"), noAnswer))
				.isTrue();
		assertThat(passes(AssertionSpec.of(AssertionKind.STATUS, "ABORTED_BUDGET"), noAnswer)).isTrue();
	}
}
