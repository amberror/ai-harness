package com.example.ai_controll.tool.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.example.ai_controll.conversation.ToolCallRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recovers calls wrapped in template markup such as {@code <tool_call>…</tool_call>}.
 *
 * <p>This is the most likely fallback to fire for the Qwen family specifically: their chat template
 * expresses tool calls in exactly these tags, and when the server's parser does not consume them --
 * a template mismatch, an unusual quantisation, an unexpected sampling path -- the tags arrive
 * verbatim in the message text. The call is right there, correctly formed; only the envelope was
 * missed. Discarding it would be a self-inflicted failure.
 */
@Component
@Order(20)
public class TaggedToolCallParser implements ToolCallParser {

	/** Tag names seen across chat templates, including the odd unicode variants. */
	private static final Pattern TAGGED = Pattern.compile(
			"<(tool_call|tool\u2581call|function_call|tool)>\\s*(.*?)\\s*</\\1>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private final ObjectMapper objectMapper;

	public TaggedToolCallParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "tagged";
	}

	@Override
	public Extraction parse(String content) {
		if (content == null || content.indexOf('<') < 0) {
			return Extraction.none(content);
		}

		List<ToolCallRequest> calls = new ArrayList<>();
		Matcher matcher = TAGGED.matcher(content);
		StringBuilder remaining = new StringBuilder();

		while (matcher.find()) {
			String body = matcher.group(2);
			Optional<ToolCallRequest> call = read(body, "recovered_tagged_%d".formatted(calls.size() + 1));
			if (call.isPresent()) {
				calls.add(call.get());
				matcher.appendReplacement(remaining, "");
			}
			else {
				// Looked like a tool call but was not one; leave the text where it was rather than
				// quietly deleting something the model might have meant as prose.
				matcher.appendReplacement(remaining, Matcher.quoteReplacement(matcher.group(0)));
			}
		}
		matcher.appendTail(remaining);

		return calls.isEmpty() ? Extraction.none(content)
				: new Extraction(calls, remaining.toString().strip());
	}

	private Optional<ToolCallRequest> read(String body, String syntheticId) {
		try {
			JsonNode node = objectMapper.readTree(body);
			return ToolCallShape.from(node, syntheticId);
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}
}
