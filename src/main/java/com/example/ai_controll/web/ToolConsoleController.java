package com.example.ai_controll.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.tool.learning.ToolCoach;
import com.example.ai_controll.tool.learning.ToolUsageAnalyzer;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolRegistryService;
import com.example.ai_controll.tool.registry.ToolSource;

import tools.jackson.databind.ObjectMapper;

/** Console pages for managing tools and their version history. */
@Controller
public class ToolConsoleController {

	private final ToolRegistryService registry;

	private final HarnessProperties properties;

	private final ObjectMapper objectMapper;

	private final java.util.List<com.example.ai_controll.tool.exec.builtin.BuiltinTool> builtins;

	private final ToolUsageAnalyzer analyzer;

	private final ToolCoach coach;

	public ToolConsoleController(ToolRegistryService registry, HarnessProperties properties,
			ObjectMapper objectMapper,
			java.util.List<com.example.ai_controll.tool.exec.builtin.BuiltinTool> builtins,
			ToolUsageAnalyzer analyzer, ToolCoach coach) {
		this.registry = registry;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.builtins = java.util.List.copyOf(builtins);
		this.analyzer = analyzer;
		this.coach = coach;
	}

	@GetMapping("/tools")
	public String list(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		model.addAttribute("tools", registry.listCurrent());
		model.addAttribute("allowedHosts", properties.tools().http().allowedHosts());
		model.addAttribute("executorTypes", ExecutorType.values());
		return "tools";
	}

	@GetMapping("/tools/{name}")
	public String detail(@PathVariable String name, Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		// Read straight from the journal on every view. Cheap, and it means the page cannot show a
		// report that has quietly gone stale relative to the runs it claims to summarise.
		model.addAttribute("usage", analyzer.report(name));
		return registry.findCurrent(name).map(current -> {
			model.addAttribute("tool", current);
			model.addAttribute("schema", pretty(current.getParamsSchema()));
			model.addAttribute("executorConfig", pretty(current.getExecutorConfig()));
			// The version history is the record of what was tried, which is the point of versioning.
			model.addAttribute("versions", registry.history(name));
			return "tool-detail";
		}).orElse("redirect:/tools");
	}

