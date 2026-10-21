package com.example.ai_controll.orchestrator.approval;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tool call waiting on a person, and everything needed to carry on afterwards.
 *
 * <p>{@code remainingCallsJson} holds the rest of the step's calls. A model may emit several at
 * once, and parking on the second must not quietly discard the third -- which is exactly the sort of
 * bug that would only show up as a run that mysteriously did less than it was asked to.
 */
@Entity
@Table(name = "pending_approval")
public class PendingApprovalEntity {

	@Id
	private UUID id;

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(name = "step_no", nullable = false)
	private int stepNo;

	@Column(name = "call_id", length = 120)
	private String callId;

	@Column(name = "tool_name", nullable = false, length = 120)
	private String toolName;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "arguments_json")
	private String argumentsJson;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "remaining_calls_json")
	private String remainingCallsJson;

	@Column(name = "policy_name", length = 120)
	private String policyName;

	@Column(name = "reason", length = 500)
	private String reason;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", length = 16)
	private ApprovalDecision decision;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", length = 16)
	private ApprovalScope scope;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "note", length = 500)
	private String note;

	protected PendingApprovalEntity() {
	}

	public PendingApprovalEntity(UUID id, UUID runId, int stepNo, String callId, String toolName,
			String argumentsJson, String remainingCallsJson, String policyName, String reason,
			Instant requestedAt) {
		this.id = id;
		this.runId = runId;
		this.stepNo = stepNo;
		this.callId = callId;
		this.toolName = toolName;
		this.argumentsJson = argumentsJson;
		this.remainingCallsJson = remainingCallsJson;
		this.policyName = policyName;
		this.reason = reason;
		this.requestedAt = requestedAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getRunId() {
		return runId;
	}

	public int getStepNo() {
		return stepNo;
	}

	public String getCallId() {
		return callId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getArgumentsJson() {
		return argumentsJson;
	}

	public String getRemainingCallsJson() {
		return remainingCallsJson;
	}

	public String getPolicyName() {
		return policyName;
	}

	public String getReason() {
		return reason;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public ApprovalDecision getDecision() {
		return decision;
	}

	public ApprovalScope getScope() {
		return scope;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public String getNote() {
		return note;
	}

	public boolean isPending() {
		return decision == null;
	}

	public void decide(ApprovalDecision decision, ApprovalScope scope, String note) {
		this.decision = decision;
		this.scope = scope;
		this.note = note;
		this.decidedAt = Instant.now();
	}
}
