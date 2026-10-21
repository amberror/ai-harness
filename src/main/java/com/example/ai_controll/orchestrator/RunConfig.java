package com.example.ai_controll.orchestrator;

import java.util.List;
import java.util.Map;

import com.example.ai_controll.lmstudio.port.ResponseFormat;
import com.example.ai_controll.lmstudio.port.SamplingParams;

import tools.jackson.databind.JsonNode;

/**
 * The effective settings for one run, after profile and per-request overrides are combined.
 *
 * <p>Every guardrail is here rather than hard-coded, so an experiment can vary exactly one of them
 * and hold the rest fixed. That is the whole point of making them settings: "the model got better
 * when I raised the repair allowance" is only a finding if nothing else moved.
 *
 * @param toolNames            which tools the model is shown; deliberately explicit, since the number
 *                             of tools in front of it strongly affects whether it picks the right one
 * @param toolVersions         pins a tool to a stored version instead of whichever is current. The
 *                             registry versions every edit so that a change in behaviour can be
 *                             attributed to a change in wording; without this the attribution could
 *                             never actually be made, because a batch would silently pick up
 *                             whatever the tool looked like on the day it ran
 * @param lenientArguments     whether to repair malformed arguments before rejecting them. Off by
 *                             default: silently fixing the model's output would destroy the very
 *                             measurement the harness exists to take
 * @param maxSteps             ceiling on loop iterations
 * @param maxRepairsPerStep    how many invalid tool calls in a row are tolerated before giving up
 * @param tokenBudget          total tokens, reasoning included, or null for no limit
 * @param wallClockBudgetMs    total time, or null for no limit
 * @param toolOutputMaxChars   how much of a tool's output goes back into the context
 * @param loopThreshold        how many identical calls before the run is stopped
 * @param contextLength        the model's window, or null to ask the server what it loaded with
 * @param responseSchema       a JSON Schema the final answer must satisfy, or null for free text.
 *                             Predictability otherwise stops at the tool boundary: arguments are
 *                             checked and repaired, while the answer -- the part anything downstream
 *                             actually consumes -- is whatever the model felt like writing
 * @param constrainOutput      whether the server should also constrain decoding to that schema.
 *                             Separate from the schema itself so an experiment can hold the contract
 *                             fixed and vary only how it is enforced
 * @param maxOutputRepairs     how many malformed answers are corrected before the run gives up
 */
public record RunConfig(String modelKey, String systemPrompt, SamplingParams sampling,
		Integer maxOutputTokens, List<String> toolNames, Map<String, Integer> toolVersions,
		boolean lenientArguments, int maxSteps, int maxRepairsPerStep, Integer tokenBudget,
		Long wallClockBudgetMs, int toolOutputMaxChars, int loopThreshold, boolean streaming,
		Integer contextLength, JsonNode responseSchema, boolean constrainOutput,
		int maxOutputRepairs) {

	public RunConfig {
		toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
		toolVersions = toolVersions == null ? Map.of() : Map.copyOf(toolVersions);
		// A run with no steps could never finish, and a negative one is certainly a mistake.
		maxSteps = Math.max(1, maxSteps);
		maxRepairsPerStep = Math.max(0, maxRepairsPerStep);
		toolOutputMaxChars = Math.max(200, toolOutputMaxChars);
		loopThreshold = Math.max(2, loopThreshold);
		maxOutputRepairs = Math.max(0, maxOutputRepairs);
	}

	public boolean hasTools() {
		return !toolNames.isEmpty();
	}

	public boolean hasResponseSchema() {
		return responseSchema != null && !responseSchema.isNull();
	}

	/** The constraint to send with the request, or null when the answer is only checked afterwards. */
	public ResponseFormat responseFormat() {
		return (constrainOutput && hasResponseSchema()) ? ResponseFormat.jsonSchema(responseSchema)
				: null;
	}
}
