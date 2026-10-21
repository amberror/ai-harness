package com.example.ai_controll.eval.store;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One repetition's outcome.
 *
 * <p>The {@code runId} is the important field. An aggregate pass rate with no way back to the
 * evidence tells you that something is wrong without ever telling you what; from here a failing
 * repetition opens straight into the run's journal.
 */
@Entity
@Table(name = "eval_result")
public class EvalResultEntity {

	@Id
	private UUID id;

	@Column(name = "batch_id", nullable = false)
	private UUID batchId;

	@Column(name = "run_id")
	private UUID runId;

	@Column(name = "repetition", nullable = false)
	private int repetition;

	@Column(name = "passed", nullable = false)
	private boolean passed;

	@Column(name = "run_status", length = 32)
	private String runStatus;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "assertions_json")
	private String assertionsJson;

	@Column(name = "duration_ms")
	private Long durationMs;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected EvalResultEntity() {
	}

	public EvalResultEntity(UUID id, UUID batchId, UUID runId, int repetition, boolean passed,
			String runStatus, String assertionsJson, Long durationMs, int inputTokens,
			int outputTokens, Instant createdAt) {
		this.id = id;
		this.batchId = batchId;
		this.runId = runId;
		this.repetition = repetition;
		this.passed = passed;
		this.runStatus = runStatus;
		this.assertionsJson = assertionsJson;
		this.durationMs = durationMs;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getBatchId() {
		return batchId;
	}

	public UUID getRunId() {
		return runId;
	}

	public int getRepetition() {
		return repetition;
	}

	public boolean isPassed() {
		return passed;
	}

	public String getRunStatus() {
		return runStatus;
	}

	public String getAssertionsJson() {
		return assertionsJson;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
