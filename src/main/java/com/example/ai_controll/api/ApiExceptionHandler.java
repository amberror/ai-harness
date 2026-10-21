package com.example.ai_controll.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.ai_controll.lmstudio.LmStudioException;

/**
 * Translates harness exceptions into API responses.
 *
 * <p>Failures from the model server are reported with their original detail rather than collapsed
 * into a generic message: "why did the model server say no" is precisely the sort of question this
 * project exists to answer, so swallowing it would defeat the purpose.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(LmStudioException.class)
	public ResponseEntity<Map<String, Object>> handleLmStudio(LmStudioException ex) {
		// A server we cannot reach is a dependency failure, not a bad request from our caller.
		HttpStatus status = ex.isUnreachable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
		log.warn("LM Studio call failed: {}", ex.getMessage());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now());
		body.put("error", ex.isUnreachable() ? "lm_studio_unreachable" : "lm_studio_error");
		body.put("message", ex.getMessage());
		if (ex.getStatusCode() != null) {
			body.put("upstreamStatus", ex.getStatusCode().value());
		}
		if (ex.getError() != null) {
			body.put("upstreamError", ex.getError());
		}
		if (ex.getRawBody() != null && !ex.getRawBody().isBlank()) {
			body.put("upstreamBody", ex.getRawBody());
		}
		return ResponseEntity.status(status).body(body);
	}

	/**
	 * Bad input from the caller: an unknown id, a fork position with nothing recorded at it, a
	 * scenario with no assertions.
	 *
	 * <p>Mapped explicitly because the alternative is a bare 500, which tells the caller only that
	 * something went wrong on the server -- when in fact the request was answerable and the message
	 * says exactly what to change.
	 */
	@ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
	public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now());
		body.put("error", "bad_request");
		body.put("message", ex.getMessage());
		return ResponseEntity.badRequest().body(body);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, Object> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now());
		body.put("error", "validation_failed");
		body.put("fields", fields);
		return ResponseEntity.badRequest().body(body);
	}
}
