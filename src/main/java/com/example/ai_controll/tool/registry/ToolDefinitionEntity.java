package com.example.ai_controll.tool.registry;

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
 * One immutable version of a tool.
 *
 * <p>Editing a tool never mutates a row; it creates a new version and moves the current pointer.
 * That is what makes the registry useful for experiments rather than merely configurable: when a
 * model's behaviour changes, the question is always "against which version of the schema", and a
 * mutable row cannot answer it.
 *
 * <p>{@code currentOf} holds the tool name while this row is the current version and is null
 * otherwise. Since both H2 and PostgreSQL treat nulls as distinct in a unique constraint, that
 * enforces "at most one current version per name" in the database rather than in application code.
 */
@Entity
@Table(name = "tool_definition")
public class ToolDefinitionEntity {

	@Id
	private UUID id;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "version", nullable = false)
	private int version;

	@Column(name = "current_of", length = 120)
	private String currentOf;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "description", nullable = false)
	private String description;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "params_schema", nullable = false)
	private String paramsSchema;

	@Enumerated(EnumType.STRING)
	@Column(name = "executor_type", nullable = false, length = 32)
	private ExecutorType executorType;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "executor_config")
	private String executorConfig;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 32)
	private ToolSource source;

	@Column(name = "mcp_server_id")
	private UUID mcpServerId;

	@Column(name = "timeout_ms")
	private Long timeoutMs;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "change_note", length = 500)
	private String changeNote;

	protected ToolDefinitionEntity() {
	}

	public ToolDefinitionEntity(UUID id, String name, int version, boolean current, String description,
			String paramsSchema, ExecutorType executorType, String executorConfig, boolean enabled,
			ToolSource source, Long timeoutMs, Instant createdAt, String changeNote) {
		this.id = id;
		this.name = name;
		this.version = version;
		this.currentOf = current ? name : null;
		this.description = description;
		this.paramsSchema = paramsSchema;
		this.executorType = executorType;
		this.executorConfig = executorConfig;
		this.enabled = enabled;
		this.source = source;
		this.timeoutMs = timeoutMs;
		this.createdAt = createdAt;
		this.changeNote = changeNote;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public int getVersion() {
		return version;
	}

	public boolean isCurrent() {
		return currentOf != null;
	}

	/** Retires this version so a newer one can take the current pointer. */
	public void supersede() {
		this.currentOf = null;
	}

	public String getDescription() {
		return description;
	}

	public String getParamsSchema() {
		return paramsSchema;
	}

	public ExecutorType getExecutorType() {
		return executorType;
	}

	public String getExecutorConfig() {
		return executorConfig;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public ToolSource getSource() {
		return source;
	}

	public UUID getMcpServerId() {
		return mcpServerId;
	}

	public void setMcpServerId(UUID mcpServerId) {
		this.mcpServerId = mcpServerId;
	}

	public Long getTimeoutMs() {
		return timeoutMs;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getChangeNote() {
		return changeNote;
	}
}
