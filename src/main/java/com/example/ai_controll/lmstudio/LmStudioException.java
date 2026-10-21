package com.example.ai_controll.lmstudio;

import org.springframework.http.HttpStatusCode;

import com.example.ai_controll.lmstudio.dto.LmStudioError;

/**
 * Raised when a call to LM Studio fails.
 *
 * <p>The parsed {@link LmStudioError} is kept alongside the raw body: the harness reports failures
 * verbatim rather than collapsing them to a generic message, because "why did the model server say
 * no" is exactly the kind of question this project exists to answer.
 */
public class LmStudioException extends RuntimeException {

	private final HttpStatusCode statusCode;

	private final transient LmStudioError error;

	private final String rawBody;

	public LmStudioException(String message, HttpStatusCode statusCode, LmStudioError error, String rawBody,
			Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.error = error;
		this.rawBody = rawBody;
	}

	/** The server could not be reached at all, as opposed to answering with an error. */
	public static LmStudioException unreachable(String baseUrl, Throwable cause) {
		return new LmStudioException(
				"LM Studio is not reachable at %s. Is the server running? Try: lms server start"
						.formatted(baseUrl),
				null, null, null, cause);
	}

	/** HTTP status LM Studio replied with, or null when it could not be reached. */
	public HttpStatusCode getStatusCode() {
		return statusCode;
	}

	/** Parsed error payload, or null when the body was absent or unparseable. */
	public LmStudioError getError() {
		return error;
	}

	/** The response body exactly as received, for the run journal. */
	public String getRawBody() {
		return rawBody;
	}

	public boolean isUnreachable() {
		return statusCode == null;
	}
}
