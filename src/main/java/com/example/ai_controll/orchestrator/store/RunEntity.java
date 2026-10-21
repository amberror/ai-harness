package com.example.ai_controll.orchestrator.store;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.ai_controll.orchestrator.domain.RunStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One execution of an agent against one input. */
@Entity
@Table(name = "run")
public class RunEntity {

	@Id
	private UUID id;

	@Column(name = "agent_profile_id")
	private UUID agentProfileId;

	@Column(name = "parent_run_id")
	private UUID parentRunId;

	@Column(name = "forked_from_seq")
	private Long forkedFromSeq;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private RunStatus status;

	@Column(name = "model_key", nullable = false)
	private String modelKey;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "input")
	private String input;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "final_output")
	private String finalOutput;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "error")
	private String error;

	/**
	 * The settings this run was started with.
	 *
	 * <p>Held here because a parked run has to be resumable: the model, the toolset and every
	 * guardrail limit otherwise live only in the request that started it. It also makes a run
	 * reproducible, which is what comparing one configuration against another will need.
	 */
	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "config_json")
	private String configJson;

	@Column(name = "steps_used", nullable = false)
	private int stepsUsed;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	/**
	 * Tracked apart from output tokens because on this model reasoning routinely dwarfs the answer,
	 * and a resumed run rebuilding its budget from input/output alone would believe it had spent far
	 * less than it had.
	 */
	@Column(name = "reasoning_tokens", nullable = false)
	private int reasoningTokens;

	@Column(name = "tool_calls_total", nullable = false)
	private int toolCallsTotal;

	@Column(name = "tool_calls_invalid", nullable = false)
	private int toolCallsInvalid;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "ended_at")
	private Instant endedAt;

	protected RunEntity() {
	}

	public RunEntity(UUID id, String modelKey, String input, Instant startedAt) {
		this.id = id;
		this.modelKey = modelKey;
		this.input = input;
		this.startedAt = startedAt;
		this.status = RunStatus.RUNNING;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAgentProfileId() {
		return agentProfileId;
	}

	public void setAgentProfileId(UUID agentProfileId) {
		this.agentProfileId = agentProfileId;
	}

	public UUID getParentRunId() {
		return parentRunId;
	}

	public void setParentRunId(UUID parentRunId) {
		this.parentRunId = parentRunId;
	}

	public Long getForkedFromSeq() {
		return forkedFromSeq;
	}

	public void setForkedFromSeq(Long forkedFromSeq) {
		this.forkedFromSeq = forkedFromSeq;
	}

	public RunStatus getStatus() {
		return status;
	}

	public void setStatus(RunStatus status) {
		this.status = status;
	}

	public String getModelKey() {
		return modelKey;
	}

	public String getInput() {
		return input;
	}

	public String getFinalOutput() {
		return finalOutput;
	}

	public void setFinalOutput(String finalOutput) {
		this.finalOutput = finalOutput;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public int getStepsUsed() {
		return stepsUsed;
	}

	public void setStepsUsed(int stepsUsed) {
		this.stepsUsed = stepsUsed;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public int getToolCallsTotal() {
		return toolCallsTotal;
	}

	public void setToolCallsTotal(int toolCallsTotal) {
		this.toolCallsTotal = toolCallsTotal;
	}

	public int getToolCallsInvalid() {
		return toolCallsInvalid;
	}

	public void setToolCallsInvalid(int toolCallsInvalid) {
		this.toolCallsInvalid = toolCallsInvalid;
	}

	public int getReasoningTokens() {
		return reasoningTokens;
	}

	public String getConfigJson() {
		return configJson;
	}

	public void setConfigJson(String configJson) {
		this.configJson = configJson;
	}

	/**
	 * Records the usage of a whole run.
	 *
	 * <p>Assigned rather than accumulated: a resumed run's tracker is seeded with what was already
	 * spent, so adding its total again would double-count everything before the pause.
	 */
	public void setUsage(int promptTokens, int completionTokens, int reasoning) {
		this.inputTokens = promptTokens;
		this.outputTokens = completionTokens;
		this.reasoningTokens = reasoning;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Instant endedAt) {
		this.endedAt = endedAt;
	}
}
