package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/v1/models/load}.
 *
 * <p>{@code contextLength} is the reason the harness uses the native admin API rather than the
 * OpenAI-compatible surface for model management: it is the main lever for keeping a small model
 * inside a workable context budget, and it can only be set at load time.
 *
 * <p>Null fields are omitted so that LM Studio applies its own defaults for anything not specified.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadModelRequest(@JsonProperty("model") @NotBlank String model,
		@JsonProperty("context_length") @Positive Integer contextLength,
		@JsonProperty("eval_batch_size") @Positive Integer evalBatchSize,
		@JsonProperty("flash_attention") Boolean flashAttention,
		@JsonProperty("num_experts") @Positive Integer numExperts,
		@JsonProperty("offload_kv_cache_to_gpu") Boolean offloadKvCacheToGpu,
		@JsonProperty("echo_load_config") Boolean echoLoadConfig) {

	/** Loads a model with LM Studio's defaults, always echoing back the resolved configuration. */
	public static LoadModelRequest of(String model) {
		return new LoadModelRequest(model, null, null, null, null, null, Boolean.TRUE);
	}

	/** Loads a model with an explicit context window, always echoing back the configuration. */
	public static LoadModelRequest of(String model, Integer contextLength) {
		return new LoadModelRequest(model, contextLength, null, null, null, null, Boolean.TRUE);
	}

	/**
	 * The harness always wants to know the configuration a model was actually loaded with, so that
	 * the run journal can record it; callers should not have to remember to ask.
	 */
	public LoadModelRequest withEchoedConfig() {
		return new LoadModelRequest(model, contextLength, evalBatchSize, flashAttention, numExperts,
				offloadKvCacheToGpu, Boolean.TRUE);
	}
}
