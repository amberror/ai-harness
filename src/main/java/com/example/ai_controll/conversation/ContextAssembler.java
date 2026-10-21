package com.example.ai_controll.conversation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.tool.schema.ToolSchema;

/**
 * Decides what the model is actually shown on a step, given the context window it has.
 *
 * <p>This is the guardrail against the failure mode that is hardest to see from the outside. A
 * transcript grows every step, and once it passes the loaded context length the model server -- not
 * the harness -- decides what to do about it. llama.cpp-backed servers typically discard from the
 * <em>front</em>, which is precisely the wrong end: the system prompt and the task go first, and the
 * model then appears to forget what it was doing and stop using its tools. In an evaluation that
 * reads as a model failure when it is a harness failure, which is the worst kind of measurement
 * error an instrument can make.
 *
 * <p>Two properties make this compaction usable as part of a measuring instrument:
 *
 * <ul>
 *   <li><strong>It is deterministic.</strong> Nothing here calls the model. A summarising compactor
 *       would fold the model's own variance into the harness, so a change in pass rate could no
 *       longer be attributed to the thing under test.</li>
 *   <li><strong>It is non-destructive.</strong> The transcript is left whole and only the request is
 *       reduced, so replay and forking still see everything that happened. What was sent is recorded
 *       verbatim in the journal either way.</li>
 * </ul>
 *
 * <p>What goes first: the oldest tool results are shortened, because they are usually the largest
 * thing in the conversation and the least needed once acted upon; only then are whole old exchanges
 * removed. The system prompt, the original task and the most recent exchanges are never removed. An
 * assistant turn and the tool results answering it are always removed together, since a tool message
 * whose matching call has gone is rejected by the endpoint outright.
 */
@Component
public class ContextAssembler {

	private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

	private final TokenEstimator estimator;

	private final HarnessProperties.Context settings;

	public ContextAssembler(TokenEstimator estimator, HarnessProperties properties) {
		this.estimator = estimator;
		this.settings = properties.context();
	}

	/**
	 * Produces the message list for one request.
	 *
	 * @param contextLength    the loaded model's window, or null when it is unknown, in which case
	 *                         nothing is compacted and the server decides -- reported as such
	 * @param reserveForOutput tokens the answer will need, which the prompt may not occupy
	 */
	public Assembly assemble(List<Message> messages, List<ToolSchema> tools, Integer contextLength,
			int reserveForOutput) {

		int before = estimator.estimate(messages, tools);
		if (contextLength == null || contextLength <= 0) {
			return Assembly.unmeasured(messages, before);
		}

		int budget = contextLength - Math.max(0, reserveForOutput) - this.settings.safetyMarginTokens();
		if (budget <= 0) {
			// The output allowance alone does not fit the window. No amount of compaction helps.
			return new Assembly(messages, false, 0, 0, before, before, budget, false);
		}
		if (before <= budget) {
			return new Assembly(messages, false, 0, 0, before, before, budget, true);
		}

		Working working = new Working(messages);
		working.shortenToolResults(tools, budget);
		working.dropOldExchanges(tools, budget);

		List<Message> assembled = working.withMarker();
		int after = estimator.estimate(assembled, tools);

		// The note explaining the gap is worth having but is not worth the run. On a conversation
		// whose exchanges are small -- a tool chain returning single numbers, say -- the note can cost
		// more than everything removed to make room for it, and the compaction then ends up larger
		// than what it started from. Measured, not hypothetical: a five-step chain in a 1330-token
		// window came back at 616 tokens after compacting 563.
		if (after > budget) {
			List<Message> withoutNote = working.flatten();
			int bare = estimator.estimate(withoutNote, tools);
			if (bare <= budget) {
				assembled = withoutNote;
				after = bare;
			}
		}

		if (after > budget) {
			log.warn("A request still needs about {} tokens after compaction but only {} are available "
					+ "in a {}-token window; the run cannot continue.", after, budget, contextLength);
		}
		return new Assembly(assembled, true, working.dropped, working.shortened, before, after, budget,
				after <= budget);
	}

	/**
	 * The mutable working copy, kept private so no caller can be handed a half-compacted list.
	 */
	private final class Working {

		private final List<Block> blocks;

		private int dropped;

		private int shortened;

		Working(List<Message> messages) {
			this.blocks = Block.split(messages);
		}

		/**
		 * Shortens tool results, oldest first.
		 *
		 * <p>Recent results are shortened too if it comes to that, but only after every older one has
		 * been cut, and never below the floor: a result trimmed to nothing leaves the model acting on a
		 * tool call whose answer it can no longer see, which produces worse behaviour than a short
		 * answer does.
		 */
		void shortenToolResults(List<ToolSchema> tools, int budget) {
			int floor = ContextAssembler.this.settings.minToolResultChars();
			for (Block block : this.blocks) {
				if (fits(tools, budget)) {
					return;
				}
				if (block.protectedHead) {
					continue;
				}
				for (int i = 0; i < block.messages.size(); i++) {
					Message message = block.messages.get(i);
					if (message.role() != Role.TOOL || message.content() == null
							|| message.content().length() <= floor) {
						continue;
					}
					int removed = message.content().length() - floor;
					block.messages.set(i, message.withContent(message.content().substring(0, floor)
							+ "\n[... %d characters removed to fit the context window]".formatted(removed),
							true));
					this.shortened++;
					if (fits(tools, budget)) {
						return;
					}
				}
			}
		}

