package com.example.ai_controll.conversation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.schema.ToolSchema;

/**
 * Estimates how many tokens a request will occupy, and corrects itself from what the server reports.
 *
 * <p>A real tokenizer is deliberately not used. Loading one would mean shipping the vocabulary of
 * whichever model happens to be loaded and keeping the two in step, and the harness exists precisely
 * to make the model swappable. What it needs is not an exact count but a bound good enough to decide
 * when to compact -- and the exact count arrives anyway, in {@code usage.prompt_tokens}, on every
 * response.
 *
 * <p><strong>The error is not symmetric.</strong> Overestimating compacts a little earlier than
 * necessary, which costs some old tool output. Underestimating sends a request that overflows the
 * window, and the server then truncates it from the front -- taking the system prompt and the task,
 * which is the exact failure the compaction exists to prevent. So the estimate is biased high until
 * it has been measured, and the correction is allowed to grow far more than it may shrink.
 *
 * <p>Measured against the loaded model rather than reasoned about: a first request carrying one tool
 * was estimated at 199 tokens and charged 401. Most of that gap is not tokenizer density at all, it
 * is the chat template's fixed scaffolding around a tool definition, which no characters-per-token
 * ratio can predict. That is why the correction here is a single multiplier over the whole estimate
 * rather than a fit on the text alone -- it absorbs template overhead, tool-block boilerplate and
 * tokenizer density together, without needing to attribute the gap to any of them.
 */
@Component
public class TokenEstimator {

	/**
	 * The shape of the estimate, not a fitted value. English prose on a modern BPE vocabulary runs
	 * near four characters per token; JSON and tool schemas tokenize a little denser.
	 */
	static final double CHARS_PER_TOKEN = 3.6d;

	/** Role markers and turn delimiters the chat template adds around every message. */
	static final int PER_MESSAGE_OVERHEAD_TOKENS = 4;

	/** The wrapper a tool definition sits in, on top of its own text. */
	static final int PER_TOOL_OVERHEAD_TOKENS = 8;

	/**
	 * How much the estimate is inflated before any response has been seen.
	 *
	 * <p>The one number here that is a guess about a model nobody has measured yet. It is set from
	 * the one that has been: the loaded model's first tool-carrying request came out at almost
	 * exactly twice the structural estimate. A different model with a different chat template will
	 * differ, which is why this only governs the first request -- from the second onwards the figure
	 * is measured rather than assumed.
	 */
	static final double UNCALIBRATED_SAFETY = 2.0d;

	/** New observations move the correction without letting one odd request dominate it. */
	private static final double SMOOTHING = 0.4d;

	/**
	 * The correction may grow a long way -- a template with a large tool preamble genuinely costs
	 * several times the text -- but may shrink only so far, because shrinking is the direction that
	 * loses the front of the conversation.
	 */
	private static final double MIN_CORRECTION = 0.8d;

	private static final double MAX_CORRECTION = 6.0d;

	private volatile double correction = UNCALIBRATED_SAFETY;

	private volatile boolean calibrated;

	/** The characters a request body would carry, ignoring template scaffolding. */
	public int charsOf(List<Message> messages, List<ToolSchema> tools) {
		int chars = 0;
		for (Message message : messages) {
			chars += length(message.content());
			chars += length(message.toolName());
			chars += length(message.toolCallId());
			for (ToolCallRequest call : message.toolCalls()) {
				chars += length(call.name()) + length(call.rawArguments());
			}
			// Reasoning is never sent back to the model, so it must not be counted against the window.
		}
		for (ToolSchema tool : tools) {
			chars += length(tool.name()) + length(tool.description());
			chars += tool.parametersSchema() == null ? 0 : tool.parametersSchema().toString().length();
		}
		return chars;
	}

	/** The token estimate for a request of this size and shape, corrected by what has been observed. */
	public int tokens(int chars, int messageCount, int toolCount) {
		return (int) Math.ceil(rawTokens(chars, messageCount, toolCount) * this.correction);
	}

	public int estimate(List<Message> messages, List<ToolSchema> tools) {
		return tokens(charsOf(messages, tools), messages.size(), tools.size());
	}

	/** A rough count for a single piece of text, used when trimming one message. */
	public int estimateText(String text) {
		return (int) Math.ceil((length(text) / CHARS_PER_TOKEN) * this.correction);
	}

	/**
	 * Corrects the estimate against what the server charged for a request the harness had sized.
	 *
	 * <p>The first observation replaces the cautious starting guess outright rather than blending
	 * with it; blending would leave the guess influencing the answer long after there was real
	 * evidence to replace it.
	 */
	public void calibrate(int chars, int messageCount, int toolCount, int reportedPromptTokens) {
		if (chars <= 0 || reportedPromptTokens <= 0) {
			return;
		}
		int raw = rawTokens(chars, messageCount, toolCount);
		if (raw <= 0) {
			return;
		}
		double observed = (double) reportedPromptTokens / (double) raw;
		double updated = this.calibrated
				? (this.correction * (1 - SMOOTHING)) + (observed * SMOOTHING)
				: observed;
		this.correction = Math.clamp(updated, MIN_CORRECTION, MAX_CORRECTION);
		this.calibrated = true;
	}

	/** The multiplier currently applied, exposed so the console can show what has been learned. */
	public double correction() {
		return this.correction;
	}

	/** Whether any response has been seen yet, or the estimate is still the cautious guess. */
	public boolean isCalibrated() {
		return this.calibrated;
	}

	/** The structural estimate, before correction. Calibration fits against this. */
	private static int rawTokens(int chars, int messageCount, int toolCount) {
		return (int) Math.ceil(chars / CHARS_PER_TOKEN) + (messageCount * PER_MESSAGE_OVERHEAD_TOKENS)
				+ (toolCount * PER_TOOL_OVERHEAD_TOKENS);
	}

	private static int length(String text) {
		return text == null ? 0 : text.length();
	}
}
