package com.example.ai_controll.lmstudio.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Separates a model's chain of thought from its actual answer.
 *
 * <p>Reasoning reaches the harness by two different routes, and which one applies depends on the
 * model, the server version and the server's settings rather than on anything the harness controls:
 *
 * <ul>
 *   <li>a dedicated {@code reasoning_content} field on the response message -- what LM Studio
 *       returns for the Qwen3 family;</li>
 *   <li>inline {@code <think>} tags left in the content itself -- common when a chat template leaks
 *       them through.</li>
 * </ul>
 *
 * <p>Both are handled, and so are the ragged cases that show up in practice: an opening tag with no
 * closing tag because generation hit its token limit mid-thought, and a closing tag with no opening
 * tag because the server stripped the opener but not the closer. Getting this wrong is not cosmetic
 * -- unextracted reasoning ends up quoted back to the model as if it were an answer, and an answer
 * mistaken for reasoning is discarded entirely.
 */
@Component
public class ReasoningExtractor {

	/** Tag names seen in the wild across reasoning models. */
	private static final String TAGS = "think|thinking|reason|reasoning";

	private static final Pattern PAIRED = Pattern.compile(
			"<(" + TAGS + ")\\b[^>]*>(.*?)</\\1\\s*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/** An opener with no closer: generation stopped mid-thought. */
	private static final Pattern UNCLOSED = Pattern.compile(
			"<(" + TAGS + ")\\b[^>]*>(.*)\\z",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/** A closer with no opener: the server emitted only the end of the block. */
	private static final Pattern ORPHAN_CLOSE = Pattern.compile(
			"\\A(.*?)</(" + TAGS + ")\\s*>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/**
	 * Splits raw model output into an answer and a chain of thought.
	 *
	 * @param rawContent           the {@code content} field, which may embed reasoning tags
	 * @param reasoningContentField the dedicated {@code reasoning_content} field, may be null
	 * @return the answer and the reasoning, each null when absent
	 */
	public Split split(String rawContent, String reasoningContentField) {
		List<String> reasoningParts = new ArrayList<>();

		if (reasoningContentField != null && !reasoningContentField.isBlank()) {
			reasoningParts.add(reasoningContentField.strip());
		}

		String content = rawContent == null ? "" : rawContent;

		// Complete <think>...</think> blocks, of which there may be several.
		Matcher paired = PAIRED.matcher(content);
		StringBuilder withoutPaired = new StringBuilder();
		while (paired.find()) {
			String inner = paired.group(2);
			if (inner != null && !inner.isBlank()) {
				reasoningParts.add(inner.strip());
			}
			paired.appendReplacement(withoutPaired, "");
		}
		paired.appendTail(withoutPaired);
		content = withoutPaired.toString();

		// A closing tag with no opener: everything before it was reasoning.
		Matcher orphan = ORPHAN_CLOSE.matcher(content);
		if (orphan.find()) {
			String before = orphan.group(1);
			if (before != null && !before.isBlank()) {
				reasoningParts.add(before.strip());
			}
			content = content.substring(orphan.end());
		}

		// An opening tag with no closer: the model ran out of tokens while thinking, so everything
		// from the tag onwards is reasoning and there is no answer after it.
		Matcher unclosed = UNCLOSED.matcher(content);
		if (unclosed.find()) {
			String inner = unclosed.group(2);
			if (inner != null && !inner.isBlank()) {
				reasoningParts.add(inner.strip());
			}
			content = content.substring(0, unclosed.start());
		}

		String answer = content.strip();
		String reasoning = reasoningParts.isEmpty() ? null : String.join("\n\n", reasoningParts);
		return new Split(answer.isEmpty() ? null : answer, reasoning);
	}

	/**
	 * @param content   the model's answer, or null when it produced none
	 * @param reasoning the chain of thought, or null when there was none
	 */
	public record Split(String content, String reasoning) {

		public boolean hasContent() {
			return content != null && !content.isBlank();
		}

		public boolean hasReasoning() {
			return reasoning != null && !reasoning.isBlank();
		}
	}
}
