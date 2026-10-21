package com.example.ai_controll.eval.assertion;

/**
 * What an assertion checks.
 *
 * <p>Every kind but {@link #JUDGE} is exactly reproducible: given the same run, it returns the same
 * verdict forever. That property is what lets a changed pass rate be attributed to the model rather
 * than to the measuring instrument.
 */
public enum AssertionKind {

	/** The run reached a particular terminal state, e.g. FINISHED or ABORTED_LOOP. */
	STATUS,

	/** The final answer contains a string, case-insensitively. */
	OUTPUT_CONTAINS,

	/** The final answer does not contain a string. */
	OUTPUT_NOT_CONTAINS,

	/** The final answer matches a regular expression. */
	OUTPUT_MATCHES,

	/** A named tool was called at least once. */
	TOOL_CALLED,

	/** A named tool was never called. */
	TOOL_NOT_CALLED,

	/** A named tool was called with arguments containing a given JSON fragment. */
	TOOL_CALLED_WITH,

	/** The run made no more than N tool calls in total. */
	MAX_TOOL_CALLS,

	/** The run produced no tool calls that failed validation. */
	NO_INVALID_TOOL_CALLS,

	/** The run used no more than N steps. */
	MAX_STEPS,

	/** The run used no more than N tokens in total, reasoning included. */
	MAX_TOKENS,

	/** No event of a given type appears in the journal, e.g. LOOP_DETECTED. */
	NO_EVENT,

	/**
	 * A model was asked whether the answer satisfies a rubric.
	 *
	 * <p>The only kind that is not reproducible. It can express things substring matching cannot --
	 * "did it correctly explain the trade-off" -- at the cost of putting a second nondeterministic
	 * model inside the instrument. A pass-rate change then has two possible causes, so results are
	 * labelled to keep that visible rather than letting it blend in.
	 */
	JUDGE;

	/** Whether this kind returns the same verdict every time for the same run. */
	public boolean isDeterministic() {
		return this != JUDGE;
	}
}
