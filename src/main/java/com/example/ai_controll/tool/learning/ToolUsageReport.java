package com.example.ai_controll.tool.learning;

import java.util.List;

/**
 * What actually happens when the model tries to use one tool.
 *
 * <p>A batch pass rate says a scenario failed; this says <em>why the tool was hard</em>. The
 * difference is the difference between "the model is bad at unit_convert" and "it omits {@code to}
 * in six calls out of ten", and only the second one tells you what to change.
 *
 * <p>Everything here was already in the journal. Every rejected call has been recorded since phase 2,
 * with the failing field path and the exact correction sent back — it was simply never read across
 * runs, because the journal is organised per run and this is the one question that is not.
 *
 * @param calls           tool calls the model produced, valid or not
 * @param invalid         how many were rejected before reaching the executor
 * @param executions      how many actually ran
 * @param failedExecutions how many of those reported failure
 * @param reasons         why calls were rejected, commonest first
 * @param fields          which argument paths failed, commonest first — the most actionable line here
 * @param rejectedSamples argument text the model produced that was refused, verbatim
 * @param successfulCalls distinct argument objects that worked, newest first
 */
public record ToolUsageReport(String tool, int calls, int invalid, int executions,
		int failedExecutions, List<Count> reasons, List<Count> fields, List<String> rejectedSamples,
		List<String> successfulCalls) {

	public ToolUsageReport {
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
		fields = fields == null ? List.of() : List.copyOf(fields);
		rejectedSamples = rejectedSamples == null ? List.of() : List.copyOf(rejectedSamples);
		successfulCalls = successfulCalls == null ? List.of() : List.copyOf(successfulCalls);
	}

	public static ToolUsageReport empty(String tool) {
		return new ToolUsageReport(tool, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
	}

	/** Share of calls the model got wrong. The single number worth sorting a tool list by. */
	public double invalidRate() {
		return calls == 0 ? 0d : (double) invalid / (double) calls;
	}

	/** Share of executions that failed once the arguments were accepted. */
	public double executionFailureRate() {
		return executions == 0 ? 0d : (double) failedExecutions / (double) executions;
	}

	/** Whether there is enough here to draw any conclusion from. */
	public boolean hasEvidence() {
		return calls > 0;
	}

	/**
	 * Whether the evidence is worth acting on yet.
	 *
	 * <p>Three calls and one rejection is 33%, and it is also nothing. A report that suggested changes
	 * off that would be the same error the confidence intervals exist to prevent one page over.
	 */
	public boolean isSubstantial() {
		return calls >= 5;
	}

	/** One tally, e.g. a failing field path and how often it failed. */
	public record Count(String value, int count) {
	}
}
