package com.example.ai_controll.lmstudio.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One model known to LM Studio, as returned by {@code GET /api/v1/models}.
 *
 * <p>Fields are mapped explicitly rather than through a naming strategy: this is a raw external
 * boundary, and being able to see exactly which wire field feeds which component is worth the
 * verbosity. Unknown properties are ignored so that an LM Studio upgrade cannot break the harness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInfo(@JsonProperty("type") String type,
		@JsonProperty("publisher") String publisher,
		@JsonProperty("key") String key,
		@JsonProperty("display_name") String displayName,
		@JsonProperty("architecture") String architecture,
		@JsonProperty("quantization") Quantization quantization,
		@JsonProperty("size_bytes") Long sizeBytes,
		@JsonProperty("params_string") String paramsString,
		@JsonProperty("loaded_instances") List<LoadedInstance> loadedInstances,
		@JsonProperty("max_context_length") Integer maxContextLength,
		@JsonProperty("format") String format,
		@JsonProperty("capabilities") Capabilities capabilities,
		@JsonProperty("description") String description) {

	public ModelInfo {
		loadedInstances = loadedInstances == null ? List.of() : List.copyOf(loadedInstances);
	}

	@JsonIgnore
	public boolean isLlm() {
		return "llm".equalsIgnoreCase(type);
	}

	@JsonIgnore
	public boolean isLoaded() {
		return !loadedInstances.isEmpty();
	}

	/**
	 * Whether the model was trained for tool use. The harness surfaces this prominently because
	 * driving an agentic loop against a model that was not trained for tool calling is the single
	 * most common cause of "the model ignores my tools".
	 */
	@JsonIgnore
	public boolean supportsToolUse() {
		return capabilities != null && Boolean.TRUE.equals(capabilities.trainedForToolUse());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Quantization(@JsonProperty("name") String name,
			@JsonProperty("bits_per_weight") Double bitsPerWeight) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Capabilities(@JsonProperty("vision") Boolean vision,
			@JsonProperty("trained_for_tool_use") Boolean trainedForToolUse) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LoadedInstance(@JsonProperty("id") String id,
			@JsonProperty("config") InstanceConfig config) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InstanceConfig(@JsonProperty("context_length") Integer contextLength,
			@JsonProperty("eval_batch_size") Integer evalBatchSize,
			@JsonProperty("flash_attention") Boolean flashAttention,
			@JsonProperty("num_experts") Integer numExperts,
			@JsonProperty("offload_kv_cache_to_gpu") Boolean offloadKvCacheToGpu) {
	}
}
