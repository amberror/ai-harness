package com.example.ai_controll.lmstudio.port;

/**
 * Why generation stopped.
 *
 * <p>Modelled as an enum rather than passed through as a string because the orchestrator makes
 * decisions on it, and an unrecognised value must be handled explicitly rather than silently
 * treated as a normal completion.
 */
public enum FinishReason {

	/** The model finished on its own. */
	STOP,

	/** The model asked to call one or more tools. */
	TOOL_CALLS,

	/** The output allowance ran out mid-generation. */
	LENGTH,

	/** Content was filtered by the server. */
	CONTENT_FILTER,

	/** Something the harness does not recognise; treated as suspicious, never as success. */
	UNKNOWN;

	public static FinishReason fromWire(String value) {
		if (value == null) {
			return UNKNOWN;
		}
		return switch (value.trim().toLowerCase()) {
			case "stop", "end_turn" -> STOP;
			case "tool_calls", "function_call" -> TOOL_CALLS;
			case "length", "max_tokens" -> LENGTH;
			case "content_filter" -> CONTENT_FILTER;
			default -> UNKNOWN;
		};
	}
}
