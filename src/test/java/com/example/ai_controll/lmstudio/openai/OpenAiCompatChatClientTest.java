package com.example.ai_controll.lmstudio.openai;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.SamplingParams;
import com.example.ai_controll.tool.schema.ToolSchema;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Built around payloads recorded from the actual target model (Qwen3.5 4B, Q6_K) rather than from
 * the OpenAI specification, because the behaviour that matters here is this model's, not the
 * protocol's.
 */
class OpenAiCompatChatClientTest {

	private static final String BASE_URL = "http://localhost:1234";

	private static final String URL = BASE_URL + "/v1/chat/completions";

	private MockRestServiceServer server;

	private OpenAiCompatChatClient client;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		HarnessProperties properties = TestProperties.builder().baseUrl(BASE_URL)
				.defaultModel("qwen-4b-6q-unc").build();
		// The stream reader is only used by the streaming path, which these tests do not exercise.
		this.client = new OpenAiCompatChatClient(builder.build(), mapper, new ReasoningExtractor(), null,
				properties);
	}

	@Test
	@DisplayName("parses a recorded tool-calling response, keeping arguments as raw text")
	void parsesToolCallResponse() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{
				  "id": "chatcmpl-abc",
				  "model": "qwen-4b-6q-unc",
				  "choices": [{
				    "index": 0,
				    "message": {
				      "role": "assistant",
				      "content": "",
				      "reasoning_content": "The user wants the weather in Kyiv. I need to use the get_weather tool.",
				      "tool_calls": [{
				        "type": "function",
				        "id": "D2KhLcC7Jxv7rH7hKW2myfUx7oKgBWYr",
				        "function": { "name": "get_weather", "arguments": "{\\"city\\":\\"Kyiv\\"}" }
				      }]
				    },
				    "finish_reason": "tool_calls"
				  }],
				  "usage": {
				    "prompt_tokens": 289, "completion_tokens": 60, "total_tokens": 349,
				    "completion_tokens_details": { "reasoning_tokens": 31 }
				  },
				  "stats": {},
				  "system_fingerprint": "qwen-4b-6q-unc"
				}
				""", MediaType.APPLICATION_JSON));

		ChatResult result = client.complete(ChatRequest.of("qwen-4b-6q-unc",
				List.of(Message.user("weather in Kyiv?"))));

		assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
		assertThat(result.hasToolCalls()).isTrue();
		assertThat(result.hasContent()).isFalse();

		ToolCallRequest call = result.toolCalls().getFirst();
		assertThat(call.name()).isEqualTo("get_weather");
		// Kept as text: this is where malformed output from a small model shows up, and the exact
		// characters are needed both for the journal and to quote back when asking for a retry.
		assertThat(call.rawArguments()).isEqualTo("{\"city\":\"Kyiv\"}");

		assertThat(result.reasoning()).contains("get_weather tool");
		assertThat(result.usage().reasoningTokens()).isEqualTo(31);
		assertThat(result.usage().promptTokens()).isEqualTo(289);
		// Vendor extensions like stats and system_fingerprint must not break parsing.
		assertThat(result.rawResponseJson()).contains("system_fingerprint");
	}

	@Test
	@DisplayName("recognises a reasoning model that spent its whole allowance thinking")
	void detectsTruncationBeforeAnswering() {
		// Recorded verbatim: this model asked to "say OK" with max_tokens 20 spent all 20 tokens
		// reasoning and returned nothing. Read naively it looks like the model had nothing to say.
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{
				  "id": "chatcmpl-zg9",
				  "model": "qwen-4b-6q-unc",
				  "choices": [{
				    "index": 0,
				    "message": {
				      "role": "assistant",
				      "content": "",
				      "reasoning_content": "Thinking Process:\\n\\n1. **Analyze the Request:** The user wants me to say \\"",
				      "tool_calls": []
				    },
				    "finish_reason": "length"
				  }],
				  "usage": {
				    "prompt_tokens": 16, "completion_tokens": 20, "total_tokens": 36,
				    "completion_tokens_details": { "reasoning_tokens": 20 }
				  }
				}
				""", MediaType.APPLICATION_JSON));

		ChatResult result = client.complete(ChatRequest.of("qwen-4b-6q-unc",
				List.of(Message.user("Say OK and nothing else."))));

		assertThat(result.truncatedBeforeAnswering()).isTrue();
		assertThat(result.isEmpty()).isTrue();
		assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
		assertThat(result.usage().reasoningShare()).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("a normal answer is not mistaken for truncation")
	void plainAnswerIsNotTruncated() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{
				  "model": "qwen-4b-6q-unc",
				  "choices": [{ "message": { "role": "assistant", "content": "OK" }, "finish_reason": "stop" }],
				  "usage": { "prompt_tokens": 16, "completion_tokens": 2, "total_tokens": 18 }
				}
				""", MediaType.APPLICATION_JSON));

		ChatResult result = client.complete(ChatRequest.of("m", List.of(Message.user("hi"))));

		assertThat(result.content()).isEqualTo("OK");
		assertThat(result.truncatedBeforeAnswering()).isFalse();
		assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
		assertThat(result.usage().reasoningTokens()).isZero();
	}

	@Test
	@DisplayName("reasoning is never sent back to the model")
	void neverReplaysReasoning() {
		// Replaying a reasoning model's own chain of thought consumes context and degrades its
		// behaviour, so a prior assistant turn must go back without it.
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.messages[1].content").value("The answer is 4."))
				.andExpect(jsonPath("$.messages[1].reasoning_content").doesNotExist())
				.andRespond(withSuccess("""
						{"model":"m","choices":[{"message":{"role":"assistant","content":"ok"},
						 "finish_reason":"stop"}]}
						""", MediaType.APPLICATION_JSON));

		client.complete(ChatRequest.of("m", List.of(
				Message.user("what is 2+2?"),
				Message.assistant("The answer is 4.", "Let me add two and two, which gives four."))));

		server.verify();
	}

	@Test
	@DisplayName("tool schemas are sent with tool_choice auto")
	void sendsToolsWithAutoChoice() {
		ToolSchema schema = new ToolSchema("get_weather", "Get current weather for a city.",
				mapper.readTree("""
						{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""));

		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.tools[0].type").value("function"))
				.andExpect(jsonPath("$.tools[0].function.name").value("get_weather"))
				.andExpect(jsonPath("$.tools[0].function.parameters.required[0]").value("city"))
				.andExpect(jsonPath("$.tool_choice").value("auto"))
				.andRespond(withSuccess("""
						{"model":"m","choices":[{"message":{"role":"assistant","content":"ok"},
						 "finish_reason":"stop"}]}
						""", MediaType.APPLICATION_JSON));

		client.complete(new ChatRequest("m", List.of(Message.user("weather?")), List.of(schema),
				SamplingParams.deterministic(), 512, List.of(), null));

		server.verify();
	}

	@Test
	@DisplayName("unset sampling parameters are omitted so the server keeps its own defaults")
	void omitsUnsetSamplingParameters() {
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.temperature").value(0.0))
				.andExpect(jsonPath("$.top_p").doesNotExist())
				.andExpect(jsonPath("$.top_k").doesNotExist())
				.andExpect(jsonPath("$.tools").doesNotExist())
				.andExpect(jsonPath("$.tool_choice").doesNotExist())
				.andExpect(jsonPath("$.stream").doesNotExist())
				.andExpect(jsonPath("$.stream_options").doesNotExist())
				.andRespond(withSuccess("""
						{"model":"m","choices":[{"message":{"role":"assistant","content":"ok"},
						 "finish_reason":"stop"}]}
						""", MediaType.APPLICATION_JSON));

		client.complete(new ChatRequest("m", List.of(Message.user("hi")), List.of(),
				SamplingParams.deterministic(), null, List.of(), null));

		server.verify();
	}

	@Test
	@DisplayName("a streaming request asks for usage, which is otherwise never reported")
	void streamingRequestsUsage() {
		// Verified against the live server: without stream_options a streamed response carries no
		// token accounting at all, which would silently disable the token budget on exactly the calls
		// most likely to need it.
		OpenAiDtos.ChatCompletionRequest streaming = client.toWire(
				ChatRequest.of("m", List.of(Message.user("hi"))), true);

		assertThat(streaming.stream()).isTrue();
		assertThat(streaming.streamOptions()).isNotNull();
		assertThat(streaming.streamOptions().includeUsage()).isTrue();

		// Non-streaming reports usage unconditionally, so the option would be noise.
		OpenAiDtos.ChatCompletionRequest blocking = client.toWire(
				ChatRequest.of("m", List.of(Message.user("hi"))), false);

		assertThat(blocking.stream()).isNull();
		assertThat(blocking.streamOptions()).isNull();
	}

	@Test
	@DisplayName("the exact bytes sent and received are both retained")
	void retainsRawBodies() {
		String responseBody = """
				{"model":"m","choices":[{"message":{"role":"assistant","content":"hello"},
				 "finish_reason":"stop"}]}""";
		server.expect(requestTo(URL))
				.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

		ChatResult result = client.complete(ChatRequest.of("m", List.of(Message.user("hi"))));

		assertThat(result.rawRequestJson()).contains("\"model\":\"m\"").contains("\"hi\"");
		assertThat(result.rawResponseJson()).isEqualTo(responseBody);
		assertThat(result.latency()).isNotNull();
	}

	@Test
	@DisplayName("a response with no choices is handled rather than throwing")
	void survivesEmptyChoices() {
		server.expect(requestTo(URL))
				.andRespond(withSuccess("{\"model\":\"m\",\"choices\":[]}", MediaType.APPLICATION_JSON));

		ChatResult result = client.complete(ChatRequest.of("m", List.of(Message.user("hi"))));

		assertThat(result.isEmpty()).isTrue();
		assertThat(result.finishReason()).isEqualTo(FinishReason.UNKNOWN);
	}
}
