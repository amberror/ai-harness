package com.example.ai_controll.tool.exec.builtin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Summary statistics over a list of numbers.
 *
 * <p>The reason this one is here is the shape of its argument rather than the arithmetic. Everything
 * else the harness ships takes strings and scalars; this takes an <strong>array</strong>, and
 * whether a quantised model can emit a well-formed JSON array of numbers is a genuinely separate
 * question from whether it can emit a string. Having one tool of each shape is what lets an
 * experiment attribute a failure to the argument type rather than to the task.
 */
@Component
public class StatsSummaryTool implements BuiltinTool {

	private final ObjectMapper objectMapper;

	public StatsSummaryTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "stats_summary";
	}

	@Override
	public String defaultDescription() {
		return "Computes count, sum, mean, median, minimum, maximum and standard deviation for a "
				+ "list of numbers, for example [4, 8, 15, 16, 23, 42].";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{"numbers":{"type":"array","items":{"type":"number"},"minItems":1,
				   "description":"The numbers to summarise"}},
				 "required":["numbers"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<List<Double>> parsed = Args.numbers(arguments, "numbers");
		if (parsed.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("numbers", "an array of numbers, for example [1, 2, 3]"), 0L);
		}
		List<Double> numbers = parsed.get();
		if (numbers.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments", "'numbers' must contain at least one value",
					0L);
		}

		List<Double> sorted = new ArrayList<>(numbers);
		Collections.sort(sorted);

		double sum = numbers.stream().mapToDouble(Double::doubleValue).sum();
		double mean = sum / numbers.size();

		ObjectNode result = objectMapper.createObjectNode();
		result.put("count", numbers.size());
		result.put("sum", round(sum));
		result.put("mean", round(mean));
		result.put("median", round(median(sorted)));
		result.put("min", round(sorted.getFirst()));
		result.put("max", round(sorted.getLast()));
		// Both, named. "Standard deviation" is two different numbers depending on whether the values
		// are the whole population or a sample of one, and a scenario asserting on a single ambiguous
		// field would be measuring which convention the tool happened to pick.
		result.put("stdDevPopulation", round(standardDeviation(numbers, mean, false)));
		result.put("stdDevSample", round(standardDeviation(numbers, mean, true)));
		return ToolOutcome.success(result.toString(), 0L);
	}

	private static double median(List<Double> sorted) {
		int size = sorted.size();
		int middle = size / 2;
		return (size % 2 == 1) ? sorted.get(middle)
				: (sorted.get(middle - 1) + sorted.get(middle)) / 2d;
	}

	private static double standardDeviation(List<Double> numbers, double mean, boolean sample) {
		if (numbers.size() == 1) {
			// A sample of one has no spread to speak of; reporting NaN would be technically defensible
			// and useless to a model that has to read it.
			return 0d;
		}
		double squares = numbers.stream().mapToDouble(value -> (value - mean) * (value - mean)).sum();
		return Math.sqrt(squares / (sample ? numbers.size() - 1 : numbers.size()));
	}

	/** Six decimal places: enough to be exact for anything a model will ask, short enough to read. */
	private static double round(double value) {
		return Math.round(value * 1_000_000d) / 1_000_000d;
	}
}
