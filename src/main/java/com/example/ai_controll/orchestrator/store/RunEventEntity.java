package com.example.ai_controll.orchestrator.store;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.ai_controll.orchestrator.domain.RunEventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One entry in the append-only run journal.
 *
 * <p>There are no setters beyond construction: the journal is a record of what happened, and an
 * event that can be edited after the fact is not evidence.
 */
@Entity
@Table(name = "run_event")
public class RunEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(name = "seq", nullable = false)
	private long seq;

	@Column(name = "step_no", nullable = false)
	private int stepNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 48)
	private RunEventType type;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "duration_ms")
	private Long durationMs;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "payload")
	private String payload;

	protected RunEventEntity() {
	}

	public RunEventEntity(UUID runId, long seq, int stepNo, RunEventType type, Instant occurredAt,
			Long durationMs, String payload) {
		this.runId = runId;
		this.seq = seq;
		this.stepNo = stepNo;
		this.type = type;
		this.occurredAt = occurredAt;
		this.durationMs = durationMs;
		this.payload = payload;
	}

	public Long getId() {
		return id;
	}

	public UUID getRunId() {
		return runId;
	}

	public long getSeq() {
		return seq;
	}

	public int getStepNo() {
		return stepNo;
	}

	public RunEventType getType() {
		return type;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public String getPayload() {
		return payload;
	}
}