		/**
		 * Removes whole exchanges, oldest first, keeping the head and the most recent ones.
		 *
		 * <p>If that is not enough, the guarantee to keep recent exchanges is given up one at a time
		 * rather than the run. Measured against the loaded model: a five-step tool chain in a tight
		 * window reached a point where two exchanges existed and both were protected as "recent", so
		 * nothing was droppable and the run ended {@code ABORTED_CONTEXT} -- while an older exchange
		 * sat right there, droppable, and dropping it let the run finish correctly. Keeping two recent
		 * exchanges is a preference; finishing the run is the point.
		 */
		void dropOldExchanges(List<ToolSchema> tools, int budget) {
			int configured = Math.max(1, ContextAssembler.this.settings.keepRecentExchanges());
			for (int keepRecent = configured; keepRecent >= 1; keepRecent--) {
				while (!fits(tools, budget)) {
					int index = firstEvictable(keepRecent);
					if (index < 0) {
						break;
					}
					this.dropped += this.blocks.get(index).messages.size();
					this.blocks.remove(index);
				}
				if (fits(tools, budget)) {
					return;
				}
			}
		}

		private int firstEvictable(int keepRecent) {
			int lastEvictable = this.blocks.size() - keepRecent;
			for (int i = 0; i < lastEvictable; i++) {
				if (!this.blocks.get(i).protectedHead) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * Whether what would actually be sent fits -- the note about the compaction included.
		 *
		 * <p>Measured against {@link #flatten()} alone, this reported success and then handed back a
		 * list with a couple of hundred characters of explanation prepended, which put the request
		 * back over the budget the compaction had just brought it under. The marker is part of the
		 * request, so it has to be part of the arithmetic. Before anything has been changed there is
		 * no marker and none is counted, which is also correct: a conversation that already fits needs
		 * no explanation.
		 */
		private boolean fits(List<ToolSchema> tools, int budget) {
			return estimator.estimate(withMarker(), tools) <= budget;
		}

		private List<Message> flatten() {
			List<Message> all = new ArrayList<>();
			this.blocks.forEach(block -> all.addAll(block.messages));
			return all;
		}

		/**
		 * The assembled list, with a note saying what is missing.
		 *
		 * <p>The model is told rather than left to infer it. Without the note, a model that has lost
		 * the record of a tool it already ran simply runs it again -- which looks like a repetition
		 * loop and trips the guardrail for one, yet another harness effect that would be read as a
		 * model trait.
		 */
		List<Message> withMarker() {
			List<Message> all = flatten();
			if (this.dropped == 0 && this.shortened == 0) {
				return all;
			}

			String note = ("[Earlier parts of this conversation were removed to fit the context window: "
					+ "%d message(s) dropped, %d tool result(s) shortened. Work from what remains; "
					+ "do not repeat steps whose results you have already been given.]")
							.formatted(this.dropped, this.shortened);

			// The note goes into the system message at position zero, never into a message of its own
			// after the task. Qwen's chat template refuses outright -- "System message must be at the
			// beginning" -- and a compaction that kills the run it was invoked to rescue is worse than
			// no compaction at all. Folding it into the existing system message also costs less than a
			// new turn does, which matters when the whole exercise is reclaiming room.
			if (!all.isEmpty() && all.getFirst().role() == Role.SYSTEM) {
				Message head = all.getFirst();
				all.set(0, head.withContent(head.content() + "\n\n" + note, head.truncated()));
			}
			else {
				all.add(0, Message.system(note));
			}
			return all;
		}
	}

	/**
	 * One exchange: an assistant or user turn, together with any tool results answering it.
	 *
	 * <p>Grouping is what keeps compaction safe. An assistant message carrying {@code tool_calls} and
	 * the {@code tool} messages that answer them are a matched set on the wire, and a request holding
	 * only one half of the pair is rejected.
	 */
	private static final class Block {

		private final List<Message> messages = new ArrayList<>();

		private boolean protectedHead;

		/**
		 * Splits a conversation into exchanges, marking the ones that must survive: the system prompt
		 * and the original task. Losing either turns the run into a different experiment.
		 */
		static List<Block> split(List<Message> messages) {
			List<Block> blocks = new ArrayList<>();
			Block current = null;
			boolean inHead = true;
			boolean taskSeen = false;

			for (Message message : messages) {
				if (message.role() != Role.TOOL || current == null) {
					current = new Block();
					blocks.add(current);
				}
				current.messages.add(message);

				if (inHead && message.role() == Role.SYSTEM) {
					current.protectedHead = true;
				}
				else if (inHead && message.role() == Role.USER && !taskSeen) {
					current.protectedHead = true;
					taskSeen = true;
					inHead = false;
				}
				else {
					inHead = false;
				}
			}
			return blocks;
		}
	}

	/**
	 * What was sent, and what it cost.
	 *
	 * @param compacted    whether anything was removed; drives the journal entry
	 * @param budgetTokens what the prompt was allowed to occupy, after reserving room for the answer
	 * @param fits         false when even the protected head does not fit. No amount of compaction
	 *                     fixes that, so the run stops on it rather than sending a request the server
	 *                     would silently truncate from the wrong end
	 */
	public record Assembly(List<Message> messages, boolean compacted, int droppedMessages,
			int shortenedResults, int estimatedTokensBefore, int estimatedTokensAfter,
			int budgetTokens, boolean fits) {

		/** The window is unknown, so nothing was decided and nothing was changed. */
		static Assembly unmeasured(List<Message> messages, int estimate) {
			return new Assembly(messages, false, 0, 0, estimate, estimate, 0, true);
		}
	}
}
