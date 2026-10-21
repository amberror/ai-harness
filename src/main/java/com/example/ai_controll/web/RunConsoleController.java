package com.example.ai_controll.web;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.orchestrator.RunService;
import com.example.ai_controll.orchestrator.StartRunCommand;

/** Console pages for starting runs and watching them unfold. */
@Controller
public class RunConsoleController {

	private final RunService runService;

	private final EventJournal journal;

	private final HarnessProperties properties;

	private final com.example.ai_controll.tool.registry.ToolsetResolver toolsetResolver;

	private final com.example.ai_controll.orchestrator.approval.ApprovalService approvals;

	public RunConsoleController(RunService runService, EventJournal journal,
			HarnessProperties properties,
			com.example.ai_controll.tool.registry.ToolsetResolver toolsetResolver,
			com.example.ai_controll.orchestrator.approval.ApprovalService approvals) {
		this.runService = runService;
		this.journal = journal;
		this.properties = properties;
		this.toolsetResolver = toolsetResolver;
		this.approvals = approvals;
	}

	@GetMapping("/runs")
	public String list(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		model.addAttribute("runs", runService.recent(50));
		model.addAttribute("defaultModel", properties.lmStudio().defaultModel());
		model.addAttribute("defaultMaxOutputTokens", StartRunCommand.DEFAULT_MAX_OUTPUT_TOKENS);
		model.addAttribute("defaultMaxSteps", StartRunCommand.DEFAULT_MAX_STEPS);
		// Only enabled tools are offered: a disabled one would be selectable and then silently absent.
		model.addAttribute("availableTools", toolsetResolver.resolveAllEnabled().names());
		return "runs";
	}

	@PostMapping("/runs")
	public String start(@RequestParam(required = false) String modelKey,
			@RequestParam(required = false) String systemPrompt,
			@RequestParam String input,
			@RequestParam(required = false) Double temperature,
			@RequestParam(required = false) Integer maxOutputTokens,
			@RequestParam(required = false) java.util.List<String> tools,
			@RequestParam(required = false) Boolean lenientArguments,
			@RequestParam(required = false) Integer maxSteps,
			@RequestParam(required = false) Integer seed,
			@RequestParam(required = false) Integer contextLength,
			RedirectAttributes redirect) {
		try {
			UUID runId = runService.start(new StartRunCommand(modelKey, systemPrompt, input, temperature,
					maxOutputTokens, Boolean.TRUE, tools, lenientArguments, maxSteps,
					null, null, null, null, null, seed, null, contextLength, null, null, null));
			// Straight to the trace view: the point is to watch it happen, not to be told it started.
			return "redirect:/runs/" + runId;
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/runs";
		}
	}

	@PostMapping("/runs/{runId}/cancel")
	public String cancel(@PathVariable UUID runId, RedirectAttributes redirect) {
		try {
			redirect.addFlashAttribute("notice", runService.cancel(runId)
					? "Stopping the run. Its transcript and journal are kept."
					: "That run had already finished.");
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/runs/" + runId;
	}

	@GetMapping("/runs/{runId}")
	public String detail(@PathVariable UUID runId, Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		return runService.detail(runId).map(detail -> {
			model.addAttribute("run", detail.summary());
			model.addAttribute("finalOutput", detail.finalOutput());
			model.addAttribute("transcript", detail.transcript());
			model.addAttribute("events", journal.eventsFor(runId));
			// The decision belongs where the evidence is: the transcript and journal that led to it.
			model.addAttribute("pendingApproval", approvals.pendingFor(runId).orElse(null));
			// Only a run still in flight needs a live stream; a finished one is fully in the journal.
			model.addAttribute("live", !detail.summary().status().isTerminal());
			return "run-detail";
		}).orElse("redirect:/runs");
	}
}
