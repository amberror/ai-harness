package com.example.ai_controll.tool.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.lmstudio.ModelSlot;
import com.example.ai_controll.lmstudio.port.ChatModelClient;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.SamplingParams;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolRegistryService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns what the journal observed into changes you can make to a tool.
 *
 * <p>Two kinds of change, in increasing order of how much they can mislead you:
 *
 * <ul>
 *   <li><strong>Examples.</strong> Calls that already worked, lifted out of the journal and written
 *       into the schema's {@code examples} keyword — which is part of JSON Schema, travels to the
 *       model inside {@code parameters} with no extra plumbing, and is well evidenced to help a small
 *       model get the shape right. Nothing is invented: every example is a call the model itself made
 *       and the validator accepted.</li>
 *   <li><strong>A description.</strong> Drafted by a model, from the current description, the schema
 *       and the observed failures. Genuinely useful and genuinely dangerous: the instrument is now
 *       proposing changes to itself, and a description that <em>reads</em> better is not the same as
 *       one the model performs better under.</li>
 * </ul>
 *
 * <p>Which is why nothing here writes anything. Every method returns a <em>proposal</em>; applying it
 * is a separate, explicit call that goes through the ordinary versioned save, so the previous
 * definition stays in the history and the change is attributable. The point of versioning tools since
 * phase 2 was to be able to say "this wording, that pass rate" — a coach that edited tools directly
 * would spend that in a week.
 */
@Service
public class ToolCoach {

	private static final Logger log = LoggerFactory.getLogger(ToolCoach.class);

	/** Kept small on purpose: the schema is sent on every step, so each example is paid for repeatedly. */
	private static final int MAX_EXAMPLES = 3;

	private static final String DRAFT_PROMPT = """
			You are improving the description of a tool so that a small language model calls it \
			correctly. Below are the tool's current description, its argument schema, and evidence \
			of how a model has actually been getting it wrong.

			Write a replacement description. Requirements:
			- State what the tool does in the first sentence.
			- Name the arguments the model keeps omitting or malforming, and say exactly what they \
			take.
			- Mention any allowed values that the model has been getting wrong.
			- Two to four sentences. Plain statements, no bullet points, no markdown.
			- Output ONLY the description text. No preamble, no quotes, no explanation.

			CURRENT DESCRIPTION:
			%s

			ARGUMENT SCHEMA:
			%s

			WHAT GOES WRONG:
			%s
			""";

	private final ToolRegistryService registry;

	private final ToolUsageAnalyzer analyzer;

	private final ChatModelClient chatClient;

	private final ModelSlot modelSlot;

	private final ObjectMapper objectMapper;

	private final HarnessProperties properties;

