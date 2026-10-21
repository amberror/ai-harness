package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/models/download}.
 *
 * @param model        a catalog identifier such as {@code qwen/qwen3-4b}, or an exact Hugging Face
 *                     repository URL
 * @param quantization quantization level such as {@code Q6_K}; LM Studio only honours this for
 *                     Hugging Face links, not for catalog identifiers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadModelRequest(@JsonProperty("model") @NotBlank String model,
		@JsonProperty("quantization") String quantization) {
}
