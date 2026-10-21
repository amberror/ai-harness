package com.example.ai_controll.conversation;

/**
 * A tool call as the model emitted it, before any validation.
 *
 * <p>{@code rawArguments} is kept as the original string rather than parsed JSON on purpose. Small
 * models routinely emit arguments that are not valid JSON at all, and the harness needs the exact
 * text both to show in the journal and to quote back to the model when asking it to try again.
 * Parsing happens later, where failure can be handled as a first-class outcome.
 *
 * @param id           identifier the model assigned, used to correlate the result back
 * @param name         tool name as emitted; may not correspond to any real tool
 * @param rawArguments argument JSON exactly as emitted; may be malformed
 */
public record ToolCallRequest(String id, String name, String rawArguments) {

	public ToolCallRequest {
		rawArguments = rawArguments == null ? "" : rawArguments;
	}
}
