package com.example.ai_controll.tool.exec.builtin;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Counts characters, words, lines and sentences in a piece of text.
 *
 * <p>Its usefulness here is that a language model is famously bad at it. Counting is a tokenizer's
 * blind spot, so "how many words are in this paragraph" is a question a 4B model will answer
 * confidently and wrongly, and an experiment can measure whether offering a tool changes that. The
 * answer is exactly checkable, which makes it a good scenario subject rather than a good demo.
 */
@Component
public class TextStatsTool implements BuiltinTool {

	private final ObjectMapper objectMapper;

	public TextStatsTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "text_stats";
	}

	@Override
	public String defaultDescription() {
		return "Counts characters, words, lines and sentences in a piece of text. "
				+ "Use this instead of counting by hand.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{"text":{"type":"string","description":"The text to measure"}},
				 "required":["text"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> text = Args.text(arguments, "text");
		if (text.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments", Args.missing("text", "a string"), 0L);
		}
		String value = text.get();

		ObjectNode result = objectMapper.createObjectNode();
		result.put("characters", value.length());
		result.put("charactersNoSpaces", value.replaceAll("\\s", "").length());
		result.put("words", value.isBlank() ? 0 : value.strip().split("\\s+").length);
		// An empty string is zero lines, not one: the split would otherwise report a line that is not
		// there, and a count that is wrong on the empty case is wrong where it is easiest to check.
		result.put("lines", value.isEmpty() ? 0 : value.split("\r\n|\r|\n", -1).length);
		result.put("sentences", countSentences(value));
		return ToolOutcome.success(result.toString(), 0L);
	}

	/**
	 * Sentence-enders, collapsing runs of them.
	 *
	 * <p>Deliberately naive and documented as such: "Dr. Smith arrived." counts as two. A rule that
	 * is simple and stated beats one that is clever and surprising, because a scenario asserting on
	 * this needs to know what the right answer is.
	 */
	private static int countSentences(String text) {
		int count = 0;
		boolean inRun = false;
		for (char character : text.toCharArray()) {
			boolean terminator = character == '.' || character == '!' || character == '?';
			if (terminator && !inRun) {
				count++;
			}
			inRun = terminator;
		}
		// Trailing text with no final full stop is still a sentence.
		if (!text.isBlank() && !endsWithTerminator(text)) {
			count++;
		}
		return count;
	}

	private static boolean endsWithTerminator(String text) {
		String stripped = text.stripTrailing();
		if (stripped.isEmpty()) {
			return false;
		}
		char last = stripped.charAt(stripped.length() - 1);
		return last == '.' || last == '!' || last == '?';
	}
}
