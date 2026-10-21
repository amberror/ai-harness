package com.example.ai_controll.tool.learning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.store.RunEventEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading the journal back as evidence about a tool rather than about a run.
 *
 * <p>The claim being tested is that the harness has had this information all along. Every rejected
 * call has carried the failing field path since phase 2 — it was recorded, sent to the model as a
 * correction, and then never looked at again.
 */
class ToolUsageAnalyzerTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final List<RunEventEntity> journal = new ArrayList<>();

	private ToolUsageAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		RunEventRepository events = mock(RunEventRepository.class);
		when(events.findRecentByTypes(any(), any())).thenReturn(journal);
		this.analyzer = new ToolUsageAnalyzer(events, mapper, TestProperties.defaults());
	}

	private void record(RunEventType type, String payload) {
		journal.add(new RunEventEntity(UUID.randomUUID(), journal.size(), 1, type, Instant.now(), null,
				payload));
	}

	private void parsed(String tool, String arguments) {
		record(RunEventType.TOOL_CALL_PARSED,
				"{\"tool\":\"%s\",\"arguments\":%s}".formatted(tool, arguments));
	}

	/**
	 * Built with the mapper rather than by formatting a string: the interesting values here are
	 * fragments of JSON the model got wrong, and embedding those in JSON by hand produces a payload
	 * that does not parse -- which the analyzer then correctly skips, and the test silently measures
	 * nothing.
	 */
	private void invalid(String tool, String raw, String reason, String path, String keyword) {
		ObjectNode violation = mapper.createObjectNode();
		violation.put("path", path);
		violation.put("message", "nope");
		violation.put("keyword", keyword);

		ObjectNode feedback = mapper.createObjectNode();
		feedback.putArray("violations").add(violation);

		ObjectNode payload = mapper.createObjectNode();
		payload.put("tool", tool);
		payload.put("rawArguments", raw);
		payload.put("reason", reason);
		payload.set("feedback", feedback);

		record(RunEventType.TOOL_CALL_INVALID, payload.toString());
	}

	@Test
	@DisplayName("counts what the model got right and wrong per tool")
	void countsPerTool() {
		parsed("unit_convert", "{\"value\":3,\"from\":\"m\",\"to\":\"cm\"}");
		invalid("unit_convert", "{}", "SCHEMA_VIOLATION", "$/to", "required");
		record(RunEventType.TOOL_EXEC_SUCCEEDED, "{\"tool\":\"unit_convert\"}");
		parsed("calc_eval", "{\"expression\":\"2+2\"}");

		List<ToolUsageReport> reports = analyzer.reportAll();

		assertThat(reports).hasSize(2);
		ToolUsageReport convert = reports.getFirst();
		// Worst first: the tool the model fights with is the one worth reading about.
		assertThat(convert.tool()).isEqualTo("unit_convert");
		assertThat(convert.calls()).isEqualTo(2);
		assertThat(convert.invalid()).isEqualTo(1);
		assertThat(convert.invalidRate()).isEqualTo(0.5d);
		assertThat(convert.executions()).isEqualTo(1);
	}

	@Test
	@DisplayName("names the argument that keeps failing, which is the actionable part")
	void ranksFailingFields() {
		for (int i = 0; i < 6; i++) {
			invalid("unit_convert", "{\"value\":3}", "SCHEMA_VIOLATION", "$/to", "required");
		}
		invalid("unit_convert", "{\"value\":3,\"from\":\"parsecs\"}", "SCHEMA_VIOLATION", "$/from", "enum");

		ToolUsageReport report = analyzer.report("unit_convert");

		// "the model is bad at unit_convert" versus "it omits 'to' six times out of seven".
		assertThat(report.fields()).isNotEmpty();
		assertThat(report.fields().getFirst().value()).contains("$/to").contains("required");
		assertThat(report.fields().getFirst().count()).isEqualTo(6);
	}

	@Test
	@DisplayName("keeps the arguments verbatim, both the refused and the accepted")
	void keepsSamples() {
		invalid("unit_convert", "{'value': 3}", "MALFORMED_JSON", "$", "json");
		parsed("unit_convert", "{\"value\":3,\"from\":\"m\",\"to\":\"cm\"}");

		ToolUsageReport report = analyzer.report("unit_convert");

		// Exactly as the model wrote it: single quotes are the mistake, and paraphrasing hides it.
		assertThat(report.rejectedSamples()).anyMatch(sample -> sample.contains("'value'"));
		assertThat(report.successfulCalls()).anyMatch(sample -> sample.contains("\"to\":\"cm\""));
	}

	@Test
	@DisplayName("identical successful calls count once, because repetition teaches nothing")
	void deduplicatesSuccessfulCalls() {
		for (int i = 0; i < 5; i++) {
			parsed("clock_now", "{}");
		}
		parsed("clock_now", "{\"zone\":\"UTC\"}");

		assertThat(analyzer.report("clock_now").successfulCalls()).hasSize(2);
	}

	@Test
	@DisplayName("a handful of calls is flagged as too little to act on")
	void refusesToOverreadSmallSamples() {
		invalid("calc_eval", "{}", "SCHEMA_VIOLATION", "$/expression", "required");
		parsed("calc_eval", "{\"expression\":\"1+1\"}");

		ToolUsageReport report = analyzer.report("calc_eval");

		// 50% off two calls is the same error the confidence intervals prevent one page over.
		assertThat(report.hasEvidence()).isTrue();
		assertThat(report.isSubstantial()).isFalse();
	}

	@Test
	@DisplayName("a tool nobody has called reports nothing rather than failing")
	void handlesUnknownTools() {
		ToolUsageReport report = analyzer.report("never_used");

		assertThat(report.hasEvidence()).isFalse();
		assertThat(report.invalidRate()).isZero();
		assertThat(report.fields()).isEmpty();
	}

	@Test
	@DisplayName("an unreadable journal row costs that row, not the report")
	void survivesBadPayloads() {
		record(RunEventType.TOOL_CALL_PARSED, "not json at all");
		record(RunEventType.TOOL_CALL_PARSED, null);
		parsed("calc_eval", "{\"expression\":\"1+1\"}");

		assertThat(analyzer.report("calc_eval").calls()).isEqualTo(1);
	}
}
