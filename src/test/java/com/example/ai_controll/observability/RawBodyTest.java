package com.example.ai_controll.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness records exact bytes as evidence, so where that evidence is cut short it has to say so.
 * Silently shortening a recording is worse than not keeping one.
 */
class RawBodyTest {

	@Test
	@DisplayName("a body within the cap is returned untouched")
	void leavesShortBodyAlone() {
		String body = "{\"model\":\"qwen-4b-6q-unc\"}";

		assertThat(RawBody.cap(body, 1000)).isEqualTo(body);
		// Exactly at the cap still counts as fitting.
		assertThat(RawBody.cap(body, body.length())).isEqualTo(body);
	}

	@Test
	@DisplayName("an oversized body keeps its head and states what was dropped")
	void capsLongBodyWithExplicitMarker() {
		String body = "x".repeat(5000);

		String capped = RawBody.cap(body, 100);

		assertThat(capped).startsWith("x".repeat(100));
		assertThat(capped).contains("truncated by the harness");
		// The numbers matter: a reader has to be able to tell how much of the evidence is missing.
		// Formatted with Locale.ROOT, so this reads the same on every machine -- the platform default
		// would render the grouping separator differently and make the recording machine-dependent.
		assertThat(capped).contains("4,900").contains("5,000");
		assertThat(capped.length()).isLessThan(300);
	}

	@Test
	@DisplayName("null and non-positive caps are handled")
	void handlesEdgeCases() {
		assertThat(RawBody.cap(null, 100)).isNull();
		// A cap of zero or less disables capping rather than discarding the whole body, so a
		// misconfiguration loses no evidence.
		assertThat(RawBody.cap("abc", 0)).isEqualTo("abc");
		assertThat(RawBody.cap("abc", -1)).isEqualTo("abc");
	}

	@Test
	@DisplayName("the default cap keeps a realistic streamed response manageable")
	void defaultCapBoundsARealStream() {
		// A measured case: 2,758 output tokens produced roughly 820 KB of Server-Sent Event framing.
		// Stored unbounded, one such call would dwarf everything else in the journal.
		String simulatedStream = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n".repeat(18_000);
		assertThat(simulatedStream.length()).isGreaterThan(700_000);

		String capped = RawBody.cap(simulatedStream, 32_768);

		assertThat(capped.length()).isLessThan(33_000);
		assertThat(capped).contains("truncated by the harness");
	}
}
