package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/models/unload}. */
public record UnloadModelRequest(@JsonProperty("instance_id") @NotBlank String instanceId) {
}
