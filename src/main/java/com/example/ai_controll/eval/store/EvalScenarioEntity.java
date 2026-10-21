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
 * One thing to ask the model, and what "right" means for it.
 *
 * <p>Stored rather than kept in a file so it is editable in the console like everything else, with
 * YAML import and export for committing a scenario next to the code it measures.
 */
@Entity
@Table(name = "eval_scenario")
public class EvalScenarioEntity {

	@Id
	private UUID id;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "description", length = 1000)
	private String description;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "input", nullable = false)
	private String input;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "system_prompt")
	private String systemPrompt;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "tools_json")
	private String toolsJson;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "config_json")
	private String configJson;

	@JdbcTypeCode(SqlTypes.CLOB)
	@Column(name = "assertions_json", nullable = false)
	private String assertionsJson;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected EvalScenarioEntity() {
	}

	public EvalScenarioEntity(UUID id, String name, String input, String assertionsJson, Instant now) {
		this.id = id;
		this.name = name;
		this.input = input;
		this.assertionsJson = assertionsJson;
		this.enabled = true;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getInput() {
		return input;
	}

	public void setInput(String input) {
		this.input = input;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public String getToolsJson() {
		return toolsJson;
	}

	public void setToolsJson(String toolsJson) {
		this.toolsJson = toolsJson;
	}

	public String getConfigJson() {
		return configJson;
	}

	public void setConfigJson(String configJson) {
		this.configJson = configJson;
	}

	public String getAssertionsJson() {
		return assertionsJson;
	}

	public void setAssertionsJson(String assertionsJson) {
		this.assertionsJson = assertionsJson;
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

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void touch() {
		this.updatedAt = Instant.now();
	}
}
