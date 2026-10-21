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
 * One scenario, one variant, run N times.
 *
 * <p>The unit of measurement is the batch rather than the run, because a single run proves almost
 * nothing about a 4B model: even at temperature zero the same prompt does not always produce the
 * same behaviour. A pass rate over repetitions is the smallest claim worth making.
 */
@Entity
@Table(name = "eval_batch")
public class EvalBatchEntity {

	@Id
	private UUID id;

	@Column(name = "scenario_id", nullable = false)
	private UUID scenarioId;

	@Column(name = "label", nullable = false, length = 200)
	private String label;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "variant_json")
	private String variantJson;

	@Column(name = "repetitions", nullable = false)
	private int repetitions;

	@Column(name = "status", nullable = false, length = 32)
	private String status;

	@Column(name = "completed_count", nullable = false)
	private int completedCount;

	@Column(name = "passed_count", nullable = false)
	private int passedCount;

	@Column(name = "total_input_tokens", nullable = false)
	private int totalInputTokens;

	@Column(name = "total_output_tokens", nullable = false)
	private int totalOutputTokens;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "error")
	private String error;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "ended_at")
	private Instant endedAt;

	protected EvalBatchEntity() {
	}

	public EvalBatchEntity(UUID id, UUID scenarioId, String label, String variantJson,
			int repetitions, Instant startedAt) {
		this.id = id;
		this.scenarioId = scenarioId;
		this.label = label;
		this.variantJson = variantJson;
		this.repetitions = repetitions;
		this.status = "RUNNING";
		this.startedAt = startedAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getScenarioId() {
		return scenarioId;
	}

	public String getLabel() {
		return label;
	}

	public String getVariantJson() {
		return variantJson;
	}

	public int getRepetitions() {
		return repetitions;
	}

	public String getStatus() {
		return status;
	}

	public int getCompletedCount() {
		return completedCount;
	}

	public int getPassedCount() {
		return passedCount;
	}

	public int getTotalInputTokens() {
		return totalInputTokens;
	}

	public int getTotalOutputTokens() {
		return totalOutputTokens;
	}

	public String getError() {
		return error;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	/** Records one repetition's outcome as it completes, so progress is visible while running. */
	public void recordResult(boolean passed, int inputTokens, int outputTokens) {
		this.completedCount++;
		if (passed) {
			this.passedCount++;
		}
		this.totalInputTokens += inputTokens;
		this.totalOutputTokens += outputTokens;
	}

	public void complete(String status, String error) {
		this.status = status;
		this.error = error;
		this.endedAt = Instant.now();
	}

	/** Share of completed repetitions that passed, in the range [0,1]. */
	public double passRate() {
		return completedCount == 0 ? 0d : (double) passedCount / (double) completedCount;
	}
}
