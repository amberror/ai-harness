package com.example.ai_controll.eval.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.ai_controll.eval.assertion.AssertionVerdict;
import com.example.ai_controll.eval.store.EvalBatchEntity;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultEntity;
import com.example.ai_controll.eval.store.EvalResultRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a batch's stored results into something a conclusion can rest on.
 *
 * <p>Two things are missing from a bare pass rate, and both are here.
 *
 * <p>The first is <strong>how much the rate is worth</strong>. Four passes out of five reads as 80%,
 * but five observations are consistent with a true rate anywhere from roughly 38% to 96%. Without
 * that range, two variants that differ only by chance look like a finding.
 *
 * <p>The second is <strong>which check failed</strong>. A batch pass rate collapses every assertion
 * into one bit, so "60%" could mean the model got the arithmetic wrong four times out of ten or that
 * it got the arithmetic right every time and merely exceeded the step limit. Those call for opposite
 * responses, and the verdicts needed to tell them apart are already stored -- they were simply never
 * read back.
 */
@Service
public class BatchStatistics {

	private final EvalBatchRepository batches;

	private final EvalResultRepository results;

	private final ObjectMapper objectMapper;

	public BatchStatistics(EvalBatchRepository batches, EvalResultRepository results,
			ObjectMapper objectMapper) {
		this.batches = batches;
		this.results = results;
		this.objectMapper = objectMapper;
	}

	public Summary summarise(UUID batchId) {
		EvalBatchEntity batch = this.batches.findById(batchId)
				.orElseThrow(() -> new IllegalArgumentException("No batch " + batchId));
		return summarise(batch, this.results.findByBatchIdOrderByRepetitionAsc(batchId));
	}

	public Summary summarise(EvalBatchEntity batch, List<EvalResultEntity> batchResults) {
		Map<String, Tally> perAssertion = new LinkedHashMap<>();
		Map<String, Integer> perStatus = new LinkedHashMap<>();
		boolean anyNonReproducible = false;

		for (EvalResultEntity result : batchResults) {
			perStatus.merge(result.getRunStatus() == null ? "UNKNOWN" : result.getRunStatus(), 1,
					Integer::sum);
			for (AssertionVerdict verdict : readVerdicts(result)) {
				Tally tally = perAssertion.computeIfAbsent(verdict.description(),
						key -> new Tally(verdict.reproducible()));
				tally.record(verdict);
				anyNonReproducible |= !verdict.reproducible();
			}
		}

		List<AssertionRate> assertionRates = new ArrayList<>();
		perAssertion.forEach((description, tally) -> assertionRates.add(
				new AssertionRate(description, tally.passes, tally.total,
						Wilson.interval(tally.passes, tally.total), tally.reproducible,
						tally.firstFailureDetail)));
		// Weakest first: the check that fails most is the one worth reading.
		assertionRates.sort((a, b) -> Double.compare(a.interval().rate(), b.interval().rate()));

		return new Summary(batch.getId(), batch.getLabel(),
				Wilson.interval(batch.getPassedCount(), batch.getCompletedCount()), assertionRates,
				perStatus, anyNonReproducible);
	}

	/**
	 * Compares two batches.
	 *
	 * <p>The verdict deliberately stops short of declaring a winner when the intervals overlap.
	 * Overlap does not prove the variants are equivalent, but it does mean these runs cannot tell
	 * them apart, and saying so is more useful than a difference someone will go on to quote.
	 */
	public Comparison compare(UUID leftId, UUID rightId) {
		Summary left = summarise(leftId);
		Summary right = summarise(rightId);

		Map<String, AssertionDelta> deltas = new LinkedHashMap<>();
		left.assertions().forEach(rate -> deltas.put(rate.description(),
				new AssertionDelta(rate.description(), rate.interval().rate(), null)));
		right.assertions().forEach(rate -> deltas.merge(rate.description(),
				new AssertionDelta(rate.description(), null, rate.interval().rate()),
				(existing, added) -> new AssertionDelta(existing.description(), existing.leftRate(),
						added.rightRate())));

		List<AssertionDelta> ordered = new ArrayList<>(deltas.values());
		// Biggest movement first: what changed is the point of the comparison.
		ordered.sort((a, b) -> Double.compare(Math.abs(b.change()), Math.abs(a.change())));

		boolean separable = !left.passRate().overlaps(right.passRate());
		return new Comparison(left, right, ordered, separable,
				variantDiff(leftId, rightId));
	}

