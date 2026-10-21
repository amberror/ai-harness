package com.example.ai_controll.lmstudio.openai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.ChatCompletionResponse;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.Choice;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireMessage;
import com.example.ai_controll.lmstudio.openai.OpenAiDtos.WireToolCall;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.ChatStreamEvent;
import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.TokenUsage;

import tools.jackson.databind.ObjectMapper;

/**
 * Reads a streaming chat completion as Server-Sent Events and reassembles it.
 *
 * <p>Streaming is not a cosmetic feature here. A small local model can take a long time to produce a
 * first token, and the difference between "thinking", "answering" and "hung" is invisible without a
 * live view -- which is exactly what the harness is meant to make observable.
 *
 * <p>The raw stream is retained alongside the reassembled result, so the journal records what the
 * server actually sent rather than the harness's interpretation of it -- but only up to a cap. A
 * streamed response is not one document; it is one event envelope per token, and a single long
 * answer measured 820 KB of stream for 2,758 tokens, almost all of it repeated framing. Beyond the
 * cap the text is dropped and the omission is stated explicitly in what remains.
 */
@Component
public class SseChatStreamReader {

	private static final Logger log = LoggerFactory.getLogger(SseChatStreamReader.class);

	private static final String DATA_PREFIX = "data:";

	private static final String DONE = "[DONE]";

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	private final ReasoningExtractor reasoningExtractor;

	private final HarnessProperties.LmStudio config;

	/**
	 * Ceiling on the retained raw stream. A streamed response is thousands of event envelopes rather
	 * than one document, so an unbounded buffer would grow to hundreds of kilobytes for a single long
	 * answer -- held in memory for the whole call, then written to the journal.
	 */
	private final int maxRawChars;

	public SseChatStreamReader(HttpClient lmStudioHttpClient, ObjectMapper objectMapper,
			ReasoningExtractor reasoningExtractor, HarnessProperties properties) {
		this.httpClient = lmStudioHttpClient;
		this.objectMapper = objectMapper;
		this.reasoningExtractor = reasoningExtractor;
		this.config = properties.lmStudio();
		this.maxRawChars = properties.journal().maxRawBodyChars();
	}

	/**
	 * Streams one chat completion, pushing events to the sink as they arrive. The sink always
	 * receives exactly one terminal event, whether the stream succeeds or fails.
	 */
	public void stream(String requestJson, Consumer<ChatStreamEvent> sink) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/chat/completions"))
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.timeout(config.readTimeout())
				.POST(HttpRequest.BodyPublishers.ofString(requestJson));
		if (config.hasApiToken()) {
			builder.header("Authorization", "Bearer " + config.apiToken());
		}

		long startedAt = System.nanoTime();
		Accumulator accumulator = new Accumulator();

		try {
			HttpResponse<Stream<String>> response = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofLines());

			if (response.statusCode() >= 400) {
				String body;
				try (Stream<String> lines = response.body()) {
					body = String.join("\n", lines.toList());
				}
				sink.accept(new ChatStreamEvent.Failed(
						"LM Studio returned HTTP %d: %s".formatted(response.statusCode(), body), null));
				return;
			}

