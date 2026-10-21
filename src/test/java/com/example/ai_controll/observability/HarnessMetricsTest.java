package com.example.ai_controll.observability;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.orchestrator.domain.RunEventType;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregates derived from the journal.
 *
 * <p>The counting rule is the part worth guarding, and it is the part that was wrong first: a failed
 * run emits both {@code RUN_FAILED}, describing the cause, and {@code RUN_FINISHED}, marking the
 * end. Counting both reported every failure twice, so the run counter disagreed with the runs table
 * it was meant to summarise -- in the direction that makes things look worse than they are, which is
 * no better than the direction that flatters.
 */
class HarnessMetricsTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final HarnessMetrics metrics = new HarnessMetrics(registry);

	private RunEventView event(RunEventType type, String payloadJson, Long durationMs) {
		JsonNode payload = payloadJson == null ? null : mapper.readTree(payloadJson);
		return new RunEventView(UUID.randomUUID(), 1L, 1, type, Instant.now(), durationMs, payload);
	}

	@Test
	@DisplayName("a run is counted once, on the event every run emits exactly once")
	void countsEachRunOnce() {
		// What a failed run actually produces: the cause, then the end.
		metrics.record(event(RunEventType.RUN_FAILED, "{\"message\":\"unreachable\"}", null));
		metrics.record(event(RunEventType.RUN_FINISHED, "{\"status\":\"FAILED\"}", null));

		assertThat(registry.counter("harness.runs", "status", "FAILED").count()).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("runs are counted separately by how they ended")
	void tagsByTerminalStatus() {
		metrics.record(event(RunEventType.RUN_FINISHED, "{\"status\":\"FINISHED\"}", null));
		metrics.record(event(RunEventType.RUN_FINISHED, "{\"status\":\"ABORTED_LOOP\"}", null));
		metrics.record(event(RunEventType.RUN_FINISHED, "{\"status\":\"ABORTED_LOOP\"}", null));

		// "Which guardrail is stopping runs this week" is the question this exists to answer, and a
		// single total cannot answer it.
		assertThat(registry.counter("harness.runs", "status", "FINISHED").count()).isEqualTo(1.0d);
		assertThat(registry.counter("harness.runs", "status", "ABORTED_LOOP").count()).isEqualTo(2.0d);
	}

	@Test
	@DisplayName("every guardrail firing lands under one counter, tagged by which")
	void countsGuardrails() {
		metrics.record(event(RunEventType.LOOP_DETECTED, "{}", null));
		metrics.record(event(RunEventType.CONTEXT_COMPACTED, "{}", null));
		metrics.record(event(RunEventType.MODEL_RETRY, "{}", null));

		// One meter rather than one per guardrail, so a newly added guardrail is visible without
		// anyone remembering to add a panel for it.
		assertThat(registry.counter("harness.guardrail.fired", "guardrail", "LOOP_DETECTED").count())
				.isEqualTo(1.0d);
		assertThat(registry.counter("harness.guardrail.fired", "guardrail", "MODEL_RETRY").count())
				.isEqualTo(1.0d);
	}

	@Test
	@DisplayName("model latency and token usage are recorded from the response event")
	void recordsModelCalls() {
		metrics.record(event(RunEventType.MODEL_RESPONSE, """
				{"finishReason":"STOP",
				 "usage":{"promptTokens":120,"completionTokens":800,"reasoningTokens":780,
				          "totalTokens":920}}""", 4300L));

		assertThat(registry.timer("harness.model.latency", "finish_reason", "STOP").count())
				.isEqualTo(1L);
		// Reasoning is summarised on its own because on this model it is most of what was spent, not
		// a rounding error on the answer.
		assertThat(registry.summary("harness.model.tokens", "kind", "reasoning").totalAmount())
				.isEqualTo(780d);
	}

	@Test
	@DisplayName("tool calls are counted per tool and per outcome")
	void recordsToolCalls() {
		metrics.record(event(RunEventType.TOOL_EXEC_SUCCEEDED, "{\"tool\":\"calc_eval\"}", 12L));
		metrics.record(event(RunEventType.TOOL_EXEC_FAILED, "{\"tool\":\"http_fetch\"}", 900L));

		assertThat(registry.counter("harness.tool.calls", "tool", "calc_eval", "outcome", "succeeded")
				.count()).isEqualTo(1.0d);
		assertThat(registry.counter("harness.tool.calls", "tool", "http_fetch", "outcome", "failed")
				.count()).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("an event with no payload does not blow up the recording")
	void survivesMissingPayloads() {
		metrics.record(event(RunEventType.MODEL_RESPONSE, null, null));
		metrics.record(event(RunEventType.RUN_FINISHED, null, null));
		metrics.record(event(RunEventType.TOOL_EXEC_SUCCEEDED, null, null));

		// Measuring the work must never be able to stop the work.
		assertThat(registry.counter("harness.runs", "status", "UNKNOWN").count()).isEqualTo(1.0d);
	}
}
