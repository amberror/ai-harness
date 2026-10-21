package com.example.ai_controll.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.orchestrator.RunService;
import com.example.ai_controll.orchestrator.approval.ApprovalDecision;
import com.example.ai_controll.orchestrator.approval.ApprovalScope;
import com.example.ai_controll.orchestrator.approval.ApprovalService;
import com.example.ai_controll.tool.policy.ApprovalMode;
import com.example.ai_controll.tool.policy.ToolPolicyEntity;
import com.example.ai_controll.tool.policy.ToolPolicyRepository;
import com.example.ai_controll.tool.registry.ToolRegistryService;

/** Console pages for policy rules and the approval queue. */
@Controller
public class PolicyConsoleController {

	private final ToolPolicyRepository policies;

	private final ApprovalService approvals;

	private final RunService runService;

	private final ToolRegistryService registry;

	private final HarnessProperties properties;

	public PolicyConsoleController(ToolPolicyRepository policies, ApprovalService approvals,
			RunService runService, ToolRegistryService registry, HarnessProperties properties) {
		this.policies = policies;
		this.approvals = approvals;
		this.runService = runService;
		this.registry = registry;
		this.properties = properties;
	}

	@GetMapping("/policies")
	public String list(Model model) {
		model.addAttribute("baseUrl", properties.lmStudio().baseUrl());
		model.addAttribute("policies", policies.findAllByOrderByPriorityAscNameAsc());
		model.addAttribute("modes", ApprovalMode.values());
		model.addAttribute("toolNames", registry.listCurrent().stream()
				.map(tool -> tool.getName()).toList());
		model.addAttribute("pending", approvals.allPending());
		return "policies";
	}

	@PostMapping("/policies")
	public String save(@RequestParam String name,
			@RequestParam String toolNamePattern,
			@RequestParam ApprovalMode approvalMode,
			@RequestParam(required = false) Integer priority,
			@RequestParam(required = false) Integer rateLimitPerRun,
			RedirectAttributes redirect) {
		if (policies.existsByName(name)) {
			redirect.addFlashAttribute("error", "A rule named '%s' already exists".formatted(name));
			return "redirect:/policies";
		}
		policies.save(new ToolPolicyEntity(UUID.randomUUID(), name, toolNamePattern, approvalMode,
				priority == null ? 100 : priority, null, rateLimitPerRun, Instant.now()));
		redirect.addFlashAttribute("notice", "Added rule '%s'".formatted(name));
		return "redirect:/policies";
	}

	@PostMapping("/policies/{id}/enabled")
	public String setEnabled(@PathVariable UUID id, @RequestParam boolean value) {
		policies.findById(id).ifPresent(policy -> {
			policy.setEnabled(value);
			policies.save(policy);
		});
		return "redirect:/policies";
	}

	@PostMapping("/policies/{id}/delete")
	public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
		policies.deleteById(id);
		redirect.addFlashAttribute("notice", "Removed the rule");
		return "redirect:/policies";
	}

	/**
	 * Records a decision and lets the run continue.
	 *
	 * <p>Redirects back to the run rather than the queue: having just decided what a run may do, the
	 * next thing you want to see is what it does with it.
	 */
	@PostMapping("/approvals/{id}/decide")
	public String decide(@PathVariable UUID id,
			@RequestParam UUID runId,
			@RequestParam String action,
			@RequestParam(required = false) String note,
			RedirectAttributes redirect) {
		try {
			// One action rather than a decision plus a scope: a submit button carries a single
			// name/value pair, so pairing them would need JavaScript to keep the two in step.
			// A denial is deliberately not offered in a broader scope -- refusing "always" would write
			// a standing ban by accident, and a standing rule belongs on the policy page.
			ApprovalDecision decision = "DENY".equals(action) ? ApprovalDecision.DENIED
					: ApprovalDecision.APPROVED;
			ApprovalScope scope = switch (action) {
				case "APPROVE_RUN" -> ApprovalScope.RUN;
				case "APPROVE_ALWAYS" -> ApprovalScope.ALWAYS;
				default -> ApprovalScope.CALL;
			};

			runService.decideAndResume(runId, id, decision, scope, note);
			redirect.addFlashAttribute("notice",
					"%s (%s). The run is continuing.".formatted(decision, scope));
		}
		catch (RuntimeException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/runs/" + runId;
	}
}
