package com.example.ai_controll.eval.stats;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind "this pass rate does not support that conclusion".
 */
class WilsonTest {

	private static final Offset<Double> TOLERANCE = Offset.offset(0.01d);

	@Test
	@DisplayName("a perfect small batch is not reported as certainty")
	void perfectScoreIsStillUncertain() {
		// The reason for Wilson rather than the textbook normal approximation, which returns
		// [1.0, 1.0] here -- asserting certainty from five observations.
		Wilson.Interval interval = Wilson.interval(5, 5);

		assertThat(interval.rate()).isEqualTo(1.0d);
		assertThat(interval.low()).isCloseTo(0.566d, TOLERANCE);
		assertThat(interval.high()).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("four out of five is consistent with a very wide range")
	void smallSamplesAreWide() {
		Wilson.Interval interval = Wilson.interval(4, 5);

		assertThat(interval.rate()).isEqualTo(0.8d);
		assertThat(interval.low()).isCloseTo(0.376d, TOLERANCE);
		assertThat(interval.high()).isCloseTo(0.964d, TOLERANCE);
	}

	@Test
	@DisplayName("more repetitions narrow the interval")
	void moreDataNarrowsIt() {
		double few = Wilson.interval(8, 10).width();
		double many = Wilson.interval(80, 100).width();

		// The whole practical point: if a difference matters, run more repetitions.
		assertThat(many).isLessThan(few);
	}

	@Test
	@DisplayName("100% and 80% over five runs cannot be told apart")
	void overlapAtSmallSampleSizes() {
		// Exactly the comparison someone is about to report as a finding.
		assertThat(Wilson.interval(5, 5).overlaps(Wilson.interval(4, 5))).isTrue();
	}

	@Test
	@DisplayName("a large enough difference does separate")
	void separatesWhenTheDifferenceIsReal() {
		assertThat(Wilson.interval(100, 100).overlaps(Wilson.interval(20, 100))).isFalse();
	}

	@Test
	@DisplayName("nothing observed claims nothing")
	void handlesAnEmptyBatch() {
		Wilson.Interval interval = Wilson.interval(0, 0);

		assertThat(interval.rate()).isZero();
		assertThat(interval.low()).isZero();
		assertThat(interval.high()).isEqualTo(1.0d);
	}

	@Test
	@DisplayName("bounds stay inside zero and one")
	void staysWithinBounds() {
		assertThat(Wilson.interval(0, 3).low()).isGreaterThanOrEqualTo(0d);
		assertThat(Wilson.interval(3, 3).high()).isLessThanOrEqualTo(1d);
	}
}
