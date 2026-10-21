package com.example.ai_controll.tool.exec;

/**
 * What running a tool produced.
 *
 * <p>A failure is an outcome, not an exception. The model is told what went wrong and given the
 * chance to react -- try different arguments, use another tool, or explain the problem in its
 * answer. A tool that throws its way out of the loop would deny it that chance and turn a
 * recoverable situation into a dead run.
 *
 * @param output     text handed back to the model; already the final content of the tool message
 * @param succeeded  whether the tool did what was asked
 * @param errorKind  short machine-readable category when it did not, e.g. {@code timeout}
 * @param durationMs how long it took, for the journal
 * @param truncated  whether the output was shortened to fit the context budget
 */
public record ToolOutcome(String output, boolean succeeded, String errorKind, long durationMs,
		boolean truncated) {

	public static ToolOutcome success(String output, long durationMs) {
		return new ToolOutcome(output, true, null, durationMs, false);
	}

	public static ToolOutcome failure(String errorKind, String message, long durationMs) {
		// Shaped as JSON so the model reads a tool failure the same way it reads a validation
		// rejection, rather than having to interpret a sentence.
		String payload = "{\"error\":\"%s\",\"message\":\"%s\"}".formatted(errorKind,
				message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\""));
		return new ToolOutcome(payload, false, errorKind, durationMs, false);
	}

	/** A copy marked as shortened, used when the output will not fit the budget. */
	public ToolOutcome truncatedTo(String shorter) {
		return new ToolOutcome(shorter, succeeded, errorKind, durationMs, true);
	}
}