	@PostMapping("/tools")
	public String save(@RequestParam String name,
			@RequestParam String description,
			@RequestParam String parametersSchema,
			@RequestParam ExecutorType executorType,
			@RequestParam(required = false) String executorConfig,
			@RequestParam(required = false) String changeNote,
			RedirectAttributes redirect) {
		try {
			registry.save(new ToolRegistryService.ToolDefinition(name, description,
					objectMapper.readTree(parametersSchema), executorType,
					(executorConfig == null || executorConfig.isBlank()) ? null
							: objectMapper.readTree(executorConfig),
					true, ToolSource.USER, null),
					(changeNote == null || changeNote.isBlank()) ? "edited in the console" : changeNote);
			redirect.addFlashAttribute("notice", "Saved a new version of '%s'".formatted(name));
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/tools";
	}

	@PostMapping("/tools/{name}/enabled")
	public String setEnabled(@PathVariable String name, @RequestParam boolean value,
			RedirectAttributes redirect) {
		registry.setEnabled(name, value);
		redirect.addFlashAttribute("notice",
				"'%s' is now %s".formatted(name, value ? "enabled" : "disabled"));
		return "redirect:/tools";
	}

	/**
	 * Takes up the compiled definition of a built-in, as a new version.
	 *
	 * <p>Seeding never overwrites, so a description someone tuned survives every restart — the
	 * registry is the authority on what the model is shown, not the source. The price is that
	 * improving a built-in in code leaves the running harness offering the old schema, and a new
	 * argument comes back rejected as an unknown property. This is the explicit way to catch up, and
	 * it goes through the ordinary versioned save, so the definition it replaces stays in the history.
	 */
	/**
	 * Adds examples harvested from calls that worked, as a new version.
	 *
	 * <p>Nothing is invented: every example is a call the model itself made and the validator
	 * accepted. They go into the schema's {@code examples} keyword, which travels to the model inside
	 * {@code parameters} with no extra plumbing.
	 */
	@PostMapping("/tools/{name}/learn-examples")
	public String learnExamples(@PathVariable String name, RedirectAttributes redirect) {
		try {
			var saved = coach.applyExamples(name);
			redirect.addFlashAttribute("notice", ("Saved version %d with examples from calls that "
					+ "worked. Run a batch against version %d to see whether it helped.")
							.formatted(saved.getVersion(), saved.getVersion() - 1));
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/tools/" + name;
	}

	/**
	 * Drafts a replacement description and shows it. Deliberately does not apply it.
	 *
	 * <p>A description that reads better is not the same as one the model performs better under, and
	 * the only thing that settles which is a batch against the version it would replace.
	 */
	@PostMapping("/tools/{name}/draft-description")
	public String draftDescription(@PathVariable String name, RedirectAttributes redirect) {
		try {
			ToolCoach.Draft draft = coach.draftDescription(name);
			if (!draft.succeeded()) {
				redirect.addFlashAttribute("error", draft.diagnosis());
			}
			else {
				redirect.addFlashAttribute("draft", draft.proposed());
				redirect.addFlashAttribute("notice",
						"Drafted by %s. Read it, then apply it if you want to measure it.".formatted(
								draft.modelKey()));
			}
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/tools/" + name;
	}

	@PostMapping("/tools/{name}/apply-description")
	public String applyDescription(@PathVariable String name, @RequestParam String description,
			RedirectAttributes redirect) {
		try {
			var saved = coach.applyDescription(name, description);
			redirect.addFlashAttribute("notice", ("Saved version %d. It is not yet measured -- compare a "
					+ "batch against version %d before believing it is better.")
							.formatted(saved.getVersion(), saved.getVersion() - 1));
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/tools/" + name;
	}

	@PostMapping("/tools/{name}/reseed")
	public String reseed(@PathVariable String name, RedirectAttributes redirect) {
		com.example.ai_controll.tool.exec.builtin.BuiltinTool builtin = builtins.stream()
				.filter(candidate -> candidate.name().equals(name))
				.findFirst().orElse(null);
		if (builtin == null) {
			redirect.addFlashAttribute("error",
					"'%s' is not a built-in, so there is no compiled definition to take up.".formatted(name));
			return "redirect:/tools/" + name;
		}

		boolean enabled = registry.findCurrent(name)
				.map(com.example.ai_controll.tool.registry.ToolDefinitionEntity::isEnabled).orElse(true);
		registry.save(new ToolRegistryService.ToolDefinition(builtin.name(), builtin.defaultDescription(),
				objectMapper.readTree(builtin.defaultSchema()), ExecutorType.BUILTIN, null, enabled,
				ToolSource.BUILTIN, null), "reseeded from the compiled built-in");
		redirect.addFlashAttribute("notice",
				"'%s' now matches the compiled built-in. The previous version is still in the history."
						.formatted(name));
		return "redirect:/tools/" + name;
	}

	@PostMapping("/tools/{name}/retire")
	public String retire(@PathVariable String name, RedirectAttributes redirect) {
		registry.delete(name);
		redirect.addFlashAttribute("notice",
				"Retired '%s'. Its versions are kept so past runs stay explicable.".formatted(name));
		return "redirect:/tools";
	}

	/** Pretty-prints stored JSON for the editor; a one-line blob is unusable to edit by hand. */
	private String pretty(String json) {
		if (json == null || json.isBlank()) {
			return "";
		}
		try {
			return objectMapper.writerWithDefaultPrettyPrinter()
					.writeValueAsString(objectMapper.readTree(json));
		}
		catch (RuntimeException ex) {
			return json;
		}
	}
}
