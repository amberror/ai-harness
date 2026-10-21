package com.example.ai_controll.lmstudio.port;

import java.util.List;

import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.tool.schema.ToolSchema;

/**
 * A single model call, in harness terms rather than in any particular vendor's wire format.
 *
 * <p>Keeping this separate from the OpenAI-compatible DTOs is what lets the transport be swapped
 * later without the orchestrator noticing.
 *
 * @param modelKey        which model to call
 * @param messages        the full conversation; the harness always sends complete history, because
 *                        it owns the history rather than the server
 * @param tools           tools to expose on this call; empty means none
 * @param sampling        decoding parameters
 * @param maxOutputTokens output allowance. On a reasoning model this covers reasoning too, so a
 *                        value tuned for a non-reasoning model can starve the actual answer
 * @param stop            stop sequences
 * @param responseFormat  a schema the server should constrain decoding to, or null to let the model
 *                        write whatever it likes and have the harness check it afterwards
 */
public record ChatRequest(String modelKey, List<Message> messages, List<ToolSchema> tools,
		SamplingParams sampling, Integer maxOutputTokens, List<String> stop,
		ResponseFormat responseFormat) {

	public ChatRequest {
		messages = messages == null ? List.of() : List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
		stop = stop == null ? List.of() : List.copyOf(stop);
		sampling = sampling == null ? SamplingParams.NONE : sampling;
	}

	public static ChatRequest of(String modelKey, List<Message> messages) {
		return new ChatRequest(modelKey, messages, List.of(), SamplingParams.NONE, null, List.of(),
				null);
	}

	public boolean hasTools() {
		return !tools.isEmpty();
	}
}
