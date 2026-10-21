package com.example.ai_controll.orchestrator.approval;

/** How far an approval reaches. */
public enum ApprovalScope {

	/** This invocation only. The next identical call asks again. */
	CALL,

	/** This tool, for the remainder of this run. Reconstructed from the journal on resume. */
	RUN,

	/**
	 * This tool, from now on. Writes an AUTO policy rule, so the decision becomes visible on the
	 * policy page rather than living invisibly inside a run.
	 */
	ALWAYS
}
