package com.example.ai_controll.tool.exec.builtin;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;

/**
 * The current time.
 *
 * <p>Trivial, zero-argument and incapable of failing, which is exactly what makes it valuable: if
 * the model cannot call this one, the fault is in the harness, the prompt or the tool schema, never
 * in the tool. It is the first thing to try when a loop is not working.
 */
@Component
public class ClockNowTool implements BuiltinTool {

	private final Clock clock;

	public ClockNowTool(Clock clock) {
		this.clock = clock;
	}

	@Override
	public String name() {
		return "clock_now";
	}

	@Override
	public String defaultDescription() {
		return "Returns the current date and time. Takes no arguments.";
	}

	@Override
	public String defaultSchema() {
		return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		ZonedDateTime now = ZonedDateTime.now(clock);
		return ToolOutcome.success(
				"{\"now\":\"%s\",\"zone\":\"%s\"}".formatted(
						now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), now.getZone()),
				0L);
	}
}
