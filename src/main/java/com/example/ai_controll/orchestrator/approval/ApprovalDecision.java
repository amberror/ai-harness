package com.example.ai_controll.orchestrator.approval;

/** The answer to an approval request. */
public enum ApprovalDecision {

	APPROVED,

	/**
	 * Refused. Reported to the model as a tool failure rather than ending the run, so it can take
	 * another route or explain the limitation.
	 */
	DENIED
}
