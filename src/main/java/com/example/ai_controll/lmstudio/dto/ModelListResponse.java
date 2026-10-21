package com.example.ai_controll.lmstudio.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Envelope returned by {@code GET /api/v1/models}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelListResponse(@JsonProperty("models") List<ModelInfo> models) {

	public ModelListResponse {
		models = models == null ? List.of() : List.copyOf(models);
	}
}
