package com.example.ai_controll.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ai_controll.api.view.RunViews;
import com.example.ai_controll.eval.assertion.AssertionEvaluator;
import com.example.ai_controll.eval.assertion.AssertionKind;
import com.example.ai_controll.eval.assertion.AssertionSpec;
import com.example.ai_controll.eval.assertion.AssertionVerdict;
import com.example.ai_controll.eval.assertion.JudgeAssertionEvaluator;
import com.example.ai_controll.eval.store.EvalBatchEntity;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultRepository;
import com.example.ai_controll.eval.store.EvalScenarioRepository;
import com.example.ai_controll.eval.store.EvalResultEntity;
import com.example.ai_controll.eval.store.EvalScenarioEntity;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.orchestrator.RunService;
import com.example.ai_controll.orchestrator.StartRunCommand;
import com.example.ai_controll.orchestrator.domain.RunStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a scenario N times and reports how often it worked.
 *
 * <p>This is the phase that earns the word "predictable". Everything before it makes the model's
 * behaviour bounded and legible; none of it answers <em>how often</em> the model does the right
 * thing, and for a 4B model that answer is not 100% even at temperature zero. A single passing run
 * is an anecdote, which is why the unit here is a batch.
 *
 * <p>Repetitions run one after another rather than in parallel. LM Studio serves a single model
 * instance, so overlapping requests would queue against each other and corrupt exactly the latency
 * and token figures the batch exists to collect.
 */
@Service
public class EvalBatchRunner {

	private static final Logger log = LoggerFactory.getLogger(EvalBatchRunner.class);

	private final RunService runService;

	private final EventJournal journal;

	private final AssertionEvaluator evaluator;

	private final JudgeAssertionEvaluator judge;

	private final EvalScenarioRepository scenarios;

	private final EvalBatchRepository batches;

	private final EvalResultRepository results;

	private final ExecutorService runExecutor;

	private final ObjectMapper objectMapper;

	/**
	 * Batches asked to stop, and the run each is currently waiting on.
	 *
	 * <p>A batch is the longest-running thing the harness does -- two hundred repetitions against a
	 * local model is an afternoon -- and it is also the easiest to start with the wrong variant. Being
	 * able to stop one without losing the repetitions already measured is the difference between an
	 * experiment and a commitment.
	 */
	private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

	private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();

	public EvalBatchRunner(RunService runService, EventJournal journal, AssertionEvaluator evaluator,
			JudgeAssertionEvaluator judge, EvalScenarioRepository scenarios,
			EvalBatchRepository batches, EvalResultRepository results,
			ExecutorService runExecutor, ObjectMapper objectMapper) {
		this.runService = runService;
		this.journal = journal;
		this.evaluator = evaluator;
		this.judge = judge;
		this.scenarios = scenarios;
		this.batches = batches;
		this.results = results;
		this.runExecutor = runExecutor;
		this.objectMapper = objectMapper;
	}

	/**
	 * Starts a batch in the background.
	 *
	 * @param variant overrides applied on top of the scenario. This is what makes it an experiment:
	 *                holding the scenario fixed and changing exactly one thing is the only way a
	 *                change in pass rate can be attributed to anything
	 * @return the batch id, immediately
	 */
	public UUID start(UUID scenarioId, String label, JsonNode variant, int repetitions) {
		EvalScenarioEntity scenario = scenarios.findById(scenarioId)
				.orElseThrow(() -> new IllegalArgumentException("No scenario " + scenarioId));

		// Enforced, not merely displayed. The console shows a scenario as disabled, and a flag that
		// says one thing while the scenario runs anyway is worse than having no flag at all.
		if (!scenario.isEnabled()) {
			throw new IllegalStateException(
					"Scenario '%s' is disabled. Enable it before running a batch.".formatted(
							scenario.getName()));
		}

		int reps = Math.clamp(repetitions, 1, 200);
		EvalBatchEntity batch = new EvalBatchEntity(UUID.randomUUID(), scenarioId,
				(label == null || label.isBlank()) ? "baseline" : label,
				variant == null ? null : variant.toString(), reps, Instant.now());
		batches.save(batch);

		runExecutor.submit(() -> execute(batch.getId(), scenario, variant, reps));
		return batch.getId();
	}

