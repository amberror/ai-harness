package com.example.ai_controll.observability;

import java.util.Locale;

/**
 * Bounds how much of a raw request or response body is retained.
 *
 * <p>The harness records exact bytes because a reconstruction is not evidence. That principle has a
 * limit, and streaming is where it bites: a streamed response is not one JSON document but thousands
 * of Server-Sent Event envelopes, one per token. A single 2,758-token answer measured 820 KB of raw
 * stream, almost all of it repeated protocol framing around a few characters of payload. Storing
 * that for every call would make the journal unusable long before it became useful.
 *
 * <p>So bodies are capped, and a cap is always announced in the retained text. A reader can tell the
 * difference between "this is what was sent" and "this is the first part of what was sent"; silently
 * shortening the evidence would be worse than not keeping it.
 */
public final class RawBody {

	private RawBody() {
	}

	/**
	 * Returns the body unchanged if it fits, otherwise its first {@code maxChars} characters followed
	 * by an explicit marker naming how much was dropped.
	 */
	public static String cap(String body, int maxChars) {
		if (body == null || maxChars <= 0 || body.length() <= maxChars) {
			return body;
		}
		int dropped = body.length() - maxChars;
		// Locale.ROOT, not the platform default: this marker becomes part of a recorded artefact that
		// is searched, compared and asserted on, so it has to read identically on every machine. The
		// default locale renders the grouping separator differently from one developer to the next.
		return body.substring(0, maxChars) + String.format(Locale.ROOT,
				"%n%n... [truncated by the harness: %,d of %,d characters omitted] ...",
				dropped, body.length());
	}
}
