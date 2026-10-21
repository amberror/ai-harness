package com.example.ai_controll.orchestrator.store;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.ai_controll.conversation.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message of the materialised transcript.
 *
 * <p>Stored separately from the journal because resuming or forking a run needs a compact, ordered
 * conversation rather than a replay of every diagnostic event.
 */
@Entity
@Table(name = "run_message")
public class RunMessageEntity {

	@Id
	private UUID id;

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(name = "seq", nullable = false)
	private int seq;

	@Column(name = "step_no", nullable = false)
	private int stepNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 16)
	private Role role;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "content")
	private String content;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "reasoning")
	private String reasoning;

	@Column(name = "tool_call_id", length = 120)
	private String toolCallId;

	@Column(name = "tool_name", length = 120)
	private String toolName;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "arguments_json")
	private String argumentsJson;

	@Column(name = "token_estimate")
	private Integer tokenEstimate;

	@Column(name = "truncated", nullable = false)
	private boolean truncated;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected RunMessageEntity() {
	}

	public RunMessageEntity(UUID id, UUID runId, int seq, int stepNo, Role role, String content,
			String reasoning, String toolCallId, String toolName, String argumentsJson,
			Integer tokenEstimate, boolean truncated, Instant createdAt) {
		this.id = id;
		this.runId = runId;
		this.seq = seq;
		this.stepNo = stepNo;
		this.role = role;
		this.content = content;
		this.reasoning = reasoning;
		this.toolCallId = toolCallId;
		this.toolName = toolName;
		this.argumentsJson = argumentsJson;
		this.tokenEstimate = tokenEstimate;
		this.truncated = truncated;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getRunId() {
		return runId;
	}

	public int getSeq() {
		return seq;
	}

	public int getStepNo() {
		return stepNo;
	}

	public Role getRole() {
		return role;
	}

	public String getContent() {
		return content;
	}

	public String getReasoning() {
		return reasoning;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getArgumentsJson() {
		return argumentsJson;
	}

	public Integer getTokenEstimate() {
		return tokenEstimate;
	}

	public boolean isTruncated() {
		return truncated;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