	/**
	 * Stops a batch.
	 *
	 * <p>The repetition in progress is stopped too, rather than left to finish. On a local model that
	 * one repetition is the several minutes the person pressing stop is trying not to spend, and its
	 * result would be discarded anyway.
	 *
	 * @return false when the batch had already ended
	 */
	public boolean cancel(UUID batchId) {
		boolean running = batches.findById(batchId)
				.filter(batch -> "RUNNING".equals(batch.getStatus()))
				.isPresent();
		if (!running) {
			return false;
		}
		this.cancelled.add(batchId);
		UUID current = this.inFlight.get(batchId);
		if (current != null) {
			runService.cancel(current);
		}
		return true;
	}

	private void execute(UUID batchId, EvalScenarioEntity scenario, JsonNode variant, int reps) {
		List<AssertionSpec> specs = readAssertions(scenario);
		log.info("Batch {} starting: {} x {} repetitions, {} assertions", batchId, scenario.getName(),
				reps, specs.size());

		try {
			for (int repetition = 1; repetition <= reps; repetition++) {
				if (this.cancelled.contains(batchId)) {
					// Whatever was measured before the stop stands; a partial batch is still evidence,
					// as long as its pass rate is read against the number of repetitions it actually ran.
					finish(batchId, "CANCELLED", "Stopped after %d of %d repetitions."
							.formatted(repetition - 1, reps));
					return;
				}
				runOnce(batchId, scenario, variant, specs, repetition);
			}
			finish(batchId, this.cancelled.contains(batchId) ? "CANCELLED" : "COMPLETED", null);
		}
		catch (RuntimeException ex) {
			log.warn("Batch {} failed", batchId, ex);
			finish(batchId, "FAILED", ex.getMessage());
		}
		finally {
			this.cancelled.remove(batchId);
			this.inFlight.remove(batchId);
		}
	}

	private void runOnce(UUID batchId, EvalScenarioEntity scenario, JsonNode variant,
			List<AssertionSpec> specs, int repetition) {

		Instant startedAt = Instant.now();
		StartRunCommand command = buildCommand(scenario, variant);

		UUID runId;
		try {
			// The id is taken before the run begins, so a stop can reach the repetition in progress
			// rather than having to wait it out.
			runId = runService.startAndAwait(command, started -> this.inFlight.put(batchId, started));
		}
		catch (RuntimeException ex) {
			// A repetition that could not start is a failure of the batch, not of the model, but it
			// still belongs in the record rather than being dropped.
			record(batchId, null, repetition, false, "COULD_NOT_START",
					List.of(new AssertionVerdict("the run could not be started", AssertionKind.STATUS,
							false, ex.getMessage(), true)),
					Duration.between(startedAt, Instant.now()).toMillis(), 0, 0);
			return;
		}

		RunViews.RunDetail detail = runService.detail(runId).orElse(null);
		if (detail == null) {
			record(batchId, runId, repetition, false, "MISSING", List.of(),
					Duration.between(startedAt, Instant.now()).toMillis(), 0, 0);
			return;
		}

		RunViews.RunSummary summary = detail.summary();
		AssertionEvaluator.RunEvidence evidence = AssertionEvaluator.RunEvidence.from(
				summary.status().name(), detail.finalOutput(), summary.stepsUsed(),
				summary.toolCallsTotal(), summary.toolCallsInvalid(),
				summary.inputTokens() + summary.outputTokens(), journal.eventsFor(runId));

		List<AssertionVerdict> verdicts = new ArrayList<>(evaluator.evaluate(specs, evidence));

		// Judge assertions run last and only when there is something to judge, so a run that never
		// produced an answer does not also spend tokens being told so.
		for (AssertionSpec spec : specs) {
			if (spec.kind() == AssertionKind.JUDGE) {
				verdicts.add(judge.judge(spec, detail.finalOutput(), summary.modelKey()));
			}
		}

		boolean passed = !verdicts.isEmpty() && verdicts.stream().allMatch(AssertionVerdict::passed);
		if (summary.status() == RunStatus.AWAITING_APPROVAL) {
			// A scenario that trips an approval policy stalls rather than measures. Recorded as a
			// failure with a plain explanation rather than auto-approved, which would quietly defeat
			// the policy the run just encountered.
			passed = false;
			verdicts.add(new AssertionVerdict("the run did not complete", AssertionKind.STATUS, false,
					"it stopped for approval; evaluation does not decide approvals on your behalf", true));
		}

		this.inFlight.remove(batchId);
		record(batchId, runId, repetition, passed, summary.status().name(), verdicts,
				Duration.between(startedAt, Instant.now()).toMillis(), summary.inputTokens(),
				summary.outputTokens());
	}