	public ToolCoach(ToolRegistryService registry, ToolUsageAnalyzer analyzer,
			ChatModelClient chatClient, ModelSlot modelSlot, ObjectMapper objectMapper,
			HarnessProperties properties) {
		this.registry = registry;
		this.analyzer = analyzer;
		this.chatClient = chatClient;
		this.modelSlot = modelSlot;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	// -------------------------------------------------------------------------------------------
	// Examples
	// -------------------------------------------------------------------------------------------

	/**
	 * Proposes examples harvested from calls that worked.
	 *
	 * @return the schema as it would be saved, or empty when the journal has nothing to offer
	 */
	public Optional<ObjectNode> proposeExamples(String name) {
		ToolDefinitionEntity definition = registry.findCurrent(name).orElse(null);
		if (definition == null) {
			return Optional.empty();
		}
		List<JsonNode> harvested = harvest(name);
		if (harvested.isEmpty()) {
			return Optional.empty();
		}

		JsonNode schema = registry.parseSchema(definition);
		if (!schema.isObject()) {
			return Optional.empty();
		}
		ObjectNode updated = ((ObjectNode) schema).deepCopy();
		ArrayNode examples = objectMapper.createArrayNode();
		harvested.forEach(examples::add);
		// The standard JSON Schema keyword, so it reaches the model inside `parameters` exactly as it
		// stands, without the harness having to render it into the description by hand.
		updated.set("examples", examples);
		return Optional.of(updated);
	}

	/** Saves the proposed examples as a new version. */
	public ToolDefinitionEntity applyExamples(String name) {
		ObjectNode schema = proposeExamples(name).orElseThrow(() -> new IllegalStateException(
				"No successful calls to '%s' are recorded, so there is nothing to learn from yet."
						.formatted(name)));
		ToolDefinitionEntity current = registry.findCurrent(name).orElseThrow();
		return registry.save(new ToolRegistryService.ToolDefinition(name, current.getDescription(),
				schema, current.getExecutorType(), executorConfig(current), current.isEnabled(),
				current.getSource(), current.getTimeoutMs()),
				"examples harvested from calls the model made successfully");
	}

	/**
	 * Distinct successful argument objects, smallest first.
	 *
	 * <p>Smallest first because a minimal example teaches the shape without teaching the values: an
	 * example carrying a long URL invites the model to reuse that URL.
	 */
	private List<JsonNode> harvest(String name) {
		List<JsonNode> examples = new ArrayList<>();
		List<String> raw = new ArrayList<>(analyzer.report(name).successfulCalls());
		raw.sort((a, b) -> Integer.compare(a.length(), b.length()));
		for (String candidate : raw) {
			try {
				JsonNode parsed = objectMapper.readTree(candidate);
				if (parsed.isObject() && !parsed.isEmpty()) {
					examples.add(parsed);
				}
			}
			catch (RuntimeException ex) {
				// Recorded arguments that no longer parse are not worth showing the model.
				log.debug("Skipping unparseable recorded arguments for '{}'", name);
			}
			if (examples.size() >= MAX_EXAMPLES) {
				break;
			}
		}
		return examples;
	}

	// -------------------------------------------------------------------------------------------
	// Description
	// -------------------------------------------------------------------------------------------

	/**
	 * Asks a model to draft a better description from the evidence.
	 *
	 * <p>Returns the draft rather than applying it, and the caller is expected to look at it. The
	 * measurement that says whether it helped is a batch comparison against the version it replaces,
	 * which is why this leaves the current version in place until someone decides.
	 */
	public Draft draftDescription(String name) {
		ToolDefinitionEntity definition = registry.findCurrent(name)
				.orElseThrow(() -> new IllegalArgumentException("No tool named '" + name + "'"));
		ToolUsageReport report = analyzer.report(name);

		String prompt = DRAFT_PROMPT.formatted(definition.getDescription(),
				registry.parseSchema(definition).toPrettyString(), evidence(report));

		HarnessProperties.Learning settings = properties.tools().learning();
		String modelKey = settings.hasModelKey() ? settings.modelKey()
				: properties.lmStudio().defaultModel();

		long queued = 0L;
		try {
			queued = modelSlot.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the model", ex);
		}
		try {
			ChatResult result = chatClient.complete(new ChatRequest(modelKey,
					List.of(Message.user(prompt)), List.of(),
					// Temperature zero: a description that changes every time it is drafted is not a
					// proposal, it is a lottery.
					SamplingParams.deterministic(),
					// Generous, because the drafting model pays the same reasoning tax as every other
					// call here: at a tight allowance it thinks about the wording and returns nothing.
					settings.maxOutputTokens(), List.of(), null));

			String drafted = clean(result.content());
			boolean usable = drafted != null && !drafted.isBlank();
			if (!usable) {
				log.info("Drafting a description for '{}' produced nothing: finish={}, {} completion "
						+ "tokens of which {} were reasoning", name, result.finishReason(),
						result.usage().completionTokens(), result.usage().reasoningTokens());
			}
			return new Draft(name, definition.getDescription(), drafted, modelKey, report, usable,
					String.valueOf(result.finishReason()), result.usage().completionTokens(),
					result.usage().reasoningTokens(), explain(usable, result));
		}
		finally {
			modelSlot.release();
			log.debug("Description draft for '{}' waited {}ms for the model", name, queued);
		}
	}

	/** Saves a description as a new version. The text is whatever the caller decided, not the draft. */
	public ToolDefinitionEntity applyDescription(String name, String description) {
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("The description cannot be empty");
		}
		ToolDefinitionEntity current = registry.findCurrent(name)
				.orElseThrow(() -> new IllegalArgumentException("No tool named '" + name + "'"));
		return registry.save(new ToolRegistryService.ToolDefinition(name, description.strip(),
				registry.parseSchema(current), current.getExecutorType(), executorConfig(current),
				current.isEnabled(), current.getSource(), current.getTimeoutMs()),
				"description rewritten from observed failures -- not yet measured");
	}

