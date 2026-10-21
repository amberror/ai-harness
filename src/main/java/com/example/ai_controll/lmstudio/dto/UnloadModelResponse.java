package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from {@code POST /api/v1/models/unload}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnloadModelResponse(@JsonProperty("instance_id") String instanceId) {
}
