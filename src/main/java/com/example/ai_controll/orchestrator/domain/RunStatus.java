package com.example.ai_controll.orchestrator.domain;

/**
 * Terminal and in-flight states of a run.
 *
 * <p>The aborted states are deliberately distinct rather than a single FAILED. A harness whose whole
 * purpose is making model behaviour predictable has to be able to say <em>which</em> guardrail
 * stopped a run: "it looped", "it exhausted its budget" and "it never produced a repairable tool
 * call" call for completely different fixes.
 */
public enum RunStatus {

	RUNNING,

	/** The model produced a final answer. */
	FINISHED,

	/** Parked on a tool call that needs human approval; resumable. */
	AWAITING_APPROVAL,

	/** The step limit was reached without a final answer. */
	ABORTED_MAX_STEPS,

	/** The model kept emitting tool calls that could not be validated or repaired. */
	ABORTED_UNREPAIRABLE,

	/** The same tool call repeated beyond the loop threshold. */
	ABORTED_LOOP,

	/** A token or wall-clock budget was exhausted. */
	ABORTED_BUDGET,

	/**
	 * The conversation no longer fits the model's context window, even after compaction.
	 *
	 * <p>Distinct from a budget abort because the fix is different and specific: load the model with
	 * a larger context_length, shorten tool output, or offer fewer tools. Sending the request anyway
	 * would let the server truncate it from the front, silently removing the system prompt and the
	 * task -- a failure that then looks like the model losing the plot.
	 */
	ABORTED_CONTEXT,

	/** Stopped by a human. */
	CANCELLED,

	/** Something went wrong that is not a guardrail: a transport error, a bug. */
	FAILED;

	public boolean isTerminal() {
		return this != RUNNING && this != AWAITING_APPROVAL;
	}
}
