package com.example.ai_controll.lmstudio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Error payload returned by LM Studio, in the shape {@code {"error": {...}}}.
 *
 * @param type    high-level category, e.g. {@code invalid_request} or {@code model_not_found}
 * @param message human-readable description
 * @param code    finer-grained code, e.g. {@code missing_required_parameter}
 * @param param   the offending request parameter, when applicable
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LmStudioError(@JsonProperty("type") String type,
		@JsonProperty("message") String message,
		@JsonProperty("code") String code,
		@JsonProperty("param") String param) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Envelope(@JsonProperty("error") LmStudioError error) {
	}
}
