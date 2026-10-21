package com.example.ai_controll.tool.exec.builtin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * How long there is between two dates.
 *
 * <p>Date arithmetic is one of the reliable failure modes of a small model: it will answer "how many
 * days between 2026-03-01 and 2026-06-15" instantly and be wrong by two or three, and it is
 * consistently worse across a leap year. That makes it a good subject rather than a chore -- the
 * right answer is exactly checkable, so a scenario can measure whether offering the tool changes the
 * outcome, and whether the model uses the tool it was given.
 *
 * <p>Months and years are deliberately absent from the unit list. "Two months" is not a fixed length
 * of time, so a tool answering in months would have to choose a convention silently and a scenario
 * asserting on it would be measuring that choice.
 */
@Component
public class DateDiffTool implements BuiltinTool {

	private final ObjectMapper objectMapper;

	public DateDiffTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "date_diff";
	}

	@Override
	public String defaultDescription() {
		return "Returns how much time separates two dates, in the unit you ask for. "
				+ "Dates are ISO-8601, for example 2026-03-01 or 2026-03-01T09:30:00Z. "
				+ "Use this rather than counting days by hand.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "from":{"type":"string","description":"The earlier date, ISO-8601"},
				   "to":{"type":"string","description":"The later date, ISO-8601"},
				   "unit":{"type":"string","description":"Unit for the answer",
				     "enum":[%s],"default":"days"}},
				 "required":["from","to"],
				 "additionalProperties":false}""".formatted(DateToolkit.TIME_UNITS);
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> fromText = Args.text(arguments, "from");
		Optional<String> toText = Args.text(arguments, "to");
		if (fromText.isEmpty() || toText.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					"'from' and 'to' are both required. " + DateToolkit.formatsHint(), 0L);
		}

		Optional<OffsetDateTime> from = DateToolkit.parse(fromText.get());
		Optional<OffsetDateTime> to = DateToolkit.parse(toText.get());
		if (from.isEmpty() || to.isEmpty()) {
			return ToolOutcome.failure("invalid_date",
					"Could not read %s as a date. %s".formatted(
							from.isEmpty() ? "'" + fromText.get() + "'" : "'" + toText.get() + "'",
							DateToolkit.formatsHint()),
					0L);
		}

		String unit = Args.keyword(arguments, "unit").orElse("days");
		Optional<Duration> unitLength = DateToolkit.unitDuration(unit);
		if (unitLength.isEmpty()) {
			return ToolOutcome.failure("unknown_unit",
					"Unknown unit '%s'. Use one of: seconds, minutes, hours, days, weeks."
							.formatted(unit),
					0L);
		}

		Duration between = Duration.between(from.get(), to.get());
		double difference = (double) between.toSeconds() / unitLength.get().toSeconds();

		ObjectNode result = objectMapper.createObjectNode();
		result.put("from", from.get().toString());
		result.put("to", to.get().toString());
		result.put("unit", unit);
		// Whole and fractional both: "how many whole days" and "how long exactly" are different
		// questions and a model asking one should not have to infer the other.
		result.put("difference", Math.round(difference * 1_000_000d) / 1_000_000d);
		result.put("wholeUnits", (long) difference);
		// Negative when 'to' precedes 'from', rather than silently swapping them: the model asked a
		// directional question and reordering it would hide a mistake worth seeing.
		result.put("totalDays", ChronoUnit.DAYS.between(from.get(), to.get()));
		return ToolOutcome.success(result.toString(), 0L);
	}
}
