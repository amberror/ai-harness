package com.example.ai_controll.eval.assertion;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.lmstudio.port.ChatModelClient;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.SamplingParams;

/**
 * Asks a model whether an answer satisfies a rubric.
 *
 * <p>This is the one assertion that is not reproducible, and the cost is worth stating plainly: the
 * judge here is the same small local model being measured, so the instrument has its own error rate.
 * When a pass rate moves, there are now two candidate explanations rather than one. Verdicts from a
 * judge are flagged as such throughout, so that ambiguity stays visible instead of blending into the
 * deterministic results.
 *
 * <p>It earns its place where substring matching genuinely cannot reach — "did it explain the
 * trade-off correctly" is not a regular expression. Three things keep it as steady as it can be:
 * temperature zero, a rubric that demands a single leading token, and an unparseable reply counted
 * as a failure rather than quietly ignored.
 */
@Component
public class JudgeAssertionEvaluator {

	private static final Logger log = LoggerFactory.getLogger(JudgeAssertionEvaluator.class);

	/**
	 * The judge is told to answer with one word first and explain afterwards.
	 *
	 * <p>Small models reason at length before committing, so demanding the verdict up front is what
	 * makes the reply parseable at all. It also means a truncated response still carries its answer.
	 */
	private static final String RUBRIC_PROMPT = """
			You are grading one answer against one criterion. You are not being asked to improve the \
			answer or to be generous about it.

			Reply with exactly one word on the first line: PASS or FAIL. \
			After that line you may add one short sentence explaining why.

			Criterion: %s

			Answer to grade:
			---
			%s
			---""";

	private final ChatModelClient chatClient;

	private final HarnessProperties properties;

	private final HarnessProperties.Judge config;

	public JudgeAssertionEvaluator(ChatModelClient chatClient, HarnessProperties properties) {
		this.chatClient = chatClient;
		this.properties = properties;
		this.config = properties.eval().judge();
	}

	/**
	 * Grades one answer against one criterion.
	 *
	 * @param modelKey which model to ask; usually the same one under test, which is exactly why the
	 *                 result is marked non-reproducible
	 */
	public AssertionVerdict judge(AssertionSpec spec, String answer, String modelKey) {
		if (answer == null || answer.isBlank()) {
			// Nothing to grade. Asking the judge anyway would spend tokens to be told the obvious.
			return AssertionVerdict.fail(spec, "there was no answer to judge");
		}

		String prompt = RUBRIC_PROMPT.formatted(spec.value() == null ? "" : spec.value(), answer);

		try {
			ChatResult result = chatClient.complete(new ChatRequest(
					resolveModel(modelKey), List.of(Message.user(prompt)), List.of(),
					// Temperature zero: the instrument should at least not add variance of its own.
					SamplingParams.deterministic(),
					// Configurable, and generous by default. Measured rather than guessed: at 1024 this
					// model reliably spent its whole allowance reasoning about the rubric and returned no
					// verdict at all, which fails every judged assertion for reasons that have nothing to
					// do with the answer being graded.
					config.maxOutputTokens(), List.of(), null));

			return interpret(spec, result);
		}
		catch (RuntimeException ex) {
			log.warn("The judge could not be reached for '{}'", spec.describe(), ex);
			return AssertionVerdict.fail(spec, "the judge could not be reached: " + ex.getMessage());
		}
	}

	private AssertionVerdict interpret(AssertionSpec spec, ChatResult result) {
		String content = result.content();
		if (content == null || content.isBlank()) {
			// Most likely the allowance went entirely on reasoning, which the harness already knows how
			// to recognise. Counted as a failure rather than skipped, so a judge that never answers
			// cannot quietly inflate a pass rate.
			return AssertionVerdict.fail(spec, result.truncatedBeforeAnswering()
					? "the judge spent its whole allowance reasoning and returned no verdict"
					: "the judge returned nothing");
		}

		String firstLine = content.strip().lines().findFirst().orElse("").strip()
				.toUpperCase(Locale.ROOT);
		String detail = content.strip().replaceAll("\\s+", " ");
		if (detail.length() > 200) {
			detail = detail.substring(0, 199) + "…";
		}

		if (firstLine.startsWith("PASS")) {
			return AssertionVerdict.pass(spec, detail);
		}
		if (firstLine.startsWith("FAIL")) {
			return AssertionVerdict.fail(spec, detail);
		}

		// Neither word. Treated as a failure rather than guessed at: a judge that cannot follow a
		// one-word instruction is not evidence about the answer it was grading.
		return AssertionVerdict.fail(spec,
				"the judge did not answer PASS or FAIL: " + detail);
	}

	private String resolveModel(String modelKey) {
		// A configured judge model wins: grading with a different model than the one under test is
		// the one thing that makes the instrument even partly independent of what it measures.
		if (config.hasModelKey()) {
			return config.modelKey();
		}
		if (modelKey != null && !modelKey.isBlank()) {
			return modelKey;
		}
		if (properties.lmStudio().hasDefaultModel()) {
			return properties.lmStudio().defaultModel();
		}
		throw new IllegalStateException("No model available to act as judge");
	}
}
