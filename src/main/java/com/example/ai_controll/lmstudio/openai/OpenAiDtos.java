package com.example.ai_controll.lmstudio.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * Wire types for LM Studio's OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>Grouped in one file because they are a single cohesive protocol description and are only ever
 * touched together. Unknown properties are ignored throughout: LM Studio adds vendor extensions
 * (such as {@code stats} and {@code system_fingerprint}) that must never break the harness.
 */
public final class OpenAiDtos {

	private OpenAiDtos() {
	}

	// ---------------------------------------------------------------------------------------
	// Request
	// ---------------------------------------------------------------------------------------

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionRequest(@JsonProperty("model") String model,
			@JsonProperty("messages") List<WireMessage> messages,
			@JsonProperty("tools") List<WireTool> tools,
			@JsonProperty("tool_choice") String toolChoice,
			@JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP,
			@JsonProperty("top_k") Integer topK,
			@JsonProperty("min_p") Double minP,
			@JsonProperty("repeat_penalty") Double repeatPenalty,
			@JsonProperty("max_tokens") Integer maxTokens,
			@JsonProperty("seed") Integer seed,
			@JsonProperty("stop") List<String> stop,
			@JsonProperty("stream") Boolean stream,
			@JsonProperty("stream_options") StreamOptions streamOptions,
			@JsonProperty("response_format") WireResponseFormat responseFormat) {
	}

	/**
	 * Grammar-constrained decoding, as the OpenAI-compatible surface expresses it.
	 *
	 * <p>Sent only when a run asks for it. Omitted entirely otherwise: some builds treat the presence
	 * of the field as a request for JSON mode even with a null value inside it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WireResponseFormat(@JsonProperty("type") String type,
			@JsonProperty("json_schema") WireJsonSchema jsonSchema) {

		public static WireResponseFormat of(WireJsonSchema schema) {
			return new WireResponseFormat("json_schema", schema);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WireJsonSchema(@JsonProperty("name") String name,
			@JsonProperty("strict") Boolean strict,
			@JsonProperty("schema") JsonNode schema) {
	}

	/**
	 * Streaming options.
	 *
	 * <p>Without {@code include_usage} a streamed response carries no token accounting at all, which
	 * would leave the harness unable to enforce a token budget on exactly the calls most likely to
	 * need one. LM Studio answers it with a final chunk that has an empty {@code choices} array and
	 * the usage attached.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {

		public static final StreamOptions INCLUDE_USAGE = new StreamOptions(Boolean.TRUE);
	}

	/**
	 * A message in either direction.
	 *
	 * <p>{@code reasoning_content} is only ever read, never sent: replaying a reasoning model's own
	 * chain of thought back to it consumes context and degrades its behaviour.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WireMessage(@JsonProperty("role") String role,
			@JsonProperty("content") String content,
			@JsonProperty("reasoning_content") String reasoningContent,
			@JsonProperty("tool_calls") List<WireToolCall> toolCalls,
			@JsonProperty("tool_call_id") String toolCallId,
			@JsonProperty("name") String name) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WireTool(@JsonProperty("type") String type,
			@JsonProperty("function") WireFunction function) {

		public static WireTool function(WireFunction function) {
			return new WireTool("function", function);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record WireFunction(@JsonProperty("name") String name,
			@JsonProperty("description") String description,
			@JsonProperty("parameters") JsonNode parameters) {
	}

	// ---------------------------------------------------------------------------------------
	// Response
	// ---------------------------------------------------------------------------------------

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatCompletionResponse(@JsonProperty("id") String id,
			@JsonProperty("model") String model,
			@JsonProperty("choices") List<Choice> choices,
			@JsonProperty("usage") Usage usage,
			@JsonProperty("system_fingerprint") String systemFingerprint) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Choice(@JsonProperty("index") Integer index,
			@JsonProperty("message") WireMessage message,
			@JsonProperty("delta") WireMessage delta,
			@JsonProperty("finish_reason") String finishReason) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WireToolCall(@JsonProperty("index") Integer index,
			@JsonProperty("id") String id,
			@JsonProperty("type") String type,
			@JsonProperty("function") WireToolCallFunction function) {
	}

	/**
	 * {@code arguments} is a JSON document encoded as a string, and it is where malformed output from
	 * a small model most often shows up, so it is carried as text and parsed later.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WireToolCallFunction(@JsonProperty("name") String name,
			@JsonProperty("arguments") String arguments) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
			@JsonProperty("completion_tokens") Integer completionTokens,
			@JsonProperty("total_tokens") Integer totalTokens,
			@JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompletionTokensDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
	}
}
