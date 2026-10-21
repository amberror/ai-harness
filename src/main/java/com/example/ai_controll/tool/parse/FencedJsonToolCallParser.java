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
 * Recovers calls a model wrote as a fenced JSON block, or as a bare JSON object in its message.
 *
 * <p>The characteristic failure this catches is a model that has understood the task perfectly and
 * then announced the call instead of making it: "I'll look that up: ```json {"name":"get_weather",
 * …}```". Without recovery that is indistinguishable from a final answer, and the run ends having
 * done nothing while appearing to have succeeded.
 *
 * <p>Ordered last, and deliberately conservative: it only accepts an object that actually names a
 * tool and carries arguments, so ordinary JSON in an answer is not mistaken for a call.
 */
@Component
@Order(30)
public class FencedJsonToolCallParser implements ToolCallParser {

	private static final Pattern FENCED_BLOCK = Pattern.compile(
			"```(?:json|JSON|tool_code)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```", Pattern.DOTALL);

	private final ObjectMapper objectMapper;

	public FencedJsonToolCallParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "fenced_json";
	}

	@Override
	public Extraction parse(String content) {
		if (content == null || content.isBlank()) {
			return Extraction.none(content);
		}

		List<ToolCallRequest> calls = new ArrayList<>();
		Matcher matcher = FENCED_BLOCK.matcher(content);
		StringBuilder remaining = new StringBuilder();

		while (matcher.find()) {
			List<ToolCallRequest> found = read(matcher.group(1), calls.size());
			if (!found.isEmpty()) {
				calls.addAll(found);
				matcher.appendReplacement(remaining, "");
			}
			else {
				matcher.appendReplacement(remaining, Matcher.quoteReplacement(matcher.group(0)));
			}
		}
		matcher.appendTail(remaining);

		if (!calls.isEmpty()) {
			return new Extraction(calls, remaining.toString().strip());
		}

		// No fence: the whole message may itself be a bare tool-call object. Only accepted when the
		// message is nothing but that object, so a JSON example inside a real answer is left alone.
		String trimmed = content.strip();
		if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
			List<ToolCallRequest> bare = read(trimmed, 0);
			if (!bare.isEmpty()) {
				return new Extraction(bare, "");
			}
		}
		return Extraction.none(content);
	}

	private List<ToolCallRequest> read(String json, int offset) {
		JsonNode node;
		try {
			node = objectMapper.readTree(json);
		}
		catch (RuntimeException ex) {
			return List.of();
		}

		List<ToolCallRequest> calls = new ArrayList<>();
		if (node.isArray()) {
			// Some models batch several calls into one array.
			for (JsonNode element : node) {
				ToolCallShape.from(element, "recovered_json_%d".formatted(offset + calls.size() + 1))
						.ifPresent(calls::add);
			}
			return calls;
		}

		Optional<ToolCallRequest> single =
				ToolCallShape.from(node, "recovered_json_%d".formatted(offset + 1));
		single.ifPresent(calls::add);
		return calls;
	}
}
