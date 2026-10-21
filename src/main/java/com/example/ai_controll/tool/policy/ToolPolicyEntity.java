package com.example.ai_controll.tool.policy;

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
 * A rule about which tool calls may run unattended.
 *
 * <p>Rules are matched by glob against the tool name and ordered by priority, lowest first, so a
 * specific rule can override a general one without either having to know about the other.
 */
@Entity
@Table(name = "tool_policy")
public class ToolPolicyEntity {

	@Id
	private UUID id;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "tool_name_pattern", nullable = false)
	private String toolNamePattern;

	@Enumerated(EnumType.STRING)
	@Column(name = "approval_mode", nullable = false, length = 32)
	private ApprovalMode approvalMode;

	@Column(name = "priority", nullable = false)
	private int priority;

	@Column(name = "timeout_ms")
	private Long timeoutMs;

	@Column(name = "rate_limit_per_run")
	private Integer rateLimitPerRun;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ToolPolicyEntity() {
	}

	public ToolPolicyEntity(UUID id, String name, String toolNamePattern, ApprovalMode approvalMode,
			int priority, Long timeoutMs, Integer rateLimitPerRun, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.toolNamePattern = toolNamePattern;
		this.approvalMode = approvalMode;
		this.priority = priority;
		this.timeoutMs = timeoutMs;
		this.rateLimitPerRun = rateLimitPerRun;
		this.enabled = true;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getToolNamePattern() {
		return toolNamePattern;
	}

	public void setToolNamePattern(String toolNamePattern) {
		this.toolNamePattern = toolNamePattern;
	}

	public ApprovalMode getApprovalMode() {
		return approvalMode;
	}

	public void setApprovalMode(ApprovalMode approvalMode) {
		this.approvalMode = approvalMode;
	}

	public int getPriority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public Long getTimeoutMs() {
		return timeoutMs;
	}

	public void setTimeoutMs(Long timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	public Integer getRateLimitPerRun() {
		return rateLimitPerRun;
	}

	public void setRateLimitPerRun(Integer rateLimitPerRun) {
		this.rateLimitPerRun = rateLimitPerRun;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
