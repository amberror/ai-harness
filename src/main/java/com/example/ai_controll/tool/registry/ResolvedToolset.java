package com.example.ai_controll.tool.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.ai_controll.tool.schema.ToolSchema;

/**
 * The tools offered to the model on one step, resolved once and reused for the whole run.
 *
 * <p>Holding the schemas and the definitions together matters: validation needs the schema the model
 * was shown, and execution needs the definition that schema came from. Resolving them separately
 * would leave room for the two to disagree after an edit mid-run.
 */
public record ResolvedToolset(List<ToolSchema> schemas, Map<String, ToolDefinitionEntity> byName) {

	public static ResolvedToolset of(List<ToolDefinitionEntity> definitions,
			java.util.function.Function<ToolDefinitionEntity, ToolSchema> toSchema) {
		Map<String, ToolDefinitionEntity> index = new LinkedHashMap<>();
		definitions.forEach(definition -> index.put(definition.getName(), definition));
		return new ResolvedToolset(definitions.stream().map(toSchema).toList(),
				Map.copyOf(index));
	}

	public static ResolvedToolset empty() {
		return new ResolvedToolset(List.of(), Map.of());
	}

	public boolean isEmpty() {
		return schemas.isEmpty();
	}

	public Optional<ToolDefinitionEntity> definition(String name) {
		return Optional.ofNullable(byName.get(name));
	}

	public List<String> names() {
		return schemas.stream().map(ToolSchema::name).toList();
	}
}
