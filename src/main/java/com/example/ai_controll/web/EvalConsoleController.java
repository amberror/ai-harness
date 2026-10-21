package com.example.ai_controll.web;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.eval.EvalBatchRunner;
import com.example.ai_controll.eval.ScenarioService;
import com.example.ai_controll.eval.store.EvalBatchRepository;
import com.example.ai_controll.eval.store.EvalResultRepository;
import com.example.ai_controll.eval.store.EvalScenarioRepository;

import tools.jackson.databind.ObjectMapper;

/** Console pages for scenarios and batch results. */
@Controller
public class EvalConsoleController {

	private final ScenarioService scenarios;

	private final EvalBatchRunner batchRunner;

	private final EvalBatchRepository batches;

	private final EvalResultRepository results;

	private final HarnessProperties properties;

	private final ObjectMapper objectMapper;

	private final com.example.ai_controll.eval.stats.BatchStatistics statistics;

	public EvalConsoleController(ScenarioService scenarios, EvalBatchRunner batchRunner,
			EvalBatchRepository batches, EvalResultRepository results,
			HarnessProperties properties, ObjectMapper objectMapper,
			com.example.ai_controll.eval.stats.BatchStatistics statistics) {
		this.scenarios = scenarios;
		this.batchRunner = batchRunner;
		this.batches = batches;
		this.results = results;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.statistics = statistics;
	}

	@GetMapping("/eval")
	public String index(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		model.addAttribute("scenarios", scenarios.list());
		model.addAttribute("batches", batches.findAllByOrderByStartedAtDesc(Limit.of(25)));
		return "eval";
	}

	@GetMapping("/eval/scenarios/{id}")
	public String scenario(@PathVariable UUID id, Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		return scenarios.find(id).map(entity -> {
			model.addAttribute("scenario", entity);
			model.addAttribute("yaml", scenarios.toYaml(entity));
			// Every batch for this scenario, which is the comparison across variants: same question,
			// one thing changed, different pass rates.
			model.addAttribute("batches", batches.findByScenarioIdOrderByStartedAtDesc(id));
			return "eval-scenario";
		}).orElse("redirect:/eval");
	}

	@GetMapping("/eval/batches/{id}")
	public String batch(@PathVariable UUID id, Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		return batches.findById(id).map(batch -> {
			model.addAttribute("batch", batch);
			model.addAttribute("results", results.findByBatchIdOrderByRepetitionAsc(id));
			model.addAttribute("scenario", scenarios.find(batch.getScenarioId()).orElse(null));
			// The interval and the per-assertion breakdown, without which a pass rate says less than
			// it appears to.
			model.addAttribute("stats", statistics.summarise(id));
			return "eval-batch";
		}).orElse("redirect:/eval");
	}

	/**
	 * Two batches side by side.
	 *
	 * <p>The comparison is the point of running batches at all, and doing it by eye across two table
	 * rows is where the reasoning usually goes wrong: the eye sees 100% against 80% and stops, without
	 * noticing that five repetitions cannot tell those apart.
	 */
	@GetMapping("/eval/compare")
	public String compare(@RequestParam UUID a, @RequestParam UUID b, Model model,
			RedirectAttributes redirect) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		try {
			model.addAttribute("comparison", statistics.compare(a, b));
			return "eval-compare";
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/eval";
		}
	}

	@PostMapping("/eval/batches/{id}/cancel")
	public String cancelBatch(@PathVariable UUID id, RedirectAttributes redirect) {
		redirect.addFlashAttribute("notice", batchRunner.cancel(id)
				? "Stopping the batch; the repetition in progress is being cancelled."
				: "That batch had already finished.");
		return "redirect:/eval/batches/" + id;
	}

	@PostMapping("/eval/import")
	public String importYaml(@RequestParam String yaml, RedirectAttributes redirect) {
		try {
			int count = scenarios.importYaml(yaml).size();
			redirect.addFlashAttribute("notice", "Imported %d scenario(s)".formatted(count));
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/eval";
	}

	@PostMapping("/eval/scenarios/{id}/run")
	public String run(@PathVariable UUID id,
			@RequestParam(required = false) String label,
			@RequestParam(defaultValue = "5") int repetitions,
			@RequestParam(required = false) String variantJson,
			RedirectAttributes redirect) {
		try {
			UUID batchId = batchRunner.start(id, label,
					(variantJson == null || variantJson.isBlank()) ? null
							: objectMapper.readTree(variantJson),
					repetitions);
			return "redirect:/eval/batches/" + batchId;
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/eval/scenarios/" + id;
		}
	}

	@PostMapping("/eval/scenarios/{id}/enabled")
	public String setEnabled(@PathVariable UUID id, @RequestParam boolean value,
			RedirectAttributes redirect) {
		scenarios.find(id).ifPresent(entity -> {
			com.example.ai_controll.eval.ScenarioService.ScenarioDefinition definition =
					scenarios.toDefinition(entity);
			scenarios.save(new com.example.ai_controll.eval.ScenarioService.ScenarioDefinition(
					definition.name(), definition.description(), definition.input(),
					definition.systemPrompt(), definition.tools(), definition.config(),
					definition.assertions(), value));
		});
		redirect.addFlashAttribute("notice", value ? "Scenario enabled" : "Scenario disabled");
		return "redirect:/eval";
	}

	@PostMapping("/eval/scenarios/{id}/delete")
	public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
		scenarios.delete(id);
		redirect.addFlashAttribute("notice", "Removed the scenario");
		return "redirect:/eval";
	}
}
