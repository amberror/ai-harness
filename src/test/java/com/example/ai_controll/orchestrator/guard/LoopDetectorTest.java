package com.example.ai_controll.orchestrator.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repetition is the characteristic failure of a small model that has lost the thread: every call
 * succeeds at the transport level, so nothing looks wrong while the run burns its entire budget.
 */
class LoopDetectorTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private JsonNode args(String json) {
		return mapper.readTree(json);
	}

	@Test
	@DisplayName("repeats below the threshold are allowed")
	void allowsRepeatsBelowThreshold() {
		// A retry after a failure is ordinary behaviour and must not trip the guard.
		LoopDetector detector = new LoopDetector(3);

		assertThat(detector.record("get_weather", args("{\"city\":\"Kyiv\"}"))).isEmpty();
		assertThat(detector.record("get_weather", args("{\"city\":\"Kyiv\"}"))).isEmpty();
	}

	@Test
	@DisplayName("the threshold repeat is reported")
	void reportsAtThreshold() {
		LoopDetector detector = new LoopDetector(3);
		JsonNode arguments = args("{\"city\":\"Kyiv\"}");

		detector.record("get_weather", arguments);
		detector.record("get_weather", arguments);

		assertThat(detector.record("get_weather", arguments)).hasValueSatisfying(repetition -> {
			assertThat(repetition.count()).isEqualTo(3);
			assertThat(repetition.signature()).contains("get_weather").contains("Kyiv");
			// The nudge names the specific call, which is what gives the model something to act on.
			assertThat(repetition.nudge()).contains("get_weather").contains("3 times");
		});
	}

	@Test
	@DisplayName("the same arguments in a different key order count as the same call")
	void canonicalisesKeyOrder() {
		// They are the same call; only the serialisation differs. Comparing raw text would let a model
		// loop forever simply by reordering its own JSON.
		LoopDetector detector = new LoopDetector(2);

		detector.record("search", args("{\"a\":1,\"b\":2}"));

		assertThat(detector.record("search", args("{\"b\":2,\"a\":1}"))).isPresent();
	}

	@Test
	@DisplayName("different arguments are different calls")
	void distinguishesDifferentArguments() {
		LoopDetector detector = new LoopDetector(2);

		detector.record("get_weather", args("{\"city\":\"Kyiv\"}"));

		assertThat(detector.record("get_weather", args("{\"city\":\"Lviv\"}"))).isEmpty();
	}

	@Test
	@DisplayName("the same arguments to different tools are different calls")
	void distinguishesDifferentTools() {
		LoopDetector detector = new LoopDetector(2);

		detector.record("tool_a", args("{\"x\":1}"));

		assertThat(detector.record("tool_b", args("{\"x\":1}"))).isEmpty();
	}

	@Test
	@DisplayName("nested structures are canonicalised too")
	void canonicalisesNestedStructures() {
		LoopDetector detector = new LoopDetector(2);

		detector.record("f", args("{\"outer\":{\"a\":1,\"b\":[1,2]}}"));

		assertThat(detector.record("f", args("{\"outer\":{\"b\":[1,2],\"a\":1}}"))).isPresent();
	}

	@Test
	@DisplayName("a threshold below two is raised, so a first call can never trip the guard")
	void enforcesMinimumThreshold() {
		// A threshold of one would abort on the very first tool call, which would make the harness
		// useless rather than safe.
		LoopDetector detector = new LoopDetector(1);

		assertThat(detector.record("f", args("{}"))).isEmpty();
		assertThat(detector.record("f", args("{}"))).isPresent();
	}

	@Test
	@DisplayName("null arguments are handled")
	void handlesNullArguments() {
		LoopDetector detector = new LoopDetector(2);

		assertThat(detector.record("clock_now", null)).isEmpty();
		assertThat(detector.record("clock_now", null)).isPresent();
	}
}
