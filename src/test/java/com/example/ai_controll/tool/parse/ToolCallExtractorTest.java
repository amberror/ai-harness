package com.example.ai_controll.tool.parse;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.TokenUsage;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure this guards against is quiet and expensive: a model that announces a tool call in
 * prose instead of emitting it looks, to an unguarded harness, exactly like a model that finished.
 * The run then reports success having done nothing at all.
 */
class ToolCallExtractorTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	// Injected in @Order sequence, as Spring would.
	private final ToolCallExtractor extractor = new ToolCallExtractor(List.of(
			new TaggedToolCallParser(mapper), new FencedJsonToolCallParser(mapper)));

	private ChatResult response(String content, List<ToolCallRequest> nativeCalls) {
		return new ChatResult(content, null, nativeCalls, FinishReason.STOP, TokenUsage.EMPTY, "m",
				"{}", "{}", Duration.ZERO);
	}

	@Test
	@DisplayName("native tool calls are used as-is and reported as the native channel")
	void prefersNativeChannel() {
		ChatResult result = response("", List.of(
				new ToolCallRequest("call_1", "get_weather", "{\"city\":\"Kyiv\"}")));

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.channel()).isEqualTo("native");
		assertThat(extraction.recovered()).isFalse();
		assertThat(extraction.toolCalls()).singleElement()
				.satisfies(call -> assertThat(call.name()).isEqualTo("get_weather"));
	}

	@Test
	@DisplayName("a native call wins even when the text also looks like one")
	void nativeWinsOverText() {
		// No re-parsing of the message when the proper channel already carried the call, or the same
		// call would be run twice.
		ChatResult result = response("<tool_call>{\"name\":\"other\",\"arguments\":{}}</tool_call>",
				List.of(new ToolCallRequest("call_1", "get_weather", "{}")));

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.toolCalls()).hasSize(1);
		assertThat(extraction.toolCalls().getFirst().name()).isEqualTo("get_weather");
	}

	@Test
	@DisplayName("a leaked <tool_call> tag is recovered")
	void recoversTaggedCall() {
		// Qwen's own chat template uses exactly these tags. When the server's parser misses them the
		// call arrives verbatim in the text -- correctly formed, just in the wrong envelope.
		ChatResult result = response("""
				<tool_call>
				{"name": "get_weather", "arguments": {"city": "Kyiv"}}
				</tool_call>""", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.recovered()).isTrue();
		assertThat(extraction.channel()).isEqualTo("tagged");
		assertThat(extraction.toolCalls().getFirst().name()).isEqualTo("get_weather");
		assertThat(extraction.toolCalls().getFirst().rawArguments()).contains("Kyiv");
		assertThat(extraction.remainingContent()).isEmpty();
	}

	@Test
	@DisplayName("text around a recovered tag is preserved as the message")
	void keepsSurroundingText() {
		ChatResult result = response(
				"Let me check that.<tool_call>{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Kyiv\"}}</tool_call>",
				List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.toolCalls()).hasSize(1);
		assertThat(extraction.remainingContent()).isEqualTo("Let me check that.");
	}

	@Test
	@DisplayName("a fenced JSON call is recovered")
	void recoversFencedCall() {
		ChatResult result = response("""
				I'll look that up.

				```json
				{"name": "get_weather", "arguments": {"city": "Kyiv"}}
				```""", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.recovered()).isTrue();
		assertThat(extraction.channel()).isEqualTo("fenced_json");
		assertThat(extraction.toolCalls().getFirst().name()).isEqualTo("get_weather");
		assertThat(extraction.remainingContent()).isEqualTo("I'll look that up.");
	}

	@Test
	@DisplayName("alternative key names are understood")
	void normalisesAlternativeShapes() {
		// There is no single convention across templates and fine-tunes; all of these mean the same.
		assertThat(recoveredName("<tool_call>{\"name\":\"a\",\"parameters\":{}}</tool_call>")).isEqualTo("a");
		assertThat(recoveredName("<tool_call>{\"tool\":\"b\",\"args\":{}}</tool_call>")).isEqualTo("b");
		assertThat(recoveredName(
				"<tool_call>{\"type\":\"function\",\"function\":{\"name\":\"c\",\"arguments\":\"{}\"}}</tool_call>"))
				.isEqualTo("c");
	}

	@Test
	@DisplayName("several calls in one array are all recovered")
	void recoversBatchedCalls() {
		ChatResult result = response("""
				```json
				[{"name":"a","arguments":{}},{"name":"b","arguments":{}}]
				```""", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.toolCalls()).hasSize(2);
		assertThat(extraction.toolCalls()).extracting(ToolCallRequest::name).containsExactly("a", "b");
	}

	@Test
	@DisplayName("a bare tool-call object as the whole message is recovered")
	void recoversBareObject() {
		ChatResult result = response("{\"name\":\"clock_now\",\"arguments\":{}}", List.of());

		assertThat(extractor.extract(result).toolCalls()).hasSize(1);
	}

	@Test
	@DisplayName("ordinary JSON inside a real answer is not mistaken for a call")
	void doesNotMisreadExampleJson() {
		// The parsers must be conservative. Treating an illustrative snippet as a call would invent
		// work the model never asked for, which is worse than missing a real one.
		ChatResult result = response("""
				Here is an example config:

				```json
				{"host": "localhost", "port": 8080}
				```

				Use it as a starting point.""", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.hasToolCalls()).isFalse();
		assertThat(extraction.recovered()).isFalse();
		// The message must come through untouched.
		assertThat(extraction.remainingContent()).contains("localhost").contains("starting point");
	}

	@Test
	@DisplayName("a plain answer stays a plain answer")
	void leavesPlainAnswerAlone() {
		ChatResult result = response("The weather in Kyiv is mild today.", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.hasToolCalls()).isFalse();
		assertThat(extraction.remainingContent()).isEqualTo("The weather in Kyiv is mild today.");
	}

	@Test
	@DisplayName("empty and null content are handled")
	void handlesEmptyContent() {
		assertThat(extractor.extract(response(null, List.of())).hasToolCalls()).isFalse();
		assertThat(extractor.extract(response("", List.of())).hasToolCalls()).isFalse();
		assertThat(extractor.extract(response("   ", List.of())).hasToolCalls()).isFalse();
	}

	@Test
	@DisplayName("a malformed tagged block is left in the text rather than deleted")
	void keepsUnparseableTagAsText() {
		// Deleting text the harness could not understand would hide the evidence of what went wrong.
		ChatResult result = response("<tool_call>this is not json</tool_call>", List.of());

		ToolCallExtractor.Extraction extraction = extractor.extract(result);

		assertThat(extraction.hasToolCalls()).isFalse();
		assertThat(extraction.remainingContent()).contains("this is not json");
	}

	private String recoveredName(String content) {
		return extractor.extract(response(content, List.of())).toolCalls().getFirst().name();
	}
}
