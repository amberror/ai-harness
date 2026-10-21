package com.example.ai_controll.orchestrator.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Notices when the model keeps making the same call.
 *
 * <p>Repetition is the characteristic failure of a small model that has lost the thread: it calls a
 * tool, does not understand the result, and calls it again with identical arguments, indefinitely.
 * Nothing about that is an error from the transport's point of view -- every call succeeds -- so
 * without an explicit check the run burns its entire budget making progress-free requests.
 *
 * <p>Arguments are canonicalised before comparison, so {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}}
 * count as the same call. They are the same call; only the serialisation differs.
 */
public class LoopDetector {

	private final int threshold;

	private final Map<String, Integer> counts = new HashMap<>();

	/**
	 * @param threshold how many identical calls are tolerated before the run is stopped. Two is
	 *                  legitimate -- a retry after a failure is normal -- so the default is higher.
	 */
	public LoopDetector(int threshold) {
		this.threshold = Math.max(2, threshold);
	}

	/**
	 * Records a call and reports whether it has now repeated too often.
	 *
	 * @return a description of the loop when the threshold is reached, otherwise empty
	 */
	public Optional<Repetition> record(String toolName, JsonNode arguments) {
		String signature = toolName + "(" + canonicalise(arguments) + ")";
		int count = counts.merge(signature, 1, Integer::sum);
		if (count >= threshold) {
			return Optional.of(new Repetition(signature, count, threshold));
		}
		return Optional.empty();
	}

	/**
	 * Replays a call that happened before this detector existed.
	 *
	 * <p>Used when resuming a parked run: the counts have to be rebuilt from the journal, or a run
	 * that stopped for approval would come back with a clean slate and could loop indefinitely
	 * precisely because a human looked at it.
	 */
	public void seed(String toolName, JsonNode arguments) {
		counts.merge(toolName + "(" + canonicalise(arguments) + ")", 1, Integer::sum);
	}

	/** How often a given call has been seen. */
	public int countFor(String toolName, JsonNode arguments) {
		return counts.getOrDefault(toolName + "(" + canonicalise(arguments) + ")", 0);
	}

	/**
	 * Serialises a node with object keys in a stable order, so that two spellings of the same
	 * arguments produce the same signature.
	 */
	static String canonicalise(JsonNode node) {
		if (node == null || node.isNull()) {
			return "null";
		}
		if (node.isObject()) {
			List<String> names = new ArrayList<>();
			node.propertyNames().forEach(names::add);
			names.sort(String::compareTo);
			StringBuilder builder = new StringBuilder("{");
			for (int i = 0; i < names.size(); i++) {
				if (i > 0) {
					builder.append(',');
				}
				builder.append(names.get(i)).append(':').append(canonicalise(node.get(names.get(i))));
			}
			return builder.append('}').toString();
		}
		if (node.isArray()) {
			StringBuilder builder = new StringBuilder("[");
			for (int i = 0; i < node.size(); i++) {
				if (i > 0) {
					builder.append(',');
				}
				builder.append(canonicalise(node.get(i)));
			}
			return builder.append(']').toString();
		}
		return node.asString();
	}

	/**
	 * @param signature the repeated call, tool name and canonical arguments
	 * @param count     how many times it has now been made
	 * @param threshold the limit that was reached
	 */
	public record Repetition(String signature, int count, int threshold) {

		/**
		 * A correction aimed at the model. Worth one attempt before abandoning the run: sometimes
		 * naming the repetition is enough for the model to change course.
		 */
		public String nudge() {
			return ("You have now called %s %d times with identical arguments and received the same "
					+ "result each time. Repeating it will not produce anything new. Either use the "
					+ "result you already have to answer, call a different tool, or say that you "
					+ "cannot complete the task.").formatted(signature, count);
		}
	}
}
