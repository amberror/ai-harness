package com.example.ai_controll.tool.parse;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;

/**
 * Decides what the model actually asked for on a given step.
 *
 * <p>The native {@code tool_calls} field is always preferred and is what this model normally uses.
 * The text-recovering parsers run only when it is empty, in order, first match winning.
 *
 * <p>Which channel a call arrived through is recorded, because it is a genuine measurement rather
 * than an implementation detail: a run of experiments where the model increasingly abandons the
 * native channel is telling you something about context pressure or sampling that no aggregate
 * success rate would reveal.
 */
@Component
public class ToolCallExtractor {

	private static final Logger log = LoggerFactory.getLogger(ToolCallExtractor.class);

	/** Ordered by {@code @Order} on each parser; Spring injects them already sorted. */
	private final List<ToolCallParser> fallbackParsers;

	public ToolCallExtractor(List<ToolCallParser> fallbackParsers) {
		this.fallbackParsers = List.copyOf(fallbackParsers);
	}

	/** Extracts the calls for one model response. */
	public Extraction extract(ChatResult result) {
		if (result.hasToolCalls()) {
			return new Extraction(result.toolCalls(), "native", result.content(), false);
		}

		String content = result.content();
		if (content == null || content.isBlank()) {
			return new Extraction(List.of(), "native", content, false);
		}

		for (ToolCallParser parser : fallbackParsers) {
			ToolCallParser.Extraction found = parser.parse(content);
			if (found.foundAnything()) {
				log.info("Recovered {} tool call(s) from message text via the '{}' parser; "
						+ "the model did not use the native tool_calls channel",
						found.toolCalls().size(), parser.name());
				return new Extraction(found.toolCalls(), parser.name(), found.remainingContent(), true);
			}
		}

		return new Extraction(List.of(), "native", content, false);
	}

	/**
	 * @param toolCalls        what the model asked to run, still unvalidated
	 * @param channel          how the calls were obtained: {@code native}, or the parser that
	 *                         recovered them
	 * @param remainingContent the message text once any recovered calls were removed
	 * @param recovered        whether a fallback was needed, which is worth journalling on its own
	 */
	public record Extraction(List<ToolCallRequest> toolCalls, String channel, String remainingContent,
			boolean recovered) {

		public Extraction {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}

		public boolean hasToolCalls() {
			return !toolCalls.isEmpty();
		}
	}
}
