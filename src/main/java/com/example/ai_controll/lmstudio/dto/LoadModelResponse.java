package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response from {@code POST /api/v1/models/load}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoadModelResponse(@JsonProperty("type") String type,
		@JsonProperty("instance_id") String instanceId,
		@JsonProperty("load_time_seconds") Double loadTimeSeconds,
		@JsonProperty("status") String status,
		@JsonProperty("load_config") ModelInfo.InstanceConfig loadConfig) {
}
