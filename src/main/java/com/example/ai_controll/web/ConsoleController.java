package com.example.ai_controll.web;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.dto.LoadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelResponse;
import com.example.ai_controll.lmstudio.dto.ModelInfo;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;

/**
 * Server-rendered console.
 *
 * <p>Phase 0 covers the control plane only: is the model server up, what can it run, and what is
 * resident right now. The live run monitor arrives with the event journal in phase 1.
 */
@Controller
public class ConsoleController {

	private final ModelAdminClient modelAdminClient;

	private final HarnessProperties properties;

	public ConsoleController(ModelAdminClient modelAdminClient, HarnessProperties properties) {
		this.modelAdminClient = modelAdminClient;
		this.properties = properties;
	}

	@GetMapping("/")
	public String dashboard(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		try {
			List<ModelInfo> models = modelAdminClient.listModels();
			model.addAttribute("reachable", true);
			model.addAttribute("totalModels", models.size());
			model.addAttribute("llmCount", models.stream().filter(ModelInfo::isLlm).count());
			model.addAttribute("toolCapableCount", models.stream().filter(ModelInfo::supportsToolUse).count());
			model.addAttribute("loaded", models.stream().filter(ModelInfo::isLoaded).toList());
		}
		catch (LmStudioException ex) {
			model.addAttribute("reachable", false);
			model.addAttribute("unreachableMessage", ex.getMessage());
		}
		return "dashboard";
	}

	@GetMapping("/models")
	public String models(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		try {
			// Loaded first, then alphabetically: the resident models are the ones you act on.
			List<ModelInfo> models = modelAdminClient.listModels().stream()
					.sorted(Comparator.comparing(ModelInfo::isLoaded).reversed()
							.thenComparing(info -> info.key() == null ? "" : info.key()))
					.toList();
			model.addAttribute("reachable", true);
			model.addAttribute("models", models);
		}
		catch (LmStudioException ex) {
			model.addAttribute("reachable", false);
			model.addAttribute("unreachableMessage", ex.getMessage());
			model.addAttribute("models", List.of());
		}
		return "models";
	}

	@PostMapping("/models/load")
	public String load(@RequestParam String modelKey,
			@RequestParam(required = false) Integer contextLength,
			RedirectAttributes redirect) {
		try {
			LoadModelResponse response = modelAdminClient.load(LoadModelRequest.of(modelKey, contextLength));
			Integer actualContext = response.loadConfig() == null ? null : response.loadConfig().contextLength();
			redirect.addFlashAttribute("notice",
					"Loaded %s in %.1fs (context_length=%s)".formatted(response.instanceId(),
							response.loadTimeSeconds() == null ? 0d : response.loadTimeSeconds(),
							actualContext));
		}
		catch (LmStudioException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/models";
	}

	@PostMapping("/models/unload")
	public String unload(@RequestParam String instanceId, RedirectAttributes redirect) {
		try {
			modelAdminClient.unload(instanceId);
			redirect.addFlashAttribute("notice", "Unloaded %s".formatted(instanceId));
		}
		catch (LmStudioException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/models";
	}
}
