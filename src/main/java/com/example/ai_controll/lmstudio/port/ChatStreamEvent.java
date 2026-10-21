package com.example.ai_controll.lmstudio.port;

/**
 * An incremental event from a streaming model call.
 *
 * <p>Reasoning and content deltas are separate event types rather than one text stream, because a
 * console that cannot distinguish "the model is thinking" from "the model is answering" is of very
 * little use when the whole point is watching how the model behaves.
 */
public sealed interface ChatStreamEvent {

	/** Generation has begun. */
	record Started(String modelInstanceId) implements ChatStreamEvent {
	}

	/** A fragment of chain of thought. */
	record ReasoningDelta(String text) implements ChatStreamEvent {
	}

	/** A fragment of the visible answer. */
	record ContentDelta(String text) implements ChatStreamEvent {
	}

	/** A tool call has been fully assembled from its fragments. */
	record ToolCallAssembled(com.example.ai_controll.conversation.ToolCallRequest toolCall)
			implements ChatStreamEvent {
	}

	/** Generation finished; carries the same aggregate a non-streaming call would have returned. */
	record Completed(ChatResult result) implements ChatStreamEvent {
	}

	/** The stream failed partway through. */
	record Failed(String message, Throwable cause) implements ChatStreamEvent {
	}
}
