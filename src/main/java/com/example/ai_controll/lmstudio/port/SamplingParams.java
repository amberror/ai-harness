package com.example.ai_controll.lmstudio.port;

/**
 * Decoding parameters. Every field is nullable so that unset values are omitted from the request and
 * the model server applies its own defaults, rather than the harness silently imposing its opinion.
 *
 * <p>{@code topK}, {@code minP} and {@code repeatPenalty} are LM Studio extensions to the
 * OpenAI-compatible endpoint rather than part of the OpenAI API itself.
 *
 * <p>{@code seed} is what turns repetition into a controlled experiment. Temperature zero is not by
 * itself reproducible -- ties are broken by whatever the sampler does next, and batching and cache
 * reuse move that around -- so two identical runs can still diverge. Pinning the seed separates
 * variance the model contributes from variance the harness does, which is the difference between a
 * flaky result and an informative one.
 */
public record SamplingParams(Double temperature, Double topP, Integer topK, Double minP,
		Double repeatPenalty, Integer seed) {

	public static final SamplingParams NONE =
			new SamplingParams(null, null, null, null, null, null);

	/**
	 * Temperature zero. The natural starting point for harness experiments, because run-to-run
	 * variance otherwise makes it impossible to tell a prompt change from noise.
	 */
	public static SamplingParams deterministic() {
		return new SamplingParams(0.0d, null, null, null, null, null);
	}

	public static SamplingParams temperature(double temperature) {
		return new SamplingParams(temperature, null, null, null, null, null);
	}

	/** The same decoding parameters with a fixed seed, so repetitions can be compared honestly. */
	public SamplingParams withSeed(Integer newSeed) {
		return new SamplingParams(temperature, topP, topK, minP, repeatPenalty, newSeed);
	}
}
