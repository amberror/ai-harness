package com.example.ai_controll.observability;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.JsonNode;

/**
 * Aggregates over runs, alongside the journal's record of each one.
 *
 * <p>The journal answers "what happened in this run" perfectly and "what usually happens" not at
 * all. Questions like whether tool failures rose after a schema edit, or whether the model got
 * slower once the context filled up, need a shape that survives across runs, and reconstructing that
 * by reading thousands of journal rows is the wrong tool for it.
 *
 * <p>Derived from the journal rather than instrumented separately, on purpose. Every meter here is
 * fed by an event that had to be recorded anyway, so the metrics cannot drift out of agreement with
 * the record they summarise -- and a new event type gets a meter by being journalled, not by
 * remembering to add a second call next to it.
 *
 * <p>Every tag has a bounded set of values -- tool names come from the registry, statuses and finish
 * reasons from enums -- so this cannot quietly turn into a cardinality problem.
 */
@Component
public class HarnessMetrics {

	private final MeterRegistry registry;

	public HarnessMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/** Called for every journalled event; anything without a meter is simply ignored. */
	public void record(RunEventView event) {
		switch (event.type()) {
			case MODEL_RESPONSE -> recordModelCall(event);
			case TOOL_EXEC_SUCCEEDED -> recordToolCall(event, "succeeded");
			case TOOL_EXEC_FAILED -> recordToolCall(event, "failed");
			// Exactly one of these per run, whatever happened to it -- including a run that died on a
			// transport error, which also emits RUN_FAILED describing the cause. Counting both would
			// report every failure twice, which is how a run counter ends up disagreeing with the runs
			// table it is meant to summarise.
			case RUN_FINISHED -> this.registry.counter("harness.runs",
					"status", text(event.payload(), "status", "UNKNOWN")).increment();
			// One counter for every way a guardrail can fire, tagged by which. Separate meters would
			// mean a new guardrail is invisible on the dashboard until someone adds a panel for it.
			case LOOP_DETECTED, BUDGET_EXCEEDED, CONTEXT_COMPACTED, TOOL_CALL_INVALID,
					TOOL_CALL_DENIED, TOOL_APPROVAL_REQUIRED, OUTPUT_TRUNCATED, OUTPUT_INVALID,
					MODEL_RETRY ->
				this.registry.counter("harness.guardrail.fired", "guardrail", event.type().name())
						.increment();
			default -> {
				// Not every event is worth a meter; the journal has them all either way.
			}
		}
	}

	private void recordModelCall(RunEventView event) {
		if (event.durationMs() != null) {
			this.registry.timer("harness.model.latency",
					"finish_reason", text(event.payload(), "finishReason", "unknown"))
					.record(event.durationMs(), TimeUnit.MILLISECONDS);
		}
		JsonNode usage = event.payload() == null ? null : event.payload().get("usage");
		if (usage != null) {
			// Reasoning is summarised separately because on a small reasoning model it is not a
			// rounding error on output -- it is routinely most of what was spent.
			this.registry.summary("harness.model.tokens", "kind", "prompt")
					.record(number(usage, "promptTokens"));
			this.registry.summary("harness.model.tokens", "kind", "completion")
					.record(number(usage, "completionTokens"));
			this.registry.summary("harness.model.tokens", "kind", "reasoning")
					.record(number(usage, "reasoningTokens"));
		}
	}

	private void recordToolCall(RunEventView event, String outcome) {
		String tool = text(event.payload(), "tool", "unknown");
		if (event.durationMs() != null) {
			this.registry.timer("harness.tool.latency", "tool", tool, "outcome", outcome)
					.record(event.durationMs(), TimeUnit.MILLISECONDS);
		}
		this.registry.counter("harness.tool.calls", "tool", tool, "outcome", outcome).increment();
	}

	private static String text(JsonNode payload, String field, String fallback) {
		if (payload == null) {
			return fallback;
		}
		JsonNode value = payload.get(field);
		if (value == null || value.isNull()) {
			return fallback;
		}
		return value.isString() ? value.stringValue() : value.toString();
	}

	private static double number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || !value.isNumber()) ? 0d : value.asDouble();
	}
}