	/**
	 * What actually differs between two variants, computed rather than taken from the labels.
	 *
	 * <p>A label is a promise; this is the evidence. "temperature 0.9" as a name means nothing if the
	 * variant also changed the tool list, and an experiment that varied two things at once is worth
	 * knowing about before its result is believed.
	 */
	private List<String> variantDiff(UUID leftId, UUID rightId) {
		JsonNode left = variantOf(leftId);
		JsonNode right = variantOf(rightId);

		List<String> differences = new ArrayList<>();
		List<String> fields = new ArrayList<>();
		left.propertyNames().forEach(fields::add);
		right.propertyNames().forEach(name -> {
			if (!fields.contains(name)) {
				fields.add(name);
			}
		});

		for (String field : fields) {
			JsonNode a = left.get(field);
			JsonNode b = right.get(field);
			if (a == null || b == null || !a.equals(b)) {
				differences.add("%s: %s -> %s".formatted(field,
						a == null ? "(scenario default)" : a, b == null ? "(scenario default)" : b));
			}
		}
		return differences;
	}

	private JsonNode variantOf(UUID batchId) {
		return this.batches.findById(batchId)
				.map(EvalBatchEntity::getVariantJson)
				.filter(json -> json != null && !json.isBlank())
				.map(json -> {
					try {
						return this.objectMapper.readTree(json);
					}
					catch (RuntimeException ex) {
						return (JsonNode) this.objectMapper.createObjectNode();
					}
				})
				.orElseGet(this.objectMapper::createObjectNode);
	}

	private List<AssertionVerdict> readVerdicts(EvalResultEntity result) {
		if (result.getAssertionsJson() == null || result.getAssertionsJson().isBlank()) {
			return List.of();
		}
		try {
			return List.of(this.objectMapper.readValue(result.getAssertionsJson(),
					AssertionVerdict[].class));
		}
		catch (RuntimeException ex) {
			// A result written by an older shape should not take out the whole summary.
			return List.of();
		}
	}

	private static final class Tally {

		private final boolean reproducible;

		private int passes;

		private int total;

		private String firstFailureDetail;

		Tally(boolean reproducible) {
			this.reproducible = reproducible;
		}

		void record(AssertionVerdict verdict) {
			this.total++;
			if (verdict.passed()) {
				this.passes++;
			}
			else if (this.firstFailureDetail == null) {
				this.firstFailureDetail = verdict.detail();
			}
		}
	}

	/**
	 * A batch, read properly.
	 *
	 * @param anyNonReproducible true when a judge was involved, so a moving rate might be the
	 *                           instrument rather than the model
	 */
	public record Summary(UUID batchId, String label, Wilson.Interval passRate,
			List<AssertionRate> assertions, Map<String, Integer> statusCounts,
			boolean anyNonReproducible) {
	}

	/** How often one check held, across the batch. */
	public record AssertionRate(String description, int passes, int total, Wilson.Interval interval,
			boolean reproducible, String firstFailureDetail) {
	}

	/** One check's rate in each of two batches. */
	public record AssertionDelta(String description, Double leftRate, Double rightRate) {

		public double change() {
			return (rightRate == null ? 0d : rightRate) - (leftRate == null ? 0d : leftRate);
		}
	}

	/**
	 * @param separable  whether the two pass rates are far enough apart to be told apart at all
	 * @param variantDiff which settings actually differed, computed from the stored variants
	 */
	public record Comparison(Summary left, Summary right, List<AssertionDelta> assertionDeltas,
			boolean separable, List<String> variantDiff) {
	}
}
