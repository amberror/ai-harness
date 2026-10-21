package com.example.ai_controll.lmstudio.port;

import java.time.Duration;
import java.util.List;

import com.example.ai_controll.conversation.ToolCallRequest;

/**
 * The outcome of one model call, including the exact bytes exchanged.
 *
 * <p>The raw request and response are carried deliberately. "What was the model actually shown, and
 * what did it actually say" is the question the whole harness is built to answer, and a
 * reconstruction after the fact is not the same thing as a recording.
 *
 * @param content         the visible answer; frequently empty when the model called tools instead
 * @param reasoning       chain of thought, from a dedicated field or split out of the content
 * @param toolCalls       tool calls as emitted, unvalidated
 * @param finishReason    why generation stopped
 * @param usage           token accounting
 * @param modelInstanceId which loaded instance answered
 * @param rawRequestJson  the exact body sent
 * @param rawResponseJson the exact body received
 * @param latency         wall-clock time for the call
 */
public record ChatResult(String content, String reasoning, List<ToolCallRequest> toolCalls,
		FinishReason finishReason, TokenUsage usage, String modelInstanceId, String rawRequestJson,
		String rawResponseJson, Duration latency) {

	public ChatResult {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		usage = usage == null ? TokenUsage.EMPTY : usage;
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}

	public boolean hasContent() {
		return content != null && !content.isBlank();
	}

	/**
	 * The output allowance was exhausted before the model produced anything usable.
	 *
	 * <p>This is a real and easily-missed failure mode on a reasoning model: the entire allowance can
	 * be consumed by the chain of thought, leaving empty content and a {@code length} finish reason.
	 * Treated as success it looks like the model simply had nothing to say, so the harness names it
	 * and reports it rather than letting a silent empty answer through.
	 */
	public boolean truncatedBeforeAnswering() {
		return finishReason == FinishReason.LENGTH && !hasContent() && !hasToolCalls();
	}

	/** Nothing usable came back at all, whatever the stated reason. */
	public boolean isEmpty() {
		return !hasContent() && !hasToolCalls();
	}
}
