package com.example.ai_controll.tool.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.store.RunEventEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the journal back the other way: not "what happened in this run" but "how does the model cope
 * with this tool".
 *
 * <p>This is the observation half of learning, and it is deliberately the only half that runs on its
 * own. It changes nothing — it reports what the model did, and every proposal built on top of it goes
 * through a person and then through the ordinary versioned save. A harness that quietly retuned its
 * own tools while measuring them would be an instrument adjusting itself mid-reading.
 */
@Service
public class ToolUsageAnalyzer {

	/** Argument samples kept per tool. Enough to see a pattern, few enough to read. */
	private static final int SAMPLES = 5;

	private static final List<RunEventType> INTERESTING = List.of(
			RunEventType.TOOL_CALL_PARSED, RunEventType.TOOL_CALL_INVALID,
			RunEventType.TOOL_EXEC_SUCCEEDED, RunEventType.TOOL_EXEC_FAILED);

	private final RunEventRepository events;

	private final ObjectMapper objectMapper;

	private final HarnessProperties.Learning settings;

	public ToolUsageAnalyzer(RunEventRepository events, ObjectMapper objectMapper,
			HarnessProperties properties) {
		this.events = events;
		this.objectMapper = objectMapper;
		this.settings = properties.tools().learning();
	}

	/** A report for every tool the journal has seen used, worst first. */
	@Transactional(readOnly = true)
	public List<ToolUsageReport> reportAll() {
		Map<String, Builder> builders = collect();
		List<ToolUsageReport> reports = new ArrayList<>();
		builders.values().forEach(builder -> reports.add(builder.build()));
		// Worst first: the tool the model fights with most is the one worth reading about.
		reports.sort(Comparator.comparingDouble(ToolUsageReport::invalidRate).reversed()
				.thenComparing(Comparator.comparingInt(ToolUsageReport::calls).reversed()));
		return reports;
	}

	@Transactional(readOnly = true)
	public ToolUsageReport report(String tool) {
		Builder builder = collect().get(tool);
		return builder == null ? ToolUsageReport.empty(tool) : builder.build();
	}

	private Map<String, Builder> collect() {
		Map<String, Builder> builders = new LinkedHashMap<>();
		// Newest first, capped: this is a look at recent behaviour, not an audit of all history, and a
		// tool reworked last week should not be judged on how it behaved before that.
		for (RunEventEntity event : events.findRecentByTypes(INTERESTING,
				Limit.of(settings.sampleEvents()))) {
			JsonNode payload = parse(event.getPayload());
			if (payload == null) {
				continue;
			}
			String tool = text(payload, "tool");
			if (tool == null || tool.isBlank()) {
				continue;
			}
			builders.computeIfAbsent(tool, Builder::new).add(event.getType(), payload);
		}
		return builders;
	}

	private JsonNode parse(String payload) {
		if (payload == null || payload.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(payload);
		}
		catch (RuntimeException ex) {
			// One unreadable row is a lost observation, not a lost report.
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value != null && value.isString()) ? value.stringValue() : null;
	}

	/** Accumulates one tool's evidence while the journal is walked once. */
	private static final class Builder {

		private final String tool;

		private final Map<String, Integer> reasons = new LinkedHashMap<>();

		private final Map<String, Integer> fields = new LinkedHashMap<>();

		private final List<String> rejected = new ArrayList<>();

		/** A set, because the same successful call repeated tells you nothing new. */
		private final Set<String> succeeded = new LinkedHashSet<>();

		private int calls;

		private int invalid;

		private int executions;

		private int failedExecutions;

		Builder(String tool) {
			this.tool = tool;
		}

		void add(RunEventType type, JsonNode payload) {
			switch (type) {
				case TOOL_CALL_PARSED -> {
					this.calls++;
					JsonNode arguments = payload.get("arguments");
					if (arguments != null && !arguments.isNull() && this.succeeded.size() < SAMPLES) {
						this.succeeded.add(arguments.toString());
					}
				}
				case TOOL_CALL_INVALID -> {
					this.calls++;
					this.invalid++;
					countReason(payload);
					countFields(payload);
					if (this.rejected.size() < SAMPLES) {
						String raw = payload.get("rawArguments") == null ? null
								: payload.get("rawArguments").asString();
						if (raw != null && !raw.isBlank()) {
							this.rejected.add(raw);
						}
					}
				}
				case TOOL_EXEC_SUCCEEDED -> this.executions++;
				case TOOL_EXEC_FAILED -> {
					this.executions++;
					this.failedExecutions++;
				}
				default -> {
					// Nothing else describes how a tool is being used.
				}
			}
		}

		private void countReason(JsonNode payload) {
			JsonNode reason = payload.get("reason");
			if (reason != null && reason.isString()) {
				this.reasons.merge(reason.stringValue(), 1, Integer::sum);
			}
		}

		/**
		 * The failing argument paths, out of the feedback the model was actually sent.
		 *
		 * <p>The most useful line in the whole report: "$/to omitted, 14 times" names the fix, whereas
		 * a rejection count only says there is one.
		 */
		private void countFields(JsonNode payload) {
			JsonNode feedback = payload.get("feedback");
			if (feedback == null) {
				return;
			}
			JsonNode violations = feedback.get("violations");
			if (violations == null || !violations.isArray()) {
				return;
			}
			for (JsonNode violation : violations) {
				JsonNode path = violation.get("path");
				JsonNode keyword = violation.get("keyword");
				if (path != null && path.isString()) {
					String label = keyword != null && keyword.isString()
							? "%s (%s)".formatted(path.stringValue(), keyword.stringValue())
							: path.stringValue();
					this.fields.merge(label, 1, Integer::sum);
				}
			}
		}

		ToolUsageReport build() {
			return new ToolUsageReport(this.tool, this.calls, this.invalid, this.executions,
					this.failedExecutions, top(this.reasons), top(this.fields), List.copyOf(this.rejected),
					List.copyOf(this.succeeded));
		}

		private static List<ToolUsageReport.Count> top(Map<String, Integer> tally) {
			return tally.entrySet().stream()
					.map(entry -> new ToolUsageReport.Count(entry.getKey(), entry.getValue()))
					.sorted(Comparator.comparingInt(ToolUsageReport.Count::count).reversed())
					.limit(SAMPLES)
					.toList();
		}
	}
}
