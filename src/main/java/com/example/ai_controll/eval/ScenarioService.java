package com.example.ai_controll.eval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.eval.assertion.AssertionSpec;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultRepository;
import com.example.ai_controll.eval.store.EvalScenarioRepository;
import com.example.ai_controll.eval.store.EvalScenarioEntity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Stores scenarios, and moves them between the database and YAML.
 *
 * <p>Scenarios live in the database so they are editable in the console like tools and policies, but
 * an experiment's definition is most valuable committed next to the code it was measuring — six
 * months later "what exactly was I testing" is answerable from a diff and not from a local H2 file.
 * Import and export bridge the two rather than forcing a choice between them.
 */
@Service
public class ScenarioService {

	private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

	private final EvalScenarioRepository scenarios;

	private final ObjectMapper objectMapper;

	/** Separate mapper: YAML is only ever used at this boundary. */
	private final YAMLMapper yamlMapper = YAMLMapper.builder().build();

	public ScenarioService(EvalScenarioRepository scenarios, ObjectMapper objectMapper) {
		this.scenarios = scenarios;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public List<EvalScenarioEntity> list() {
		return scenarios.findAllByOrderByNameAsc();
	}

	@Transactional(readOnly = true)
	public Optional<EvalScenarioEntity> find(UUID id) {
		return scenarios.findById(id);
	}

	@Transactional(readOnly = true)
	public Optional<EvalScenarioEntity> findByName(String name) {
		return scenarios.findByName(name);
	}

	/** Creates a scenario, or replaces the one with the same name. */
	@Transactional
	public EvalScenarioEntity save(ScenarioDefinition definition) {
		validate(definition);

		EvalScenarioEntity entity = scenarios.findByName(definition.name())
				.orElseGet(() -> new EvalScenarioEntity(UUID.randomUUID(), definition.name(),
						definition.input(), "[]", Instant.now()));

		entity.setDescription(definition.description());
		entity.setInput(definition.input());
		entity.setSystemPrompt(definition.systemPrompt());
		entity.setToolsJson(objectMapper.writeValueAsString(
				definition.tools() == null ? List.of() : definition.tools()));
		entity.setConfigJson(definition.config() == null ? null
				: objectMapper.writeValueAsString(definition.config()));
		entity.setAssertionsJson(objectMapper.writeValueAsString(definition.assertions()));
		entity.setEnabled(definition.enabled() == null || definition.enabled());
		entity.touch();

		return scenarios.save(entity);
	}

	@Transactional
	public void delete(UUID id) {
		scenarios.deleteById(id);
	}

	/** Renders a scenario as YAML, for committing alongside the code it measures. */
	@Transactional(readOnly = true)
	public String toYaml(EvalScenarioEntity entity) {
		return yamlMapper.writeValueAsString(toDefinition(entity));
	}

	/** Renders every scenario as one YAML document list. */
	@Transactional(readOnly = true)
	public String exportAll() {
		return yamlMapper.writeValueAsString(list().stream().map(this::toDefinition).toList());
	}

	/**
	 * Reads scenarios from YAML, accepting either a single scenario or a list of them.
	 *
	 * <p>Both shapes are accepted because both are natural to write: one file per scenario reads
	 * cleanly in a diff, and one file for a suite is easier to run in a batch. Rejecting either would
	 * be a gratuitous obstacle.
	 */
	@Transactional
	public List<EvalScenarioEntity> importYaml(String yaml) {
		if (yaml == null || yaml.isBlank()) {
			throw new IllegalArgumentException("There is nothing to import");
		}

		List<ScenarioDefinition> definitions;
		try {
			tools.jackson.databind.JsonNode root = yamlMapper.readTree(yaml);
			definitions = root.isArray()
					? List.of(yamlMapper.treeToValue(root, ScenarioDefinition[].class))
					: List.of(yamlMapper.treeToValue(root, ScenarioDefinition.class));
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("That is not valid scenario YAML: " + ex.getMessage(), ex);
		}

		List<EvalScenarioEntity> saved = definitions.stream().map(this::save).toList();
		log.info("Imported {} scenario(s)", saved.size());
		return saved;
	}

	public ScenarioDefinition toDefinition(EvalScenarioEntity entity) {
		return new ScenarioDefinition(entity.getName(), entity.getDescription(), entity.getInput(),
				entity.getSystemPrompt(), readStrings(entity.getToolsJson()),
				readConfig(entity.getConfigJson()), readAssertions(entity.getAssertionsJson()),
				entity.isEnabled());
	}

	private void validate(ScenarioDefinition definition) {
		if (definition.name() == null || definition.name().isBlank()) {
			throw new IllegalArgumentException("A scenario needs a name");
		}
		if (definition.input() == null || definition.input().isBlank()) {
			throw new IllegalArgumentException("A scenario needs an input to send");
		}
		if (definition.assertions() == null || definition.assertions().isEmpty()) {
			// A scenario with no assertions would pass unconditionally, which is worse than useless:
			// it would sit in a suite reporting success while checking nothing at all.
			throw new IllegalArgumentException(
					"Scenario '%s' has no assertions, so it could only ever pass. Give it at least one."
							.formatted(definition.name()));
		}
	}

	private List<String> readStrings(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return List.of(objectMapper.readValue(json, String[].class));
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	private java.util.Map<String, Object> readConfig(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> config = objectMapper.readValue(json, java.util.Map.class);
			return config;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private List<AssertionSpec> readAssertions(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return List.of(objectMapper.readValue(json, AssertionSpec[].class));
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	/**
	 * A scenario in its portable form: what YAML round-trips through.
	 *
	 * @param tools  names of the tools to expose; empty means a plain completion
	 * @param config guardrail and sampling overrides, the same keys a variant may override
	 */
	@com.fasterxml.jackson.annotation.JsonInclude(
			com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	public record ScenarioDefinition(String name, String description, String input,
			String systemPrompt, List<String> tools, java.util.Map<String, Object> config,
			List<AssertionSpec> assertions, Boolean enabled) {
	}
}
