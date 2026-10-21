package com.example.ai_controll.eval.stats;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.eval.assertion.AssertionKind;
import com.example.ai_controll.eval.assertion.AssertionVerdict;
import com.example.ai_controll.eval.store.EvalBatchEntity;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultEntity;
import com.example.ai_controll.eval.store.EvalResultRepository;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading a batch properly: not just how often it passed, but which check failed and whether the
 * number is worth anything at this sample size.
 */
class BatchStatisticsTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private EvalBatchRepository batches;

	private EvalResultRepository results;

	private BatchStatistics statistics;

	@BeforeEach
	void setUp() {
		this.batches = mock(EvalBatchRepository.class);
		this.results = mock(EvalResultRepository.class);
		this.statistics = new BatchStatistics(batches, results, mapper);
	}

	private EvalBatchEntity batch(UUID id, String label, String variantJson, int passed, int total) {
		EvalBatchEntity entity = new EvalBatchEntity(id, UUID.randomUUID(), label, variantJson, total,
				Instant.now());
		for (int i = 0; i < total; i++) {
			entity.recordResult(i < passed, 10, 20);
		}
		return entity;
	}

	private EvalResultEntity result(UUID batchId, int repetition, String status,
			AssertionVerdict... verdicts) {
		return new EvalResultEntity(UUID.randomUUID(), batchId, UUID.randomUUID(), repetition,
				java.util.Arrays.stream(verdicts).allMatch(AssertionVerdict::passed), status,
				mapper.writeValueAsString(List.of(verdicts)), 100L, 10, 20, Instant.now());
	}

	private AssertionVerdict verdict(String description, boolean passed) {
		return new AssertionVerdict(description, AssertionKind.STATUS, passed,
				passed ? "held" : "did not hold", true);
	}

	private void given(UUID batchId, EvalBatchEntity entity, List<EvalResultEntity> rows) {
		when(batches.findById(batchId)).thenReturn(Optional.of(entity));
		when(results.findByBatchIdOrderByRepetitionAsc(batchId)).thenReturn(rows);
	}

	@Test
	@DisplayName("each check gets its own rate, so a failure says which one failed")
	void breaksDownByAssertion() {
		UUID id = UUID.randomUUID();
		given(id, batch(id, "baseline", null, 1, 3), List.of(
				result(id, 1, "FINISHED", verdict("called calc_eval", true),
						verdict("output contains 42", true)),
				result(id, 2, "FINISHED", verdict("called calc_eval", true),
						verdict("output contains 42", false)),
				result(id, 3, "FINISHED", verdict("called calc_eval", true),
						verdict("output contains 42", false))));

		BatchStatistics.Summary summary = statistics.summarise(id);

		// The batch rate alone would say "33%" and leave open whether the model failed to use the
		// tool or used it and then got the answer wrong. These call for opposite responses.
		assertThat(summary.assertions()).hasSize(2);
		assertThat(summary.assertions().getFirst().description()).isEqualTo("output contains 42");
		assertThat(summary.assertions().getFirst().passes()).isEqualTo(1);
		assertThat(summary.assertions().getLast().passes()).isEqualTo(3);
	}

	@Test
	@DisplayName("the weakest check is reported first, with the reason it failed")
	void surfacesTheFirstFailure() {
		UUID id = UUID.randomUUID();
		given(id, batch(id, "baseline", null, 0, 1), List.of(
				result(id, 1, "ABORTED_LOOP", verdict("status is FINISHED", false))));

		BatchStatistics.Summary summary = statistics.summarise(id);

		assertThat(summary.assertions().getFirst().firstFailureDetail()).isEqualTo("did not hold");
		assertThat(summary.statusCounts()).containsEntry("ABORTED_LOOP", 1);
	}

	@Test
	@DisplayName("a judged assertion is flagged, because it is not reproducible")
	void flagsNonReproducibleChecks() {
		UUID id = UUID.randomUUID();
		given(id, batch(id, "baseline", null, 1, 1), List.of(
				result(id, 1, "FINISHED",
						new AssertionVerdict("a model judged: is it polite", AssertionKind.JUDGE, true,
								"PASS", false))));

		// Otherwise a pass rate that moves between batches gets blamed on the model when part of it
		// belongs to the instrument.
		assertThat(statistics.summarise(id).anyNonReproducible()).isTrue();
	}

	@Test
	@DisplayName("two small batches that differ by one run are reported as indistinguishable")
	void refusesToSeparateNoise() {
		UUID left = UUID.randomUUID();
		UUID right = UUID.randomUUID();
		given(left, batch(left, "baseline", "{\"temperature\":0.0}", 5, 5), List.of(
				result(left, 1, "FINISHED", verdict("status is FINISHED", true))));
		given(right, batch(right, "warmer", "{\"temperature\":0.9}", 4, 5), List.of(
				result(right, 1, "FINISHED", verdict("status is FINISHED", true))));

		BatchStatistics.Comparison comparison = statistics.compare(left, right);

		// 100% against 80% over five repetitions is the comparison someone is about to report as a
		// finding. It is not one.
		assertThat(comparison.separable()).isFalse();
	}

	@Test
	@DisplayName("what actually differed is computed, not taken from the labels")
	void computesTheVariantDifference() {
		UUID left = UUID.randomUUID();
		UUID right = UUID.randomUUID();
		given(left, batch(left, "baseline", "{\"temperature\":0.0}", 5, 5), List.of());
		given(right, batch(right, "temperature 0.9", "{\"temperature\":0.9,\"maxSteps\":20}", 1, 5),
				List.of());

		BatchStatistics.Comparison comparison = statistics.compare(left, right);

		// A batch named for one change that made two is measuring both at once, and its result cannot
		// be attributed to either. The label would never have said so.
		assertThat(comparison.variantDiff()).hasSize(2);
		assertThat(comparison.variantDiff()).anyMatch(line -> line.startsWith("maxSteps"));
	}

	@Test
	@DisplayName("an unreadable verdict does not take out the summary")
	void survivesUnreadableRows() {
		UUID id = UUID.randomUUID();
		EvalResultEntity broken = new EvalResultEntity(UUID.randomUUID(), id, null, 1, false,
				"FINISHED", "not json at all", 1L, 0, 0, Instant.now());
		given(id, batch(id, "baseline", null, 0, 1), List.of(broken));

		// A row written by an older shape is a reason to lose that row, not the batch.
		assertThat(statistics.summarise(id).assertions()).isEmpty();
	}
}
