package com.example.ai_controll.lmstudio.port;

/**
 * Token accounting for one model call.
 *
 * <p>Reasoning tokens are tracked separately because on a reasoning model they are not a rounding
 * error: a request can spend its entire output allowance thinking and return no answer at all. The
 * budget tracker needs to see that distinctly from ordinary output.
 */
public record TokenUsage(int promptTokens, int completionTokens, int reasoningTokens,
		int totalTokens) {

	public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

	public TokenUsage plus(TokenUsage other) {
		if (other == null) {
			return this;
		}
		return new TokenUsage(promptTokens + other.promptTokens,
				completionTokens + other.completionTokens,
				reasoningTokens + other.reasoningTokens,
				totalTokens + other.totalTokens);
	}

	/** Share of the output allowance that went to reasoning rather than to an answer. */
	public double reasoningShare() {
		return completionTokens == 0 ? 0d : (double) reasoningTokens / (double) completionTokens;
	}
}
