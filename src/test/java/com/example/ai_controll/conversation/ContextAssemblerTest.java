package com.example.ai_controll.conversation;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.tool.schema.ToolSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is shown when the conversation no longer fits.
 *
 * <p>The properties asserted here are the ones that make the compaction safe to use inside a
 * measuring instrument: it never removes the task, it never separates a tool result from the call it
 * answers, and it never rewrites the transcript the run is recorded against.
 */
class ContextAssemblerTest {

	private final ContextAssembler assembler = new ContextAssembler(new TokenEstimator(),
			TestProperties.defaults());

	private static final List<ToolSchema> NO_TOOLS = List.of();

	/** A conversation of N exchanges, each with a large tool result. */
	private List<Message> conversation(int exchanges, int resultChars) {
		List<Message> messages = new ArrayList<>();
		messages.add(Message.system("You are precise."));
		messages.add(Message.user("Work out the total."));
		for (int i = 0; i < exchanges; i++) {
			messages.add(Message.assistantToolCalls(null, null,
					List.of(new ToolCallRequest("call_" + i, "calc_eval", "{\"expression\":\"1+1\"}"))));
			messages.add(Message.toolResult("call_" + i, "calc_eval", "x".repeat(resultChars), false));
		}
		return messages;
	}

	@Test
	@DisplayName("a conversation that fits is passed through untouched")
	void leavesSmallConversationsAlone() {
		List<Message> messages = conversation(2, 100);

		ContextAssembler.Assembly assembly = assembler.assemble(messages, NO_TOOLS, 8192, 512);

		assertThat(assembly.compacted()).isFalse();
		assertThat(assembly.messages()).isEqualTo(messages);
		assertThat(assembly.fits()).isTrue();
	}

	@Test
	@DisplayName("an unknown window compacts nothing and says so")
	void doesNothingWithoutAWindow() {
		// Not knowing the window is a reason to leave the request alone and record that it was not
		// checked -- not a reason to guess and start deleting the conversation.
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(20, 4000), NO_TOOLS, null,
				512);

