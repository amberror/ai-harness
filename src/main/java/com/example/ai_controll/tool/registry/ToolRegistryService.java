package com.example.ai_controll.tool.registry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.tool.schema.ToolSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalogue of tools, and the only place they change.
 *
 * <p>Every edit creates a new version rather than mutating the existing row. That costs a little
 * storage and buys the ability to ask the question this project exists to answer: when the model
 * started getting a tool wrong, what exactly did the tool look like at the time? A registry that
 * overwrote its rows could only say what the tool looks like now.
 */
@Service
public class ToolRegistryService {

	private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

	/**
	 * Tool names must be plain identifiers. Models reproduce {@code get_weather} reliably and mangle
	 * anything with spaces, dots or unicode in it, so the constraint is enforced rather than advised.
	 */
	private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");

	private final ToolDefinitionRepository repository;

	private final ObjectMapper objectMapper;

	public ToolRegistryService(ToolDefinitionRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	/**
	 * Creates a tool, or supersedes the existing one with a new version.
	 *
	 * @param changeNote why this version exists; free text, shown in the version history
	 */
	@Transactional
	public ToolDefinitionEntity save(ToolDefinition definition, String changeNote) {
		validate(definition);

		Optional<ToolDefinitionEntity> existing = repository.findByCurrentOf(definition.name());
		existing.ifPresent(ToolDefinitionEntity::supersede);
		// Flush the retired pointer before claiming it, or the unique constraint on current_of would
		// reject the new row.
		existing.ifPresent(repository::saveAndFlush);

		int version = repository.highestVersion(definition.name()) + 1;
		ToolDefinitionEntity entity = new ToolDefinitionEntity(UUID.randomUUID(), definition.name(),
				version, true, definition.description(), definition.parametersSchema().toString(),
				definition.executorType(), definition.executorConfig() == null ? null
						: definition.executorConfig().toString(),
				definition.enabled(), definition.source(), definition.timeoutMs(), Instant.now(),
				changeNote);

		log.info("Saved tool '{}' as version {} ({})", definition.name(), version,
				version == 1 ? "new" : "supersedes v" + (version - 1));
		return repository.save(entity);
	}

	/** Turns a tool on or off without creating a version; availability is not a schema change. */
	@Transactional
	public void setEnabled(String name, boolean enabled) {
		repository.findByCurrentOf(name).ifPresentOrElse(entity -> entity.setEnabled(enabled), () -> {
			throw new IllegalArgumentException("No tool named '%s'".formatted(name));
		});
	}

	/**
	 * Retires a tool by superseding its current version without replacing it.
	 *
	 * <p>History is kept deliberately: a run that called the tool must remain explicable afterwards,
	 * and it would not be if the definition it used had been erased.
	 */
	@Transactional
	public void delete(String name) {
		repository.findByCurrentOf(name).ifPresent(entity -> {
			entity.supersede();
			repository.save(entity);
			log.info("Retired tool '{}' at version {}; earlier versions are kept for the record",
					name, entity.getVersion());
		});
	}

	@Transactional(readOnly = true)
	public List<ToolDefinitionEntity> listCurrent() {
		return repository.findByCurrentOfNotNullOrderByNameAsc();
	}

	@Transactional(readOnly = true)
	public Optional<ToolDefinitionEntity> findCurrent(String name) {
		return repository.findByCurrentOf(name);
	}

	@Transactional(readOnly = true)
	public List<ToolDefinitionEntity> history(String name) {
		return repository.findByNameOrderByVersionDesc(name);
	}

	@Transactional(readOnly = true)
	public Optional<ToolDefinitionEntity> findVersion(String name, int version) {
		return repository.findByNameAndVersion(name, version);
	}

	public boolean exists(String name) {
		return repository.existsByName(name);
	}

	/** Renders a stored definition into the form the model is shown. */
	public ToolSchema toSchema(ToolDefinitionEntity entity) {
		return new ToolSchema(entity.getName(), entity.getDescription(),
				objectMapper.readTree(entity.getParamsSchema()));
	}

	public JsonNode parseSchema(ToolDefinitionEntity entity) {
		return objectMapper.readTree(entity.getParamsSchema());
	}

	public JsonNode parseExecutorConfig(ToolDefinitionEntity entity) {
		String config = entity.getExecutorConfig();
		return (config == null || config.isBlank()) ? objectMapper.createObjectNode()
				: objectMapper.readTree(config);
	}

	private void validate(ToolDefinition definition) {
		if (definition.name() == null || !VALID_NAME.matcher(definition.name()).matches()) {
			throw new IllegalArgumentException(
					"Tool name '%s' is not usable. Use letters, digits and underscores, starting with a "
							+ "letter -- a model reproduces those reliably and mangles anything else."
									.formatted(definition.name()));
		}
		if (definition.description() == null || definition.description().isBlank()) {
			throw new IllegalArgumentException(
					"Tool '%s' needs a description. For a small model it is the single most influential "
							+ "piece of text in the request.".formatted(definition.name()));
		}
		JsonNode schema = definition.parametersSchema();
		if (schema == null || !schema.isObject()) {
			throw new IllegalArgumentException("Tool '%s' needs a JSON Schema object for its arguments."
					.formatted(definition.name()));
		}
		JsonNode type = schema.get("type");
		if (type == null || !"object".equals(type.asString())) {
			throw new IllegalArgumentException(
					"The schema for '%s' must be of type 'object': tool arguments are always a named set, "
							+ "never a bare value.".formatted(definition.name()));
		}
	}

	/**
	 * A tool as submitted, before it is versioned and stored.
	 *
	 * @param executorConfig shape depends on {@code executorType}; null for tools that need none
	 * @param timeoutMs      per-call ceiling, or null to use the global default
	 */
	public record ToolDefinition(String name, String description, JsonNode parametersSchema,
			ExecutorType executorType, JsonNode executorConfig, boolean enabled, ToolSource source,
			Long timeoutMs) {
	}
}
