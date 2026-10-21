package com.example.ai_controll.orchestrator;

import java.util.List;
import java.util.Map;

import com.example.ai_controll.lmstudio.port.SamplingParams;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Everything needed to start a run.
 *
 * <p>Guardrails are overridable per request so a single experiment can vary one of them and hold the
 * rest fixed. Every default below is a considered value rather than a round number, and the
 * reasoning is recorded next to it.
 *
 * @param tools            names of the tools to expose. Empty means none, and the run is a plain
 *                         single-turn completion
 * @param lenientArguments repair malformed arguments instead of rejecting them. Off by default:
 *                         silently correcting the model destroys the measurement
 * @param maxOutputTokens  output allowance. On this reasoning model it also covers the chain of
 *                         thought, so a value tuned for a non-reasoning model starves the answer
 * @param seed             fixes the sampler, so two repetitions differ only where the harness made
 *                         them differ. Unset by default, because a seeded run is reproducible rather
 *                         than representative and a pass rate wants the latter
 * @param toolVersions     pins named tools to stored versions instead of whichever is current
 * @param contextLength    the model's window; unset asks the server what it was loaded with
 * @param responseSchema   a JSON Schema the final answer must satisfy, or null for free text
 * @param constrainOutput  whether the server should constrain decoding to that schema as well
 */
public record StartRunCommand(String modelKey, String systemPrompt, @NotBlank String input,
		Double temperature, @Positive Integer maxOutputTokens, Boolean stream, List<String> tools,
		Boolean lenientArguments, @Positive Integer maxSteps, Integer maxRepairsPerStep,
		Integer tokenBudget, Long wallClockBudgetMs, Integer toolOutputMaxChars,
		Integer loopThreshold, Integer seed, Map<String, Integer> toolVersions,
		@Positive Integer contextLength, JsonNode responseSchema, Boolean constrainOutput,
		Integer maxOutputRepairs) {

	/**
	 * Generous, because of a measured failure: this model asked a trivial question with an allowance
	 * of 20 tokens spent all 20 reasoning and returned an empty answer.
	 */
	public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;

	/** Enough for a multi-step task, few enough that a runaway loop is caught quickly. */
	public static final int DEFAULT_MAX_STEPS = 12;

	/**
	 * One retry after a correction is reasonable; a model that cannot act on two corrections in a row
	 * is not going to act on a third, and the run is better ended with a clear reason.
	 */
	public static final int DEFAULT_MAX_REPAIRS_PER_STEP = 2;

	/** Enough for a useful result, small enough not to swamp a small context window. */
	public static final int DEFAULT_TOOL_OUTPUT_MAX_CHARS = 4000;

	/** A retry after a failure is normal; a third identical call is not. */
	public static final int DEFAULT_LOOP_THRESHOLD = 3;

	/** The same allowance tool arguments get, for the same reason. */
	public static final int DEFAULT_MAX_OUTPUT_REPAIRS = 2;

	public StartRunCommand {
		tools = tools == null ? List.of() : List.copyOf(tools);
		toolVersions = toolVersions == null ? Map.of() : Map.copyOf(toolVersions);
	}

	/**
	 * Everything at its default.
	 *
	 * <p>Exists so that a caller overriding nothing does not have to write a line of positional
	 * nulls that has to be recounted every time a setting is added -- the kind of line where a new
	 * field silently lands in the wrong slot.
	 */
	public static StartRunCommand defaults() {
		return new StartRunCommand(null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null);
	}

	/** The same command asking a different question, for a fork that changes the input. */
	public StartRunCommand withInput(String newInput) {
		return new StartRunCommand(modelKey, systemPrompt, newInput, temperature, maxOutputTokens,
				stream, tools, lenientArguments, maxSteps, maxRepairsPerStep, tokenBudget,
				wallClockBudgetMs, toolOutputMaxChars, loopThreshold, seed, toolVersions, contextLength,
				responseSchema, constrainOutput, maxOutputRepairs);
	}

	/** Temperature zero by default: run-to-run variance otherwise masks any real change. */
	public SamplingParams sampling() {
		return SamplingParams.temperature(temperature == null ? 0.0d : temperature).withSeed(seed);
	}

	public boolean streaming() {
		return stream == null || stream;
	}

	/** Combines the request with the defaults into the settings the loop actually runs on. */
	public RunConfig toConfig(String resolvedModelKey) {
		return new RunConfig(resolvedModelKey, systemPrompt, sampling(),
				maxOutputTokens == null ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens,
				tools, toolVersions,
				Boolean.TRUE.equals(lenientArguments),
				maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps,
				maxRepairsPerStep == null ? DEFAULT_MAX_REPAIRS_PER_STEP : maxRepairsPerStep,
				tokenBudget, wallClockBudgetMs,
				toolOutputMaxChars == null ? DEFAULT_TOOL_OUTPUT_MAX_CHARS : toolOutputMaxChars,
				loopThreshold == null ? DEFAULT_LOOP_THRESHOLD : loopThreshold,
				streaming(), contextLength, responseSchema,
				Boolean.TRUE.equals(constrainOutput),
				maxOutputRepairs == null ? DEFAULT_MAX_OUTPUT_REPAIRS : maxOutputRepairs);
	}
}
