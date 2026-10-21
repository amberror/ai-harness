package com.example.ai_controll.tool.policy;

/** What a policy says should happen to a matching tool call. */
public enum ApprovalMode {

	/** Run it without asking. */
	AUTO,

	/** Park the run and wait for a person. */
	REQUIRE_APPROVAL,

	/**
	 * Refuse it.
	 *
	 * <p>The refusal goes back to the model as a tool failure rather than ending the run, so it can
	 * choose another route or explain the limitation -- the same treatment as any other tool that
	 * could not do what was asked.
	 */
	DENY
}
