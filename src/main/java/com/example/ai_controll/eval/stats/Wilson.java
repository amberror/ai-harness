package com.example.ai_controll.eval.stats;

/**
 * A confidence interval for a pass rate.
 *
 * <p>This exists because a bare pass rate over a handful of repetitions invites a conclusion it
 * cannot support. Five runs, four passes, is "80%" -- but the true rate could comfortably be
 * anywhere from about 38% to 96%, so the difference between that and a 5/5 variant is not evidence
 * of anything. Reporting the interval alongside the rate turns an anecdote back into a measurement,
 * and makes the sample size visible in the number itself rather than in a column beside it.
 *
 * <p>Wilson rather than the textbook normal approximation, because the normal one is at its worst
 * exactly where a harness lives: small samples and rates near 0 or 1. At 5/5 it produces the
 * interval [1.0, 1.0], which asserts certainty from five observations.
 */
public final class Wilson {

	/** 1.96 standard deviations: the conventional 95% two-sided interval. */
	public static final double Z_95 = 1.959963985d;

	private Wilson() {
	}

	public static Interval interval(int passes, int total) {
		return interval(passes, total, Z_95);
	}

	public static Interval interval(int passes, int total, double z) {
		if (total <= 0) {
			// Nothing observed: the rate could be anything, and saying so is the honest answer.
			return new Interval(0d, 0d, 1d, 0);
		}
		double n = total;
		double observed = passes / n;
		double z2 = z * z;
		double denominator = 1 + (z2 / n);
		double centre = observed + (z2 / (2 * n));
		double spread = z * Math.sqrt((observed * (1 - observed) / n) + (z2 / (4 * n * n)));

		double low = (centre - spread) / denominator;
		double high = (centre + spread) / denominator;
		return new Interval(observed, Math.max(0d, low), Math.min(1d, high), total);
	}

	/**
	 * A rate with the range it is actually consistent with.
	 *
	 * @param rate     the observed proportion
	 * @param low      lower bound of the 95% interval
	 * @param high     upper bound
	 * @param total    how many observations it rests on
	 */
	public record Interval(double rate, double low, double high, int total) {

		/**
		 * Whether two intervals overlap, which is the question anyone comparing variants is really
		 * asking. Overlapping intervals do not prove the variants are equivalent, but they do mean the
		 * data cannot tell them apart -- which is the useful warning to put in front of someone about
		 * to report a difference.
		 */
		public boolean overlaps(Interval other) {
			return other != null && this.low <= other.high && other.low <= this.high;
		}

		/** How wide the interval is; a quick read on whether the batch was big enough. */
		public double width() {
			return this.high - this.low;
		}

		/** Formatted as a percentage range, for the console. */
		public String asPercentRange() {
			return "%.0f%%–%.0f%%".formatted(this.low * 100, this.high * 100);
		}
	}
}
