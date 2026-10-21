package com.example.ai_controll.tool.exec.builtin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Moves a date forwards or backwards.
 *
 * <p>The companion to {@code date_diff}, and the natural second step of a two-tool chain: ask
 * {@code clock_now} what today is, then ask this what the date will be in ninety days. Chains like
 * that are worth having in the built-in set, because a single-tool scenario only measures whether
 * the model can call a tool, not whether it can carry a result from one call into the next.
 *
 * <p>A negative amount moves backwards. That is stated in the description rather than given a
 * separate direction argument: fewer arguments is fewer chances for a small model to get the shape
 * wrong, and whether it works out that it needs {@code -90} is itself informative.
 */
@Component
public class DateShiftTool implements BuiltinTool {

	private final ObjectMapper objectMapper;

	public DateShiftTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "date_shift";
	}

	@Override
	public String defaultDescription() {
		return "Adds time to a date and returns the new date. Use a negative amount to go backwards, "
				+ "for example amount -30 with unit days. Dates are ISO-8601, e.g. 2026-03-01.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "date":{"type":"string","description":"The starting date, ISO-8601"},
				   "amount":{"type":"integer","description":"How much to add; negative goes backwards"},
				   "unit":{"type":"string","description":"Unit of the amount",
				     "enum":[%s],"default":"days"}},
				 "required":["date","amount"],
				 "additionalProperties":false}""".formatted(DateToolkit.TIME_UNITS);
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> dateText = Args.text(arguments, "date");
		if (dateText.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					"'date' is required. " + DateToolkit.formatsHint(), 0L);
		}
		Optional<OffsetDateTime> start = DateToolkit.parse(dateText.get());
		if (start.isEmpty()) {
			return ToolOutcome.failure("invalid_date",
					"Could not read '%s' as a date. %s".formatted(dateText.get(),
							DateToolkit.formatsHint()),
					0L);
		}

		Optional<Integer> amount = Args.integer(arguments, "amount");
		if (amount.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("amount", "a whole number, negative to go backwards"), 0L);
		}

		String unit = Args.keyword(arguments, "unit").orElse("days");
		Optional<Duration> unitLength = DateToolkit.unitDuration(unit);
		if (unitLength.isEmpty()) {
			return ToolOutcome.failure("unknown_unit",
					"Unknown unit '%s'. Use one of: seconds, minutes, hours, days, weeks."
							.formatted(unit),
					0L);
		}

		OffsetDateTime shifted = start.get().plus(unitLength.get().multipliedBy(amount.get()));

		ObjectNode result = objectMapper.createObjectNode();
		result.put("from", start.get().toString());
		result.put("amount", amount.get());
		result.put("unit", unit);
		result.put("result", shifted.toString());
		// A plain date in gets a plain date out. Answering "2026-06-15" with a timestamp and an
		// offset the caller never mentioned makes the result harder to use and harder to assert on.
		if (DateToolkit.isDateOnly(dateText.get())) {
			result.put("resultDate", shifted.format(DateTimeFormatter.ISO_LOCAL_DATE));
		}
		result.put("dayOfWeek", shifted.getDayOfWeek().toString());
		return ToolOutcome.success(result.toString(), 0L);
	}
}