		assertThat(assembly.compacted()).isFalse();
		assertThat(assembly.fits()).isTrue();
		assertThat(assembly.messages()).hasSize(42);
	}

	@Test
	@DisplayName("old tool results are shortened before any exchange is dropped")
	void shortensBeforeDropping() {
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(4, 4000), NO_TOOLS, 2048,
				256);

		assertThat(assembly.compacted()).isTrue();
		assertThat(assembly.shortenedResults()).isPositive();
		// Shortening freed enough on its own; nothing needed removing, and a shortened result still
		// tells the model which call it answers.
		assertThat(assembly.droppedMessages()).isZero();
	}

	@Test
	@DisplayName("the system prompt and the original task always survive")
	void neverDropsTheTask() {
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(40, 3000), NO_TOOLS, 1024,
				256);

		assertThat(assembly.messages().get(0).role()).isEqualTo(Role.SYSTEM);
		assertThat(assembly.messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo(Role.USER);
			assertThat(message.content()).isEqualTo("Work out the total.");
		});
	}

	@Test
	@DisplayName("a tool result is never separated from the call it answers")
	void keepsToolPairsTogether() {
		// The invariant that makes the whole thing safe: the endpoint rejects a tool message whose
		// matching assistant tool_call is missing, so a request assembled that way fails outright.
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(30, 2000), NO_TOOLS, 2048,
				256);

		List<String> requested = new ArrayList<>();
		for (Message message : assembly.messages()) {
			message.toolCalls().forEach(call -> requested.add(call.id()));
			if (message.role() == Role.TOOL) {
				assertThat(requested).contains(message.toolCallId());
			}
		}
	}

	@Test
	@DisplayName("the model is told what was removed")
	void explainsTheGap() {
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(40, 3000), NO_TOOLS, 1024,
				256);

		// Without this a model that has lost the record of a tool it already ran simply runs it
		// again, which trips the loop guard -- a harness effect that reads as a model trait.
		assertThat(assembly.messages()).anySatisfy(message ->
				assertThat(message.content()).contains("removed to fit the context window"));
	}

	@Test
	@DisplayName("a compacted request still has exactly one system message, and it is first")
	void keepsTheSystemMessageFirstAndAlone() {
		// Measured against the live model: a note inserted as its own system message after the task
		// is refused by Qwen's chat template with "System message must be at the beginning", and the
		// run dies -- so compaction killed the runs it exists to rescue. The note belongs inside the
		// system message at position zero.
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(40, 3000), NO_TOOLS, 1024,
				256);

		List<Message> messages = assembly.messages();
		assertThat(messages.getFirst().role()).isEqualTo(Role.SYSTEM);
		assertThat(messages.stream().filter(m -> m.role() == Role.SYSTEM).count()).isEqualTo(1);
		assertThat(messages.getFirst().content()).contains("You are precise.")
				.contains("removed to fit the context window");
	}

	@Test
	@DisplayName("with no system prompt, the note becomes one at the beginning")
	void createsASystemMessageWhenThereIsNone() {
		List<Message> messages = new ArrayList<>(conversation(40, 3000));
		messages.removeFirst();

		ContextAssembler.Assembly assembly = assembler.assemble(messages, NO_TOOLS, 1024, 256);

		assertThat(assembly.messages().getFirst().role()).isEqualTo(Role.SYSTEM);
		assertThat(assembly.messages().stream().filter(m -> m.role() == Role.SYSTEM).count())
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the recent exchanges are kept")
	void keepsTheMostRecentExchanges() {
		List<Message> messages = conversation(20, 3000);
		Message lastResult = messages.get(messages.size() - 1);

		ContextAssembler.Assembly assembly = assembler.assemble(messages, NO_TOOLS, 2048, 256);

		// The result of the step just taken is the one thing the next step cannot do without.
		assertThat(assembly.messages().get(assembly.messages().size() - 1).toolCallId())
				.isEqualTo(lastResult.toolCallId());
	}

	@Test
	@DisplayName("a droppable exchange is given up before the run is")
	void prefersDroppingOverAborting() {
		// The case measured against the live model: only two exchanges exist, both inside the
		// "keep the most recent two" guarantee, and the request does not fit. Honouring the
		// preference would end the run while a perfectly droppable older exchange sat there.
		List<Message> messages = conversation(2, 1200);

		ContextAssembler.Assembly assembly = assembler.assemble(messages, NO_TOOLS, 900, 256);

		assertThat(assembly.fits()).isTrue();
		assertThat(assembly.droppedMessages() + assembly.shortenedResults()).isPositive();
	}

	@Test
	@DisplayName("compaction never returns a request larger than the one it was given")
	void neverGrowsTheRequest() {
		// Small exchanges are the case where the explanatory note can cost more than everything
		// removed to make room for it. Measured on the live model before this was handled: 563 tokens
		// in, 616 tokens out.
		List<Message> messages = conversation(6, 30);
		int before = new TokenEstimator().estimate(messages, NO_TOOLS);

		ContextAssembler.Assembly assembly = assembler.assemble(messages, NO_TOOLS, 700, 380);

		assertThat(assembly.estimatedTokensAfter()).isLessThanOrEqualTo(before);
	}

	@Test
	@DisplayName("a window too small for the answer alone is reported rather than attempted")
	void refusesWhenNothingCanFit() {
		// Reserving more output than the window holds cannot be compacted around, and sending it
		// anyway would let the server truncate the request from the front.
		ContextAssembler.Assembly assembly = assembler.assemble(conversation(2, 100), NO_TOOLS, 512,
				4096);

		assertThat(assembly.fits()).isFalse();
	}

	@Test
	@DisplayName("the transcript passed in is not modified")
	void isNonDestructive() {
		List<Message> messages = conversation(30, 3000);
		int sizeBefore = messages.size();
		String firstResultBefore = messages.get(3).content();

		assembler.assemble(messages, NO_TOOLS, 2048, 256);

		// Only the request is reduced. The run's own record stays whole, or replay and forking would
		// silently see less than actually happened.
		assertThat(messages).hasSize(sizeBefore);
		assertThat(messages.get(3).content()).isEqualTo(firstResultBefore);
	}
}
