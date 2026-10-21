package com.example.ai_controll.conversation;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation the harness owns, and the step each message belongs to.
 *
 * <p>These exist because of a real failure. An earlier version kept messages and their step numbers
 * in two parallel lists, and the system-prompt constructor appended to only one of them. The lists
 * were permanently one apart, {@code entries()} threw on every call, and the persistence layer
 * caught and logged the exception rather than propagating it — so every transcript silently failed
 * to save and nothing looked wrong until a fork came back with an empty conversation.
 */
class TranscriptTest {

	@Test
	@DisplayName("a system prompt is stamped like any other message")
	void systemPromptIsStamped() {
		// The exact case that broke: the constructor must go through the same path as every other
		// message, not append directly to internal state.
		Transcript transcript = Transcript.of("You are precise.");
		transcript.add(Message.user("hello"));

		assertThat(transcript.entries()).hasSize(2);
		assertThat(transcript.messages()).hasSize(2);
		assertThat(transcript.entries().getFirst().message().role()).isEqualTo(Role.SYSTEM);
	}

	@Test
	@DisplayName("entries and messages always agree in size")
	void entriesAndMessagesStayInStep() {
		Transcript transcript = Transcript.of("system");
		transcript.currentStep(1);
		transcript.add(Message.user("q"));
		transcript.currentStep(2);
		transcript.add(Message.assistant("a", null));
		transcript.addAt(Message.toolResult("c1", "t", "r", false), 2);
		transcript.addAll(List.of(Message.user("more"), Message.user("and more")));

		assertThat(transcript.entries()).hasSameSizeAs(transcript.messages());
		assertThat(transcript.size()).isEqualTo(transcript.entries().size());
	}

	@Test
	@DisplayName("messages carry the step current when they were added")
	void stampsTheCurrentStep() {
		Transcript transcript = new Transcript();
		transcript.currentStep(1);
		transcript.add(Message.user("first"));
		transcript.currentStep(3);
		transcript.add(Message.assistant("later", null));

		assertThat(transcript.entries().get(0).stepNo()).isEqualTo(1);
		assertThat(transcript.entries().get(1).stepNo()).isEqualTo(3);
	}

	@Test
	@DisplayName("a message with no step set belongs to step zero")
	void defaultsToStepZero() {
		// The opening user turn is added before the loop starts, so it legitimately has no step.
		Transcript transcript = Transcript.of("system");
		transcript.add(Message.user("the question"));

		assertThat(transcript.entries()).allSatisfy(entry ->
				assertThat(entry.stepNo()).isZero());
	}

	@Test
	@DisplayName("a restored message keeps the step it was stored with")
	void restoresStoredSteps() {
		Transcript transcript = new Transcript();
		transcript.addAt(Message.user("q"), 0);
		transcript.addAt(Message.assistant("a", null), 4);

		assertThat(transcript.entries().get(1).stepNo()).isEqualTo(4);
	}

	@Test
	@DisplayName("compaction keeps the transcript consistent")
	void replaceAllStaysConsistent() {
		Transcript transcript = Transcript.of("system");
		transcript.add(Message.user("one"));
		transcript.add(Message.user("two"));

		transcript.replaceAll(List.of(Message.system("summary"), Message.user("two")));

		assertThat(transcript.entries()).hasSize(2);
		assertThat(transcript.messages()).hasSize(2);
	}

	@Test
	@DisplayName("a copy is detached from the original")
	void copyIsIndependent() {
		Transcript original = Transcript.of("system");
		original.add(Message.user("q"));

		Transcript copy = original.copy();
		copy.add(Message.user("only in the copy"));

		assertThat(original.size()).isEqualTo(2);
		assertThat(copy.size()).isEqualTo(3);
	}

	@Test
	@DisplayName("an empty or blank system prompt adds nothing")
	void ignoresBlankSystemPrompt() {
		assertThat(Transcript.of(null).isEmpty()).isTrue();
		assertThat(Transcript.of("").isEmpty()).isTrue();
		assertThat(Transcript.of("   ").isEmpty()).isTrue();
	}

	@Test
	@DisplayName("the snapshot cannot be used to mutate the transcript")
	void snapshotIsImmutable() {
		Transcript transcript = Transcript.of("system");

		assertThat(transcript.messages()).isUnmodifiable();
		assertThat(transcript.entries()).isUnmodifiable();
	}
}
