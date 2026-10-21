package com.example.ai_controll.conversation;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.tool.schema.ToolSchema;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estimator only has to be good enough to decide when to compact, and it is told the real answer
 * on every response. These tests are about that correction working, about it counting the right
 * things, and above all about it erring in the safe direction.
 *
 * <p>The numbers in {@link #convergesOnMeasuredTraffic()} are real: they were recorded from the
 * loaded model during a five-step tool run.
 */
class TokenEstimatorTest {

	private final TokenEstimator estimator = new TokenEstimator();

	private final JsonMapper mapper = JsonMapper.builder().build();

	private ToolSchema tool() {
		return new ToolSchema("calc_eval", "Evaluates arithmetic.",
				mapper.readTree("{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}}}"));
	}

	@Test
	@DisplayName("reasoning is not counted, because it is never sent")
	void ignoresReasoning() {
		// The one thing an estimate must not include. Reasoning can be many times the size of the
		// answer, and counting it would compact a conversation that comfortably fits.
		Message withoutReasoning = Message.assistant("short answer", null);
		Message withReasoning = Message.assistant("short answer", "a".repeat(5000));

		assertThat(estimator.charsOf(List.of(withReasoning), List.of()))
				.isEqualTo(estimator.charsOf(List.of(withoutReasoning), List.of()));
	}

	@Test
	@DisplayName("tool schemas count towards the prompt")
	void countsToolSchemas() {
		int withoutTools = estimator.estimate(List.of(Message.user("hi")), List.of());
		int withTools = estimator.estimate(List.of(Message.user("hi")), List.of(tool()));

		// A schema is sent on every single step, so leaving it out would understate every request by
		// a constant -- the most dangerous kind of error, because it never looks wrong.
		assertThat(withTools).isGreaterThan(withoutTools);
	}

	@Test
	@DisplayName("per-message overhead grows with the number of turns")
	void countsPerMessageOverhead() {
		Message one = Message.user("abcdefgh");
		int single = estimator.estimate(List.of(one), List.of());
		int split = estimator.estimate(List.of(Message.user("abcd"), Message.user("efgh")), List.of());

		// The same text in two turns costs more than in one: the template wraps each turn.
		assertThat(split).isGreaterThan(single);
	}

	@Test
	@DisplayName("the very first request of a process is a guess, and knows it")
	void startsCautious() {
		// Nothing has been measured yet, so this one cannot be guaranteed -- only biased towards the
		// safe side, because underestimating sends a request that overflows and gets truncated from
		// the front, while overestimating merely compacts a little early. Every request after it is
		// measured rather than guessed.
		assertThat(estimator.isCalibrated()).isFalse();
		assertThat(estimator.correction()).isEqualTo(TokenEstimator.UNCALIBRATED_SAFETY);
	}

	@Test
	@DisplayName("the first observation replaces the guess rather than blending with it")
	void firstObservationWins() {
		// 3600 characters is 1000 raw tokens before overhead; charged 2000 means a factor of two.
		estimator.calibrate(3600, 0, 0, 2000);

		assertThat(estimator.isCalibrated()).isTrue();
		assertThat(estimator.correction()).isCloseTo(2.0d, Offset.offset(0.05d));
	}

	@Test
	@DisplayName("the correction tracks real traffic and stays on the safe side of it")
	void convergesOnMeasuredTraffic() {
		// Recorded from the loaded model: five steps of a tool chain, characters implied by the raw
		// estimate the harness made at the time, against the prompt_tokens the server charged.
		int[][] observed = {
				// {raw estimate, reported prompt tokens}
				{ 199, 401 }, { 280, 466 }, { 372, 536 }, { 469, 606 }, { 566, 674 },
		};
		boolean first = true;
		for (int[] step : observed) {
			int chars = (int) ((step[0] - 4 * 2 - 8) * TokenEstimator.CHARS_PER_TOKEN);
			int estimated = estimator.tokens(chars, 2, 1);
			if (!first) {
				// Once anything has been measured the estimate must never fall below the truth. An
				// estimate under the real figure is exactly what lets a request overflow the window and
				// be truncated from the front.
				assertThat(estimated).isGreaterThanOrEqualTo(step[1]);
			}
			estimator.calibrate(chars, 2, 1, step[1]);
			first = false;
		}

		// And it does not stay wildly inflated either, or every long conversation would be compacted
		// long before it needed to be.
		assertThat(estimator.correction()).isBetween(1.0d, 2.2d);
	}

	@Test
	@DisplayName("a nonsensical observation is ignored rather than believed")
	void ignoresImpossibleObservations() {
		estimator.calibrate(3600, 0, 0, 2000);
		double after = estimator.correction();

		estimator.calibrate(0, 1, 0, 100);
		estimator.calibrate(3600, 1, 0, 0);

		assertThat(estimator.correction()).isEqualTo(after);
	}

	@Test
	@DisplayName("the correction may grow freely but may not collapse")
	void clampsAsymmetrically() {
		for (int i = 0; i < 50; i++) {
			// A server reporting almost nothing for a large request, sustained. Believing it would
			// mean sending requests many times larger than the window.
			estimator.calibrate(36000, 0, 0, 1);
		}

		assertThat(estimator.correction()).isGreaterThanOrEqualTo(0.8d);
	}
}