	/** Applies the variant's overrides on top of the scenario. */
	private StartRunCommand buildCommand(EvalScenarioEntity scenario, JsonNode variant) {
		JsonNode config = readJson(scenario.getConfigJson());
		JsonNode merged = merge(config, variant);

		List<String> tools = readStringList(
				merged.has("tools") ? merged.get("tools").toString() : scenario.getToolsJson());

		return new StartRunCommand(
				text(merged, "modelKey"),
				merged.has("systemPrompt") ? text(merged, "systemPrompt") : scenario.getSystemPrompt(),
				merged.has("input") ? text(merged, "input") : scenario.getInput(),
				number(merged, "temperature"),
				integer(merged, "maxOutputTokens"),
				// Never streamed: a batch has no one watching, and the deltas would be pure overhead.
				Boolean.FALSE,
				tools,
				bool(merged, "lenientArguments"),
				integer(merged, "maxSteps"),
				integer(merged, "maxRepairsPerStep"),
				integer(merged, "tokenBudget"),
				merged.has("wallClockBudgetMs") ? merged.get("wallClockBudgetMs").asLong() : null,
				integer(merged, "toolOutputMaxChars"),
				integer(merged, "loopThreshold"),
				integer(merged, "seed"),
				// Pinning tool versions is what lets a variant be "the same scenario with the old tool
				// description", which is the comparison the registry keeps versions for.
				versions(merged.get("toolVersions")),
				integer(merged, "contextLength"),
				merged.get("responseSchema"),
				bool(merged, "constrainOutput"),
				integer(merged, "maxOutputRepairs"));
	}

	/** Reads {@code toolVersions} from a variant, ignoring anything that is not a whole number. */
	private Map<String, Integer> versions(JsonNode node) {
		if (node == null || !node.isObject()) {
			return Map.of();
		}
		Map<String, Integer> pins = new LinkedHashMap<>();
		node.propertyNames().forEach(name -> {
			JsonNode value = node.get(name);
			if (value != null && value.isNumber()) {
				pins.put(name, value.asInt());
			}
		});
		return pins;
	}

	/** Variant fields win over the scenario's own configuration. */
	private JsonNode merge(JsonNode base, JsonNode overrides) {
		tools.jackson.databind.node.ObjectNode merged = objectMapper.createObjectNode();
		if (base != null && base.isObject()) {
			base.propertyNames().forEach(name -> merged.set(name, base.get(name)));
		}
		if (overrides != null && overrides.isObject()) {
			overrides.propertyNames().forEach(name -> merged.set(name, overrides.get(name)));
		}
		return merged;
	}

	private void record(UUID batchId, UUID runId, int repetition, boolean passed, String runStatus,
			List<AssertionVerdict> verdicts, long durationMs, int inputTokens, int outputTokens) {

		results.save(new EvalResultEntity(UUID.randomUUID(), batchId, runId, repetition, passed,
				runStatus, objectMapper.writeValueAsString(verdicts), durationMs, inputTokens,
				outputTokens, Instant.now()));

		// Progress is committed per repetition so a long batch can be watched rather than waited on.
		batches.findById(batchId).ifPresent(batch -> {
			batch.recordResult(passed, inputTokens, outputTokens);
			batches.save(batch);
		});
	}

	private void finish(UUID batchId, String status, String error) {
		batches.findById(batchId).ifPresent(batch -> {
			batch.complete(status, error);
			batches.save(batch);
			log.info("Batch {} {}: {}/{} passed ({}%)", batchId, status, batch.getPassedCount(),
					batch.getCompletedCount(), Math.round(batch.passRate() * 100));
		});
	}

	private List<AssertionSpec> readAssertions(EvalScenarioEntity scenario) {
		try {
			AssertionSpec[] specs = objectMapper.readValue(scenario.getAssertionsJson(),
					AssertionSpec[].class);
			return List.of(specs);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException(
					"Scenario '%s' has unreadable assertions: %s".formatted(scenario.getName(),
							ex.getMessage()), ex);
		}
	}

	private JsonNode readJson(String json) {
		if (json == null || json.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(json);
		}
		catch (RuntimeException ex) {
			return objectMapper.createObjectNode();
		}
	}

	private List<String> readStringList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			List<String> values = new ArrayList<>();
			if (node.isArray()) {
				node.forEach(element -> values.add(
						element.isString() ? element.stringValue() : element.toString()));
			}
			return values;
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isString()) ? value.stringValue() : null;
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isNumber()) ? value.asInt() : null;
	}

	private static Double number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isNumber()) ? value.asDouble() : null;
	}

	private static Boolean bool(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isBoolean()) ? value.asBoolean() : null;
	}
}