	private JsonNode executorConfig(ToolDefinitionEntity definition) {
		return definition.getExecutorConfig() == null || definition.getExecutorType() == ExecutorType.BUILTIN
				? null : registry.parseExecutorConfig(definition);
	}

	/** The failure evidence, as the drafting model sees it. */
	private static String evidence(ToolUsageReport report) {
		if (!report.hasEvidence()) {
			return "No calls to this tool have been recorded yet.";
		}
		StringBuilder out = new StringBuilder();
		out.append("Of %d calls, %d were rejected before running.%n".formatted(report.calls(),
				report.invalid()));
		if (!report.fields().isEmpty()) {
			out.append("Argument problems, commonest first:%n");
			report.fields().forEach(field ->
					out.append("  %s -- %d times%n".formatted(field.value(), field.count())));
		}
		if (!report.rejectedSamples().isEmpty()) {
			out.append("Rejected arguments, exactly as the model wrote them:%n");
			report.rejectedSamples().forEach(sample ->
					out.append("  %s%n".formatted(sample)));
		}
		if (!report.successfulCalls().isEmpty()) {
			out.append("Calls that worked:%n");
			report.successfulCalls().forEach(sample -> out.append("  %s%n".formatted(sample)));
		}
		return out.toString();
	}

	/**
	 * Strips what a small model wraps its answer in.
	 *
	 * <p>Told to output only the description, it will still open with "Here is the improved
	 * description:" often enough to matter, and a description beginning that way is then shown to the
	 * model on every single step.
	 */
	static String clean(String drafted) {
		if (drafted == null) {
			return null;
		}
		String text = drafted.strip();
		if (text.startsWith("```")) {
			int firstBreak = text.indexOf('\n');
			int close = text.lastIndexOf("```");
			if (firstBreak > 0 && close > firstBreak) {
				text = text.substring(firstBreak + 1, close).strip();
			}
		}
		int colon = text.indexOf(':');
		if (colon > 0 && colon < 60 && text.substring(0, colon).toLowerCase(java.util.Locale.ROOT)
				.matches(".*(here is|here's|description|replacement|revised|updated|proposed).*")) {
			text = text.substring(colon + 1).strip();
		}
		if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
			text = text.substring(1, text.length() - 1).strip();
		}
		return text;
	}

	/**
	 * A proposed description, alongside the one it would replace and the evidence behind it.
	 *
	 * @param succeeded false when the model returned nothing usable, which on a small model usually
	 *                  means it spent its whole allowance reasoning about the wording
	 */
	public record Draft(String tool, String current, String proposed, String modelKey,
			ToolUsageReport evidence, boolean succeeded, String finishReason, int completionTokens,
			int reasoningTokens, String diagnosis) {
	}

	/**
	 * Why the draft came back empty, in terms of the thing that has to change.
	 *
	 * <p>"It returned nothing" is the kind of answer this project exists to stop giving. On a
	 * reasoning model the overwhelmingly likely cause is that the whole allowance went on thinking
	 * about the wording, and that is both checkable from the usage figures and fixable in one line of
	 * configuration.
	 */
	private static String explain(boolean usable, ChatResult result) {
		if (usable) {
			return null;
		}
		int completion = result.usage().completionTokens();
		int reasoning = result.usage().reasoningTokens();
		if (reasoning > 0 && reasoning >= completion * 9 / 10) {
			return ("The model spent %d of its %d output tokens reasoning and never wrote the "
					+ "description. Raise harness.tools.learning.max-output-tokens, or point "
					+ "harness.tools.learning.model-key at a model that reasons less.")
							.formatted(reasoning, completion);
		}
		return "The model returned no usable text (finish reason: %s, %d output tokens)."
				.formatted(result.finishReason(), completion);
	}
}
