package com.example.ai_controll.tool.parse;

import java.util.List;

import com.example.ai_controll.conversation.ToolCallRequest;

/**
 * Recovers tool calls a model wrote into its message text instead of emitting properly.
 *
 * <p>The native {@code tool_calls} field is the correct channel and the one this model normally
 * uses. These parsers exist for when it does not: under context pressure, at higher temperature, or
 * when a chat template leaks its own markup, a model will describe the call in prose instead. Left
 * unrecovered that reads as a final answer, so the run ends "successfully" having done nothing.
 *
 * <p>Implementations are tried in order and the first that finds anything wins. Each reports a name
 * so the journal records which fallback was needed -- how often the model leaves the native channel
 * is itself a measurement worth having.
 */
public interface ToolCallParser {

	/** Identifier recorded in the journal when this strategy recovers a call. */
	String name();

	/**
	 * Extracts tool calls from message text.
	 *
	 * @return the calls found, and the text left over once they are removed
	 */
	Extraction parse(String content);

	/**
	 * @param toolCalls        calls recovered from the text
	 * @param remainingContent the text with the recovered calls removed; what is genuinely a message
	 */
	record Extraction(List<ToolCallRequest> toolCalls, String remainingContent) {

		public Extraction {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}

		public static Extraction none(String content) {
			return new Extraction(List.of(), content);
		}

		public boolean foundAnything() {
			return !toolCalls.isEmpty();
		}
	}
}
