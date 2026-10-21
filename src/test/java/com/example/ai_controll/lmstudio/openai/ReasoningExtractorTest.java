package com.example.ai_controll.lmstudio.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Getting this wrong is not cosmetic: unextracted reasoning is quoted back to the model as if it
 * were an answer, and an answer mistaken for reasoning is discarded outright.
 */
class ReasoningExtractorTest {

	private final ReasoningExtractor extractor = new ReasoningExtractor();

	@Test
	@DisplayName("the dedicated reasoning_content field is used as-is")
	void usesDedicatedField() {
		// The shape LM Studio actually returns for the Qwen3 family.
		ReasoningExtractor.Split split = extractor.split("The weather is mild.",
				"The user wants the weather. I should call the tool.");

		assertThat(split.content()).isEqualTo("The weather is mild.");
		assertThat(split.reasoning()).isEqualTo("The user wants the weather. I should call the tool.");
	}

	@Test
	@DisplayName("inline <think> blocks are stripped out of the content")
	void stripsInlineThinkBlock() {
		ReasoningExtractor.Split split = extractor.split(
				"<think>Let me work this out. 2+2 is 4.</think>The answer is 4.", null);

		assertThat(split.content()).isEqualTo("The answer is 4.");
		assertThat(split.reasoning()).isEqualTo("Let me work this out. 2+2 is 4.");
	}

	@Test
	@DisplayName("several think blocks are all collected")
	void collectsMultipleBlocks() {
		ReasoningExtractor.Split split = extractor.split(
				"<think>First thought.</think>Partial answer. <think>Second thought.</think>Rest.", null);

		assertThat(split.reasoning()).contains("First thought.").contains("Second thought.");
		assertThat(split.content()).contains("Partial answer.").contains("Rest.");
		assertThat(split.content()).doesNotContain("thought");
	}

	@Test
	@DisplayName("the reasoning field and inline tags are combined when both appear")
	void combinesFieldAndInlineTags() {
		ReasoningExtractor.Split split = extractor.split("<think>Inline part.</think>Answer.",
				"Field part.");

		assertThat(split.reasoning()).contains("Field part.").contains("Inline part.");
		assertThat(split.content()).isEqualTo("Answer.");
	}

	@Test
	@DisplayName("an unclosed <think> means the model ran out of tokens mid-thought")
	void handlesUnclosedThinkTag() {
		// The token limit was hit before the model closed the block, so there is no answer at all --
		// and crucially the fragment must not be handed on as if it were one.
		ReasoningExtractor.Split split = extractor.split(
				"<think>I need to consider the options and then", null);

		assertThat(split.content()).isNull();
		assertThat(split.reasoning()).isEqualTo("I need to consider the options and then");
	}

	@Test
	@DisplayName("text before an unclosed tag is still the answer")
	void keepsTextBeforeUnclosedTag() {
		ReasoningExtractor.Split split = extractor.split("Here is the answer.<think>but wait", null);

		assertThat(split.content()).isEqualTo("Here is the answer.");
		assertThat(split.reasoning()).isEqualTo("but wait");
	}

	@Test
	@DisplayName("a closing tag with no opener means everything before it was reasoning")
	void handlesOrphanClosingTag() {
		// Some servers strip the opening tag but leave the closing one behind.
		ReasoningExtractor.Split split = extractor.split(
				"I should answer briefly.</think>The answer is 4.", null);

		assertThat(split.content()).isEqualTo("The answer is 4.");
		assertThat(split.reasoning()).isEqualTo("I should answer briefly.");
	}

	@Test
	@DisplayName("tag name and casing variants are all recognised")
	void recognisesTagVariants() {
		assertThat(extractor.split("<THINK>a</THINK>b", null).reasoning()).isEqualTo("a");
		assertThat(extractor.split("<thinking>a</thinking>b", null).reasoning()).isEqualTo("a");
		assertThat(extractor.split("<reasoning>a</reasoning>b", null).reasoning()).isEqualTo("a");
	}

	@Test
	@DisplayName("content with no reasoning at all is passed through untouched")
	void passesThroughPlainContent() {
		ReasoningExtractor.Split split = extractor.split("Just a plain answer.", null);

		assertThat(split.content()).isEqualTo("Just a plain answer.");
		assertThat(split.reasoning()).isNull();
		assertThat(split.hasReasoning()).isFalse();
	}

	@Test
	@DisplayName("empty and null inputs do not blow up")
	void handlesEmptyInput() {
		assertThat(extractor.split(null, null).content()).isNull();
		assertThat(extractor.split("", null).content()).isNull();
		assertThat(extractor.split("   ", null).content()).isNull();
		assertThat(extractor.split("", "   ").reasoning()).isNull();
	}

	@Test
	@DisplayName("an empty answer after stripping reasoning is reported as absent, not as blank text")
	void reportsEmptyAnswerAsNull() {
		// This distinction is what lets the orchestrator tell "the model thought but never answered"
		// apart from "the model answered with an empty string".
		ReasoningExtractor.Split split = extractor.split("<think>Only thinking here.</think>   ", null);

		assertThat(split.content()).isNull();
		assertThat(split.hasContent()).isFalse();
		assertThat(split.hasReasoning()).isTrue();
	}

	@Test
	@DisplayName("angle brackets that are not reasoning tags are left alone")
	void leavesUnrelatedMarkupAlone() {
		ReasoningExtractor.Split split = extractor.split(
				"Use <div> for a block and a < b for comparison.", null);

		assertThat(split.content()).isEqualTo("Use <div> for a block and a < b for comparison.");
		assertThat(split.reasoning()).isNull();
	}
}
