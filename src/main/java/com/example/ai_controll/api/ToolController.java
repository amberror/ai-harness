package com.example.ai_controll.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.tool.exec.ToolExecutor;
import com.example.ai_controll.tool.exec.builtin.BuiltinTool;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolRegistryService;
import com.example.ai_controll.tool.registry.ToolSource;
import com.example.ai_controll.tool.schema.ToolArgumentValidator;
import com.example.ai_controll.tool.schema.ToolSchema;
import com.example.ai_controll.tool.schema.ValidationOutcome;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Managing tools: create, edit, retire, inspect history, and try one out.
 *
 * <p>Editing never overwrites. Each change produces a new version and moves the current pointer, so
 * a run remains explicable after the tool it used has moved on.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

	private final ToolRegistryService registry;

	private final ToolArgumentValidator validator;

	private final List<ToolExecutor> executors;

	private final List<BuiltinTool> builtins;

	private final ObjectMapper objectMapper;

	public ToolController(ToolRegistryService registry, ToolArgumentValidator validator,
			List<ToolExecutor> executors, List<BuiltinTool> builtins, ObjectMapper objectMapper) {
		this.registry = registry;
		this.validator = validator;
		this.executors = List.copyOf(executors);
		this.builtins = List.copyOf(builtins);
		this.objectMapper = objectMapper;
	}

	@GetMapping
	public List<ToolView> list() {
		return registry.listCurrent().stream().map(this::toView).toList();
	}

	@GetMapping("/{name}")
	public ResponseEntity<ToolView> get(@PathVariable String name) {
		return registry.findCurrent(name).map(this::toView).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Every version of a tool, newest first: how the schema evolved and why. */
	@GetMapping("/{name}/versions")
	public List<ToolView> versions(@PathVariable String name) {
		return registry.history(name).stream().map(this::toView).toList();
	}

	@PostMapping
	public ToolView create(@Valid @RequestBody ToolRequest request) {
		return toView(registry.save(request.toDefinition(ToolSource.USER), request.changeNote()));
	}

	/** Saves a new version. The previous one is kept and stays inspectable. */
	@PutMapping("/{name}")
	public ToolView update(@PathVariable String name, @Valid @RequestBody ToolRequest request) {
		ToolSource source = registry.findCurrent(name).map(ToolDefinitionEntity::getSource)
				.orElse(ToolSource.USER);
		return toView(registry.save(request.withName(name).toDefinition(source), request.changeNote()));
	}

	/**
	 * Rewrites a built-in's stored definition from the compiled one, as a new version.
	 *
	 * <p>Seeding happens once and never overwrites, so that a description someone tuned is not
	 * silently reverted on the next restart — the registry, not the source, is the authority on what
	 * the model is shown. The cost of that is real: improve a built-in's schema in code and the
	 * running harness keeps offering the old one, and a new argument is rejected as an unknown
	 * property rather than used.
	 *
	 * <p>So the update is available but explicit, and it goes through the ordinary versioned save:
	 * the previous definition stays in the history, and reverting is picking the older version back
	 * up rather than hoping it was kept somewhere.
	 */
	@PostMapping("/{name}/reseed")
	public ResponseEntity<ToolView> reseed(@PathVariable String name) {
		BuiltinTool builtin = builtins.stream()
				.filter(candidate -> candidate.name().equals(name))
				.findFirst().orElse(null);
		if (builtin == null) {
			// Only built-ins have a compiled definition to fall back to; a user-defined tool has no
			// source of truth other than the registry itself.
			return ResponseEntity.notFound().build();
		}

		boolean enabled = registry.findCurrent(name).map(ToolDefinitionEntity::isEnabled).orElse(true);
		Long timeoutMs = registry.findCurrent(name).map(ToolDefinitionEntity::getTimeoutMs).orElse(null);

		return ResponseEntity.ok(toView(registry.save(new ToolRegistryService.ToolDefinition(
				builtin.name(), builtin.defaultDescription(),
				objectMapper.readTree(builtin.defaultSchema()), ExecutorType.BUILTIN, null, enabled,
				ToolSource.BUILTIN, timeoutMs),
				"reseeded from the compiled built-in")));
	}

	@PostMapping("/{name}/enabled")
	public ResponseEntity<Void> setEnabled(@PathVariable String name, @RequestParam boolean value) {
		registry.setEnabled(name, value);
		return ResponseEntity.noContent().build();
	}

	/** Retires a tool. Its versions are kept so past runs stay explicable. */
	@DeleteMapping("/{name}")
	public ResponseEntity<Void> delete(@PathVariable String name) {
		registry.delete(name);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Runs a tool directly with the arguments given.
	 *
	 * <p>The most useful endpoint here while building a toolset. When a run misbehaves the first
	 * question is always whether the tool works at all, and answering it through the model means
	 * waiting a minute for an answer that confounds two variables. This answers it in a second.
	 *
	 * <p>Arguments go through the same validator the loop uses, so a failure here is the failure the
	 * model would have hit.
	 */
	@PostMapping("/{name}/test")
	public ResponseEntity<TestResult> test(@PathVariable String name,
			@RequestBody(required = false) JsonNode arguments) {

		ToolDefinitionEntity definition = registry.findCurrent(name).orElse(null);
		if (definition == null) {
			return ResponseEntity.notFound().build();
		}

		ToolSchema schema = registry.toSchema(definition);
		JsonNode args = arguments == null ? tools.jackson.databind.node.JsonNodeFactory.instance
				.objectNode() : arguments;

		ValidationOutcome validation = validator.validate(
				new ToolCallRequest("test", name, args.toString()), List.of(schema), false);

		if (!validation.isValid()) {
			// Reported as a 200 with the rejection inside: the request was well-formed, and the
			// rejection is the result being asked for.
			return ResponseEntity.ok(new TestResult(false, validation.status().name(),
					validation.feedback(), null, 0L));
		}

		ToolExecutor executor = executors.stream()
				.filter(candidate -> candidate.type() == definition.getExecutorType())
				.findFirst().orElse(null);
		if (executor == null) {
			return ResponseEntity.ok(new TestResult(false, "no_executor", null,
					"No executor registered for %s".formatted(definition.getExecutorType()), 0L));
		}

		ToolOutcome outcome = executor.execute(new ToolInvocation(UUID.randomUUID(), 0, "test",
				definition, validation.arguments()));

		return ResponseEntity.ok(new TestResult(outcome.succeeded(), outcome.errorKind(), null,
				outcome.output(), outcome.durationMs()));
	}

	private ToolView toView(ToolDefinitionEntity entity) {
		return new ToolView(entity.getId(), entity.getName(), entity.getVersion(), entity.isCurrent(),
				entity.getDescription(), registry.parseSchema(entity), entity.getExecutorType(),
				registry.parseExecutorConfig(entity), entity.isEnabled(), entity.getSource(),
				entity.getTimeoutMs(), entity.getCreatedAt(), entity.getChangeNote());
	}

	/** A tool as returned by the API. */
	public record ToolView(UUID id, String name, int version, boolean current, String description,
			JsonNode parametersSchema, ExecutorType executorType, JsonNode executorConfig,
			boolean enabled, ToolSource source, Long timeoutMs, java.time.Instant createdAt,
			String changeNote) {
	}

	/**
	 * A tool as submitted.
	 *
	 * @param changeNote why this version exists; worth writing, since the version history is the
	 *                   record of what was tried
	 */
	public record ToolRequest(String name, @NotBlank String description,
			JsonNode parametersSchema, ExecutorType executorType, JsonNode executorConfig,
			Boolean enabled, Long timeoutMs, String changeNote) {

		ToolRequest withName(String override) {
			return new ToolRequest(override, description, parametersSchema, executorType,
					executorConfig, enabled, timeoutMs, changeNote);
		}

		ToolRegistryService.ToolDefinition toDefinition(ToolSource source) {
			return new ToolRegistryService.ToolDefinition(name, description, parametersSchema,
					executorType == null ? ExecutorType.HTTP : executorType, executorConfig,
					enabled == null || enabled, source, timeoutMs);
		}
	}

	/**
	 * @param rejection the validator's feedback when the arguments were refused; exactly what the
	 *                  model would have been told
	 */
	public record TestResult(boolean succeeded, String errorKind, JsonNode rejection, String output,
			long durationMs) {
	}
}
