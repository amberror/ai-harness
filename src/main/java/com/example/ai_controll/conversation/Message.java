package com.example.ai_controll.conversation;

import java.util.List;

/**
 * One entry in a transcript.
 *
 * <p>Reasoning is held separately from content and is never sent back to the model. Feeding a
 * reasoning model its own previous chain of thought wastes context and measurably degrades
 * behaviour, but the harness still needs to record it, because it is often the only evidence of why
 * a small model went wrong.
 *
 * @param role       who produced the message
 * @param content    the visible text
 * @param reasoning  extracted chain of thought; recorded, never replayed to the model
 * @param toolCalls  tool calls requested; assistant messages only
 * @param toolCallId which tool call this message answers; tool messages only
 * @param toolName   the tool that produced this result; tool messages only
 * @param truncated  whether content was shortened to fit the context budget
 */
public record Message(Role role, String content, String reasoning, List<ToolCallRequest> toolCalls,
		String toolCallId, String toolName, boolean truncated) {

	public Message {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public static Message system(String content) {
		return new Message(Role.SYSTEM, content, null, List.of(), null, null, false);
	}

	public static Message user(String content) {
		return new Message(Role.USER, content, null, List.of(), null, null, false);
	}

	public static Message assistant(String content, String reasoning) {
		return new Message(Role.ASSISTANT, content, reasoning, List.of(), null, null, false);
	}

	/** An assistant turn that asked for tools; content is usually empty in that case. */
	public static Message assistantToolCalls(String content, String reasoning,
			List<ToolCallRequest> toolCalls) {
		return new Message(Role.ASSISTANT, content, reasoning, toolCalls, null, null, false);
	}

	/** The outcome of running a tool, addressed back to the call that requested it. */
	public static Message toolResult(String toolCallId, String toolName, String content,
			boolean truncated) {
		return new Message(Role.TOOL, content, null, List.of(), toolCallId, toolName, truncated);
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}

	public boolean hasContent() {
		return content != null && !content.isBlank();
	}

	/** A copy with the content replaced, used by truncation and compaction. */
	public Message withContent(String newContent, boolean nowTruncated) {
		return new Message(role, newContent, reasoning, toolCalls, toolCallId, toolName, nowTruncated);
	}
}
