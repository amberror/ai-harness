package com.example.ai_controll.tool.exec;

import java.util.UUID;

import com.example.ai_controll.tool.registry.ToolDefinitionEntity;

import tools.jackson.databind.JsonNode;

/**
 * One validated request to run a tool.
 *
 * <p>Arguments arrive parsed and schema-checked, so an executor never has to defend against a
 * hallucinated field or a malformed document; that was settled before the call got here.
 *
 * @param runId     the run this belongs to, for the journal and for per-run rate limits
 * @param stepNo    which loop iteration asked for it
 * @param callId    the model's identifier for the call, used to address the reply back to it
 * @param tool      the stored definition, including its executor configuration
 * @param arguments the validated arguments
 */
public record ToolInvocation(UUID runId, int stepNo, String callId, ToolDefinitionEntity tool,
		JsonNode arguments) {

	public String toolName() {
		return tool.getName();
	}
}
