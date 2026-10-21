package com.example.ai_controll.lmstudio.port;

import java.util.function.Consumer;

/**
 * The inference boundary.
 *
 * <p>The harness drives the OpenAI-compatible endpoint rather than LM Studio's native
 * {@code /api/v1/chat}, because the native endpoint accepts neither custom tools nor assistant
 * messages in the request. Delegating to it would hand the agentic loop to LM Studio and leave the
 * harness able only to watch -- unable to intercept a tool call, repair bad arguments, require
 * approval, compact context or fork a conversation.
 */
public interface ChatModelClient {

	/** Runs one model call to completion. */
	ChatResult complete(ChatRequest request);

	/**
	 * Runs one model call, delivering events as they arrive. The sink is always given a terminal
	 * {@link ChatStreamEvent.Completed} or {@link ChatStreamEvent.Failed} event.
	 */
	void stream(ChatRequest request, Consumer<ChatStreamEvent> sink);
}