			try (Stream<String> lines = response.body()) {
				lines.forEach(line -> handleLine(line, accumulator, sink));
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			sink.accept(new ChatStreamEvent.Failed("Streaming was interrupted", ex));
			return;
		}
		catch (Exception ex) {
			// The partial output collected so far is still worth reporting: on a small model, a
			// stream that dies halfway is itself a finding, and the fragment usually shows why.
			log.warn("Chat stream failed after {} content chars", accumulator.content.length(), ex);
			sink.accept(new ChatStreamEvent.Failed(
					"Streaming failed: " + ex.getMessage(), ex));
			return;
		}

		Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
		sink.accept(new ChatStreamEvent.Completed(accumulator.toResult(requestJson, latency)));
	}

	private void handleLine(String line, Accumulator accumulator, Consumer<ChatStreamEvent> sink) {
		accumulator.appendRaw(line);

		if (line.isBlank() || !line.startsWith(DATA_PREFIX)) {
			return; // comments, event: lines and blank separators carry nothing we need
		}
		String data = line.substring(DATA_PREFIX.length()).trim();
		if (data.isEmpty() || DONE.equals(data)) {
			return;
		}

		ChatCompletionResponse chunk;
		try {
			chunk = objectMapper.readValue(data, ChatCompletionResponse.class);
		}
		catch (RuntimeException ex) {
			log.debug("Skipping unparseable stream chunk: {}", data);
			return;
		}

		if (chunk.model() != null) {
			accumulator.modelInstanceId = chunk.model();
		}
		if (chunk.usage() != null) {
			accumulator.usage = OpenAiCompatChatClient.toUsage(chunk.usage());
		}
		if (chunk.choices() == null || chunk.choices().isEmpty()) {
			return;
		}

		Choice choice = chunk.choices().getFirst();
		if (choice.finishReason() != null) {
			accumulator.finishReason = FinishReason.fromWire(choice.finishReason());
		}

		// A chunk carries a delta while streaming, but the final chunk of some servers carries a
		// complete message instead, so both are accepted.
		WireMessage delta = choice.delta() != null ? choice.delta() : choice.message();
		if (delta == null) {
			return;
		}

		if (!accumulator.started) {
			accumulator.started = true;
			sink.accept(new ChatStreamEvent.Started(accumulator.modelInstanceId));
		}

		if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
			accumulator.reasoning.append(delta.reasoningContent());
			sink.accept(new ChatStreamEvent.ReasoningDelta(delta.reasoningContent()));
		}
		if (delta.content() != null && !delta.content().isEmpty()) {
			accumulator.content.append(delta.content());
			sink.accept(new ChatStreamEvent.ContentDelta(delta.content()));
		}
		if (delta.toolCalls() != null) {
			for (WireToolCall call : delta.toolCalls()) {
				accumulator.acceptToolCallFragment(call, sink);
			}
		}
	}

	/**
	 * Reassembles a streamed response.
	 *
	 * <p>Tool calls are the awkward part: they arrive as fragments keyed by an index, with the name
	 * usually in the first fragment and the arguments dribbled across many later ones, so they have
	 * to be accumulated per index rather than handled a chunk at a time.
	 */
	private final class Accumulator {

		private final StringBuilder raw = new StringBuilder();

		/** Characters seen, including any dropped once the cap was reached. */
		private long rawCharsSeen;

		private final StringBuilder content = new StringBuilder();

		private final StringBuilder reasoning = new StringBuilder();

		private final Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();

		private String modelInstanceId;

		private FinishReason finishReason = FinishReason.UNKNOWN;

		private TokenUsage usage = TokenUsage.EMPTY;

		private boolean started;

		/** Retains the raw stream up to the cap, then counts the rest without storing it. */
		void appendRaw(String line) {
			rawCharsSeen += line.length() + 1;
			if (raw.length() < maxRawChars) {
				raw.append(line).append('\n');
			}
		}

		/** The retained stream, with an explicit marker when it was cut short. */
		String rawStream() {
			if (rawCharsSeen <= raw.length()) {
				return raw.toString();
			}
			long omitted = rawCharsSeen - raw.length();
			// Locale.ROOT so the recorded marker reads identically on every machine.
			return raw + String.format(Locale.ROOT,
					"%n... [truncated by the harness: %,d of %,d characters omitted] ...",
					omitted, rawCharsSeen);
		}

		void acceptToolCallFragment(WireToolCall fragment, Consumer<ChatStreamEvent> sink) {
			// Servers that only ever stream one call at a time may omit the index entirely.
			int index = fragment.index() == null ? 0 : fragment.index();
			PartialToolCall partial = toolCalls.computeIfAbsent(index, key -> new PartialToolCall());

			if (fragment.id() != null) {
				partial.id = fragment.id();
			}
			if (fragment.function() != null) {
				if (fragment.function().name() != null) {
					partial.name = fragment.function().name();
				}
				if (fragment.function().arguments() != null) {
					partial.arguments.append(fragment.function().arguments());
				}
			}

			// Announce a call once it has a name; the arguments keep accumulating after that, and the
			// authoritative copy is the one in the completed result.
			if (partial.name != null && !partial.announced) {
				partial.announced = true;
				sink.accept(new ChatStreamEvent.ToolCallAssembled(partial.toRequest()));
			}
		}

		ChatResult toResult(String requestJson, Duration latency) {
			// Inline <think> tags can survive streaming, so the same extraction runs on the assembled
			// text as on a non-streamed response.
			ReasoningExtractor.Split split = reasoningExtractor.split(content.toString(),
					reasoning.isEmpty() ? null : reasoning.toString());

			List<ToolCallRequest> calls = new ArrayList<>();
			for (PartialToolCall partial : toolCalls.values()) {
				if (partial.name != null) {
					calls.add(partial.toRequest());
				}
			}

			return new ChatResult(split.content(), split.reasoning(), calls, finishReason, usage,
					modelInstanceId, requestJson, rawStream(), latency);
		}
	}

	private static final class PartialToolCall {

		private String id;

		private String name;

		private final StringBuilder arguments = new StringBuilder();

		private boolean announced;

		ToolCallRequest toRequest() {
			return new ToolCallRequest(id, name, arguments.toString());
		}
	}
}
