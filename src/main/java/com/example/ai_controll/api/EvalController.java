package com.example.ai_controll.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.eval.EvalBatchRunner;
import com.example.ai_controll.eval.ScenarioService;
import com.example.ai_controll.eval.store.EvalBatchEntity;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultRepository;
import com.example.ai_controll.eval.store.EvalScenarioRepository;
import com.example.ai_controll.eval.store.EvalResultEntity;
import com.example.ai_controll.eval.store.EvalScenarioEntity;

import tools.jackson.databind.JsonNode;

/**
 * Scenarios, batches and their results.
 *
 * <p>A batch is the unit of measurement rather than a run: for a 4B model a single passing run is an
 * anecdote, and the useful question is what share of repetitions worked.
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

	private final ScenarioService scenarios;

	private final EvalBatchRunner batchRunner;

	private final EvalBatchRepository batches;

	private final EvalResultRepository results;

	private final com.example.ai_controll.eval.stats.BatchStatistics statistics;

	public EvalController(ScenarioService scenarios, EvalBatchRunner batchRunner,
			EvalBatchRepository batches, EvalResultRepository results,
			com.example.ai_controll.eval.stats.BatchStatistics statistics) {
		this.scenarios = scenarios;
		this.batchRunner = batchRunner;
		this.batches = batches;
		this.results = results;
		this.statistics = statistics;
	}

	// -------------------------------------------------------------------------------------------
	// Scenarios
	// -------------------------------------------------------------------------------------------

	/**
	 * The scenarios, with their ids.
	 *
	 * <p>Returns the view rather than the definition because the id is what a caller needs next --
	 * starting a batch, exporting, deleting -- and a list you cannot act on is not much of a list.
	 * The full definition is available per scenario and through export.
	 */
	@GetMapping("/scenarios")
	public List<ScenarioView> listScenarios() {
		return scenarios.list().stream().map(ScenarioView::from).toList();
	}

	@GetMapping("/scenarios/{id}")
	public ResponseEntity<ScenarioService.ScenarioDefinition> getScenario(@PathVariable UUID id) {
		return scenarios.find(id).map(scenarios::toDefinition).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/scenarios")
	public ScenarioView saveScenario(@RequestBody ScenarioService.ScenarioDefinition definition) {
		return ScenarioView.from(scenarios.save(definition));
	}

	@DeleteMapping("/scenarios/{id}")
	public ResponseEntity<Void> deleteScenario(@PathVariable UUID id) {
		scenarios.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** Everything as one YAML document, for committing alongside the code it measures. */
	@GetMapping(value = "/scenarios/export", produces = MediaType.TEXT_PLAIN_VALUE)
	public String exportScenarios() {
		return scenarios.exportAll();
	}

	@GetMapping(value = "/scenarios/{id}/export", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> exportScenario(@PathVariable UUID id) {
		return scenarios.find(id).map(scenarios::toYaml).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Accepts one scenario or a list of them; both shapes are natural to write. */
	@PostMapping(value = "/scenarios/import", consumes = MediaType.TEXT_PLAIN_VALUE)
	public List<ScenarioView> importScenarios(@RequestBody String yaml) {
		return scenarios.importYaml(yaml).stream().map(ScenarioView::from).toList();
	}

	// -------------------------------------------------------------------------------------------
	// Batches
	// -------------------------------------------------------------------------------------------

	/**
	 * Runs a scenario N times, optionally with overrides.
	 *
	 * <p>The variant is what turns this into an experiment: hold the scenario fixed, change one
	 * thing, and compare pass rates. Any field a run accepts can be overridden — the system prompt,
	 * the temperature, the repair policy, the tools on offer.
	 */
	@PostMapping("/batches")
	public ResponseEntity<BatchView> startBatch(@RequestBody StartBatchRequest request) {
		UUID batchId = batchRunner.start(request.scenarioId(), request.label(), request.variant(),
				request.repetitions() == null ? 5 : request.repetitions());
		return ResponseEntity.accepted()
				.body(batches.findById(batchId).map(BatchView::from).orElse(null));
	}

	@GetMapping("/batches")
	public List<BatchView> listBatches(@RequestParam(defaultValue = "50") int limit) {
		return batches.findAllByOrderByStartedAtDesc(Limit.of(Math.clamp(limit, 1, 200))).stream()
				.map(BatchView::from).toList();
	}

	@GetMapping("/batches/{id}")
	public ResponseEntity<BatchDetail> getBatch(@PathVariable UUID id) {
		return batches.findById(id)
				.map(batch -> new BatchDetail(BatchView.from(batch),
						results.findByBatchIdOrderByRepetitionAsc(id).stream().map(ResultView::from)
								.toList()))
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * The batch read properly: its pass rate with a confidence interval, and each assertion's own
	 * rate. A bare pass rate over a handful of repetitions supports far less than it appears to, and
	 * collapses every check into one bit so a failure never says which check failed.
	 */
	@GetMapping("/batches/{id}/stats")
	public ResponseEntity<com.example.ai_controll.eval.stats.BatchStatistics.Summary> batchStats(
			@PathVariable UUID id) {
		return ResponseEntity.ok(statistics.summarise(id));
	}

	/** Two batches side by side, with the settings that actually differed computed rather than named. */
	@GetMapping("/batches/compare")
	public com.example.ai_controll.eval.stats.BatchStatistics.Comparison compareBatches(
			@RequestParam UUID a, @RequestParam UUID b) {
		return statistics.compare(a, b);
	}

	/**
	 * Deletes a batch and its results.
	 *
	 * <p>The runs behind it are left alone: they are evidence in their own right, and a batch started
	 * with the wrong variant does not make the runs it produced worthless.
	 */
	@DeleteMapping("/batches/{id}")
	public ResponseEntity<Void> deleteBatch(@PathVariable UUID id) {
		if (!batches.existsById(id)) {
			return ResponseEntity.notFound().build();
		}
		// eval_result cascades from eval_batch in the schema, so removing the batch is enough.
		batches.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/** Stops a batch, along with the repetition it is currently waiting on. */
	@PostMapping("/batches/{id}/cancel")
	public ResponseEntity<Map<String, Object>> cancelBatch(@PathVariable UUID id) {
		boolean stopped = batchRunner.cancel(id);
		return stopped
				? ResponseEntity.ok(Map.of("batchId", id, "cancelled", true))
				: ResponseEntity.status(409).body(Map.of("batchId", id, "cancelled", false,
						"message", "That batch had already finished."));
	}

	/** Every batch for one scenario: the comparison across variants. */
	@GetMapping("/scenarios/{id}/batches")
	public List<BatchView> batchesForScenario(@PathVariable UUID id) {
		return batches.findByScenarioIdOrderByStartedAtDesc(id).stream().map(BatchView::from).toList();
	}

	// -------------------------------------------------------------------------------------------
	// Views
	// -------------------------------------------------------------------------------------------

	public record StartBatchRequest(UUID scenarioId, String label, JsonNode variant,
			Integer repetitions) {
	}

	public record ScenarioView(UUID id, String name, String description, boolean enabled,
			Instant updatedAt) {

		static ScenarioView from(EvalScenarioEntity entity) {
			return new ScenarioView(entity.getId(), entity.getName(), entity.getDescription(),
					entity.isEnabled(), entity.getUpdatedAt());
		}
	}

	/**
	 * @param passRate share of completed repetitions that passed. The single number a batch exists to
	 *                 produce, and the only honest summary of a model that is right most of the time
	 */
	public record BatchView(UUID id, UUID scenarioId, String label, int repetitions, String status,
			int completed, int passed, double passRate, int inputTokens, int outputTokens,
			String error, Instant startedAt, Instant endedAt) {

		static BatchView from(EvalBatchEntity batch) {
			return new BatchView(batch.getId(), batch.getScenarioId(), batch.getLabel(),
					batch.getRepetitions(), batch.getStatus(), batch.getCompletedCount(),
					batch.getPassedCount(), batch.passRate(), batch.getTotalInputTokens(),
					batch.getTotalOutputTokens(), batch.getError(), batch.getStartedAt(),
					batch.getEndedAt());
		}
	}

	/** @param runId the run behind this repetition, so a failure can be opened and read */
	public record ResultView(UUID id, UUID runId, int repetition, boolean passed, String runStatus,
			String assertionsJson, Long durationMs, int inputTokens, int outputTokens) {

		static ResultView from(EvalResultEntity entity) {
			return new ResultView(entity.getId(), entity.getRunId(), entity.getRepetition(),
					entity.isPassed(), entity.getRunStatus(), entity.getAssertionsJson(),
					entity.getDurationMs(), entity.getInputTokens(), entity.getOutputTokens());
		}
	}

	public record BatchDetail(BatchView batch, List<ResultView> results) {
	}
}
