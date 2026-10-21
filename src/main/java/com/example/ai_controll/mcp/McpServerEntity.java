package com.example.ai_controll.mcp;

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
 * A configured MCP server.
 *
 * <p>Connection details are data rather than code, which is what makes servers addable at runtime --
 * and is also exactly why launching one is gated by an allowlist. For a stdio server this row holds
 * a command that will be executed.
 */
@Entity
@Table(name = "mcp_server")
public class McpServerEntity {

	@Id
	private UUID id;

	@Column(name = "label", nullable = false, length = 120)
	private String label;

	@Column(name = "description", length = 1000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "transport", nullable = false, length = 32)
	private McpTransport transport;

	@Column(name = "command", length = 500)
	private String command;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "args_json")
	private String argsJson;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "env_json")
	private String envJson;

	@Column(name = "url", length = 1000)
	private String url;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "headers_json")
	private String headersJson;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Column(name = "status", nullable = false, length = 32)
	private String status;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "last_error")
	private String lastError;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected McpServerEntity() {
	}

	public McpServerEntity(UUID id, String label, McpTransport transport, Instant now) {
		this.id = id;
		this.label = label;
		this.transport = transport;
		this.enabled = true;
		this.status = "UNKNOWN";
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getLabel() {
		return label;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public McpTransport getTransport() {
		return transport;
	}

	public void setTransport(McpTransport transport) {
		this.transport = transport;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public String getArgsJson() {
		return argsJson;
	}

	public void setArgsJson(String argsJson) {
		this.argsJson = argsJson;
	}

	public String getEnvJson() {
		return envJson;
	}

	public void setEnvJson(String envJson) {
		this.envJson = envJson;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getHeadersJson() {
		return headersJson;
	}

	public void setHeadersJson(String headersJson) {
		this.headersJson = headersJson;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getStatus() {
		return status;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/** Records the outcome of the most recent connection attempt. */
	public void recordStatus(String status, String error) {
		this.status = status;
		this.lastError = error;
		this.updatedAt = Instant.now();
	}

	public void recordSynced() {
		this.lastSyncedAt = Instant.now();
		this.updatedAt = this.lastSyncedAt;
	}

	public boolean isStdio() {
		return transport == McpTransport.STDIO;
	}
}
