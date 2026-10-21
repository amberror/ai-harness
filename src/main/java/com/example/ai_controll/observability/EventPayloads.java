package com.example.ai_controll.observability;

import java.util.List;

import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.TokenUsage;
import com.example.ai_controll.orchestrator.domain.RunStatus;

/**
 * Payload shapes for journal entries.
 *
 * <p>Typed records rather than loose maps, so that the console and evaluation assertions read a
 * stable contract instead of guessing at key names.
 *
 * <p>Raw bodies are stored as the original strings, not as re-parsed JSON. Re-serialising would
 * produce something equivalent but not identical -- different key order, different whitespace,
 * numbers normalised -- and the point of recording them is to have the bytes that actually crossed
 * the wire rather than a faithful-looking reconstruction.
 */
public final class EventPayloads {

	private EventPayloads() {
	}

	public record RunStarted(String modelKey, String systemPrompt, String input, Double temperature,
			Integer maxOutputTokens, boolean streaming) {
	}

	/**
	 * @param body            the exact request body sent
	 * @param messageCount    how many messages the model was shown
	 * @param toolCount       how many tool schemas were exposed
	 * @param estimatedTokens what the harness thought the prompt would cost. Recorded next to the
	 *                        reported figure in the response so the estimator can be checked rather
	 *                        than trusted
	 * @param queuedMs        time spent waiting for the model to be free. Kept apart from latency:
	 *                        queueing is the harness's doing, generation is the model's
	 */
	public record ModelRequest(String body, int messageCount, int toolCount, int estimatedTokens,
			long queuedMs) {
	}

	/**
	 * A model call was retried after failing.
	 *
	 * @param attempt which attempt failed, counting from one
	 * @param reason  what went wrong, verbatim
	 */
	public record ModelRetry(int attempt, int maxAttempts, String reason, long backoffMs) {
	}

	/**
	 * History was reduced to fit the context window.
	 *
	 * <p>Carries what was removed rather than just the fact of it, because a run that behaved oddly
	 * after a compaction should be explainable by what it stopped being able to see.
	 *
	 * @param fits false when even the protected head does not fit, which ends the run
	 */
	public record ContextCompacted(int contextLength, int budgetTokens, int estimatedBefore,
			int estimatedAfter, int droppedMessages, int shortenedResults, boolean fits) {
	}

	/**
	 * The final answer did not satisfy the schema the run required, and the correction sent back.
	 */
	public record OutputInvalid(String output, String reason,
			tools.jackson.databind.JsonNode feedback) {
	}

	/**
	 * @param body           the exact response body received
	 * @param contentChars   size of the answer; zero alongside a non-zero reasoning count is the
	 *                       signature of a model that thought without answering
	 * @param reasoningChars size of the chain of thought
	 */
	public record ModelResponse(String body, FinishReason finishReason, TokenUsage usage,
			long latencyMs, int contentChars, int reasoningChars, int toolCallCount,
			List<String> toolNames) {
	}

	/**
	 * The output allowance ran out before the model produced anything usable.
	 *
	 * <p>Recorded as its own entry rather than left implicit in the response, because it is easy to
	 * mistake for the model simply having nothing to say, and the remedy is specific.
	 */
	public record OutputTruncated(int completionTokens, int reasoningTokens, double reasoningShare,
			String hint) {
	}

	public record RunFinished(RunStatus status, String finalOutput, int stepsUsed, TokenUsage usage,
			long durationMs) {
	}

	public record RunFailed(String message, String exceptionType, Integer upstreamStatus) {
	}

	/** A fragment of streamed output. Live view only; never stored. */
	public record Delta(String text) {
	}

	// -------------------------------------------------------------------------------------------
	// Tool events
	// -------------------------------------------------------------------------------------------

	/**
	 * A tool call the harness accepted.
	 *
	 * @param channel   how it arrived: {@code native}, or the parser that recovered it from text
	 * @param recovered whether a fallback parser was needed, which is a measurement in its own right
	 */
	public record ToolCallParsed(String tool, tools.jackson.databind.JsonNode arguments, String channel,
			boolean recovered) {
	}

	/**
	 * A tool call the harness rejected, together with the correction sent back.
	 *
	 * @param rawArguments the argument text exactly as the model produced it
	 * @param reason       {@code UNKNOWN_TOOL}, {@code MALFORMED_JSON} or {@code SCHEMA_VIOLATION}
	 * @param feedback     the machine-readable correction the model receives
	 */
	public record ToolCallInvalid(String tool, String rawArguments, String reason,
			tools.jackson.databind.JsonNode feedback, String channel) {
	}

	public record ToolExecStarted(String tool, String executorType, int toolVersion,
			tools.jackson.databind.JsonNode arguments) {
	}

	/**
	 * @param errorKind short category when the tool failed, e.g. {@code timeout}, {@code egress_denied}
	 * @param output    what went back to the model, after any truncation
	 */
	public record ToolExecFinished(String tool, boolean succeeded, String errorKind, String output,
			boolean truncated) {
	}

	// -------------------------------------------------------------------------------------------
	// Guardrails
	// -------------------------------------------------------------------------------------------

	/**
	 * The same call was made repeatedly with identical arguments.
	 *
	 * @param signature tool name and canonical arguments, so two spellings of the same call match
	 */
	public record LoopDetected(String signature, int count, int threshold) {
	}

	public record BudgetExceeded(String reason, TokenUsage usage, long elapsedMs) {
	}

	// -------------------------------------------------------------------------------------------
	// Policy and approvals
	// -------------------------------------------------------------------------------------------

	/**
	 * A call a policy forbids.
	 *
	 * @param reason phrased for a person reading the journal later, who needs to know which rule
	 */
	public record ToolCallDenied(String tool, String policyName, String reason) {
	}

	/** A call the run stopped on, waiting for a person. */
	public record ApprovalRequired(String tool, tools.jackson.databind.JsonNode arguments,
			String policyName, String reason) {
	}

	/**
	 * A decision a person made.
	 *
	 * @param scope CALL, RUN or ALWAYS -- recorded because "why did that run unattended" needs an
	 *              answer, and a run-scoped grant is invisible otherwise
	 */
	public record ApprovalDecided(String tool, String decision, String scope, String note) {
	}

	/** A parked run picked back up. */
	public record RunResumed(String decision, String tool, String scope) {
	}
}
