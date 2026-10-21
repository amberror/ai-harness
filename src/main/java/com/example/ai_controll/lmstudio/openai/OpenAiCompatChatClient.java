package com.example.ai_controll.lmstudio.openai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.dto.LmStudioError;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.ChatCompletionRequest;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.ChatCompletionResponse;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.Choice;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireFunction;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireMessage;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireTool;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireToolCall;
import com.example.ai_controll.lmstudio.port.ChatModelClient;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.ChatStreamEvent;
import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.ResponseFormat;
import com.example.ai_controll.lmstudio.port.SamplingParams;
import com.example.ai_controll.lmstudio.port.TokenUsage;

import tools.jackson.databind.ObjectMapper;

/**
 * Drives LM Studio's OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>The request body is serialised here rather than handed to the HTTP client as an object, and the
 * response is read as a string before being parsed. That is deliberate: it costs one extra parse and
 * buys the exact bytes in both directions, which is what the run journal records. A reconstruction
 * of what was probably sent is not evidence of what was actually sent.
 */
@Component
public class OpenAiCompatChatClient implements ChatModelClient {

	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatChatClient.class);

	private static final String CHAT_COMPLETIONS = "/v1/chat/completions";

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final ReasoningExtractor reasoningExtractor;

	private final SseChatStreamReader streamReader;

	private final String baseUrl;

	public OpenAiCompatChatClient(RestClient lmStudioRestClient, ObjectMapper objectMapper,
			ReasoningExtractor reasoningExtractor, SseChatStreamReader streamReader,
			HarnessProperties properties) {
		this.restClient = lmStudioRestClient;
		this.objectMapper = objectMapper;
		this.reasoningExtractor = reasoningExtractor;
		this.streamReader = streamReader;
		this.baseUrl = properties.lmStudio().baseUrl();
	}

	@Override
	public ChatResult complete(ChatRequest request) {
		String requestJson = objectMapper.writeValueAsString(toWire(request, false));
		long startedAt = System.nanoTime();

		String responseJson;
		try {
			responseJson = restClient.post()
					.uri(CHAT_COMPLETIONS)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON)
					.body(requestJson)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						throw errorFrom(res.getStatusCode(), readBody(res));
					})
					.body(String.class);
		}
		catch (ResourceAccessException ex) {
			throw LmStudioException.unreachable(baseUrl, ex);
		}

		Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
		return parseResult(requestJson, responseJson, latency);
	}

	@Override
	public void stream(ChatRequest request, Consumer<ChatStreamEvent> sink) {
		String requestJson = objectMapper.writeValueAsString(toWire(request, true));
		streamReader.stream(requestJson, sink);
	}

	// -------------------------------------------------------------------------------------------
	// Domain -> wire
	// -------------------------------------------------------------------------------------------

	ChatCompletionRequest toWire(ChatRequest request, boolean stream) {
		List<WireMessage> messages = request.messages().stream().map(this::toWire).toList();

		List<WireTool> tools = request.tools().isEmpty() ? null
				: request.tools().stream()
						.map(tool -> WireTool.function(
								new WireFunction(tool.name(), tool.description(), tool.parametersSchema())))
						.toList();

		SamplingParams sampling = request.sampling();
		ResponseFormat format = request.responseFormat();
		return new ChatCompletionRequest(request.modelKey(), messages, tools,
				tools == null ? null : "auto",
				sampling.temperature(), sampling.topP(), sampling.topK(), sampling.minP(),
				sampling.repeatPenalty(), request.maxOutputTokens(), sampling.seed(),
				request.stop().isEmpty() ? null : request.stop(),
				stream ? Boolean.TRUE : null,
				// Streaming reports no usage unless it is asked for, and a budget cannot be enforced
				// against numbers that never arrive.
				stream ? OpenAiDtos.StreamOptions.INCLUDE_USAGE : null,
				format == null ? null : OpenAiDtos.WireResponseFormat.of(
						new OpenAiDtos.WireJsonSchema(format.name(), format.strict(), format.schema())));
	}

	private WireMessage toWire(Message message) {
		List<WireToolCall> toolCalls = message.hasToolCalls()
				? message.toolCalls().stream()
						.map(call -> new WireToolCall(null, call.id(), "function",
								new OpenAiDtos.WireToolCallFunction(call.name(), call.rawArguments())))
						.toList()
				: null;

		// Reasoning is never sent back: it would consume context and degrade the model's behaviour.
		return new WireMessage(message.role().wireValue(),
				message.content() == null ? "" : message.content(),
				null, toolCalls, message.toolCallId(), message.toolName());
	}

	// -------------------------------------------------------------------------------------------
	// Wire -> domain
	// -------------------------------------------------------------------------------------------

	ChatResult parseResult(String requestJson, String responseJson, Duration latency) {
		ChatCompletionResponse response = objectMapper.readValue(responseJson, ChatCompletionResponse.class);

		Choice choice = (response.choices() == null || response.choices().isEmpty()) ? null
				: response.choices().getFirst();
		WireMessage message = choice == null ? null : choice.message();

		ReasoningExtractor.Split split = reasoningExtractor.split(
				message == null ? null : message.content(),
				message == null ? null : message.reasoningContent());

		List<ToolCallRequest> toolCalls = new ArrayList<>();
		if (message != null && message.toolCalls() != null) {
			for (WireToolCall call : message.toolCalls()) {
				if (call.function() == null) {
					continue;
				}
				toolCalls.add(new ToolCallRequest(call.id(), call.function().name(),
						call.function().arguments()));
			}
		}

		FinishReason finishReason = FinishReason.fromWire(choice == null ? null : choice.finishReason());
		TokenUsage usage = toUsage(response.usage());

		ChatResult result = new ChatResult(split.content(), split.reasoning(), toolCalls, finishReason,
				usage, response.model(), requestJson, responseJson, latency);

		if (result.truncatedBeforeAnswering()) {
			// Worth a warning rather than a debug line: it looks like an empty answer, but the real
			// cause is that reasoning consumed the entire output allowance.
			log.warn("Model produced no answer: output limit reached after {} tokens, {} of them "
					+ "reasoning. Raise max_output_tokens or lower the reasoning effort.",
					usage.completionTokens(), usage.reasoningTokens());
		}
		return result;
	}

	static TokenUsage toUsage(OpenAiDtos.Usage usage) {
		if (usage == null) {
			return TokenUsage.EMPTY;
		}
		int reasoning = usage.completionTokensDetails() == null
				|| usage.completionTokensDetails().reasoningTokens() == null ? 0
						: usage.completionTokensDetails().reasoningTokens();
		return new TokenUsage(orZero(usage.promptTokens()), orZero(usage.completionTokens()), reasoning,
				orZero(usage.totalTokens()));
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

	// -------------------------------------------------------------------------------------------
	// Errors
	// -------------------------------------------------------------------------------------------

	private LmStudioException errorFrom(HttpStatusCode status, String rawBody) {
		LmStudioError error = null;
		try {
			LmStudioError.Envelope envelope = objectMapper.readValue(rawBody, LmStudioError.Envelope.class);
			error = envelope == null ? null : envelope.error();
		}
		catch (RuntimeException ex) {
			log.debug("Could not parse chat error body: {}", rawBody);
		}
		String detail = (error != null && error.message() != null) ? error.message() : rawBody;
		return new LmStudioException(
				"LM Studio returned %s for %s: %s".formatted(status, CHAT_COMPLETIONS, detail), status,
				error, rawBody, null);
	}

	private String readBody(org.springframework.http.client.ClientHttpResponse response) {
		try (var body = response.getBody()) {
			return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			return "";
		}
	}
}
