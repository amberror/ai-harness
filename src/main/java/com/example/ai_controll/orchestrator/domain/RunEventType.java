package com.example.ai_controll.orchestrator.domain;

/**
 * Everything the harness records about a run.
 *
 * <p>Durable events are persisted to the journal and are what replay and evaluation assertions read.
 * Ephemeral events exist only on the live stream: token-level deltas are far too voluminous to store
 * for every run, and the aggregate {@link #MODEL_RESPONSE} already captures the same information.
 */
public enum RunEventType {

	RUN_STARTED(true),

	/** The exact request body sent to the model. */
	MODEL_REQUEST(true),

	/** The exact response body received, plus usage and timings. */
	MODEL_RESPONSE(true),

	/** A fragment of chain of thought. Live view only. */
	REASONING_DELTA(false),

	/** A fragment of the answer. Live view only. */
	CONTENT_DELTA(false),

	/** A tool call the harness successfully extracted from the response. */
	TOOL_CALL_PARSED(true),

	/** A tool call that failed name or schema validation, with the feedback sent back. */
	TOOL_CALL_INVALID(true),

	/** A call matched a policy requiring a person; the run parks here. */
	TOOL_APPROVAL_REQUIRED(true),

	APPROVAL_GRANTED(true),

	APPROVAL_DENIED(true),

	/** A call a policy forbids outright. Reported to the model, not fatal to the run. */
	TOOL_CALL_DENIED(true),

	/** A parked run picked back up after a decision. */
	RUN_RESUMED(true),

	TOOL_EXEC_STARTED(true),

	TOOL_EXEC_SUCCEEDED(true),

	TOOL_EXEC_FAILED(true),

	/** History was compacted to fit the context budget. */
	CONTEXT_COMPACTED(true),

	/**
	 * A model call failed on transport or a server error and was tried again. Durable on purpose: a
	 * retry that is not recorded hides exactly the instability worth knowing about.
	 */
	MODEL_RETRY(true),

	/** The model's final answer did not match the schema the run required of it. */
	OUTPUT_INVALID(true),

	/** The same call repeated past the threshold. */
	LOOP_DETECTED(true),

	BUDGET_EXCEEDED(true),

	/** Generation stopped on the token limit before producing anything usable. */
	OUTPUT_TRUNCATED(true),

	RUN_FINISHED(true),

	RUN_FAILED(true);

	private final boolean durable;

	RunEventType(boolean durable) {
		this.durable = durable;
	}

	/** Whether this event is written to the journal, as opposed to only being streamed live. */
	public boolean isDurable() {
		return this.durable;
	}
}
