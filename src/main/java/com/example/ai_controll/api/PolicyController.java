package com.example.ai_controll.api;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.tool.policy.ApprovalMode;
import com.example.ai_controll.tool.policy.ToolPolicyEntity;
import com.example.ai_controll.tool.policy.ToolPolicyRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Managing the rules about which tool calls may run unattended.
 *
 * <p>Rules are matched by glob on the tool name and ordered by priority, lowest first. A call
 * matching nothing runs, so this is a list of exceptions rather than a list of permissions.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

	private final ToolPolicyRepository policies;

	public PolicyController(ToolPolicyRepository policies) {
		this.policies = policies;
	}

	@GetMapping
	public List<PolicyView> list() {
		return policies.findAllByOrderByPriorityAscNameAsc().stream().map(PolicyView::from).toList();
	}

	@PostMapping
	public ResponseEntity<PolicyView> create(@Valid @RequestBody PolicyRequest request) {
		if (policies.existsByName(request.name())) {
			return ResponseEntity.badRequest().build();
		}
		ToolPolicyEntity entity = new ToolPolicyEntity(UUID.randomUUID(), request.name(),
				request.toolNamePattern(), request.approvalMode(),
				request.priority() == null ? 100 : request.priority(), request.timeoutMs(),
				request.rateLimitPerRun(), Instant.now());
		entity.setEnabled(request.enabled() == null || request.enabled());
		return ResponseEntity.ok(PolicyView.from(policies.save(entity)));
	}

	@PutMapping("/{id}")
	public ResponseEntity<PolicyView> update(@PathVariable UUID id,
			@Valid @RequestBody PolicyRequest request) {
		return policies.findById(id).map(entity -> {
			entity.setToolNamePattern(request.toolNamePattern());
			entity.setApprovalMode(request.approvalMode());
			entity.setPriority(request.priority() == null ? entity.getPriority() : request.priority());
			entity.setTimeoutMs(request.timeoutMs());
			entity.setRateLimitPerRun(request.rateLimitPerRun());
			entity.setEnabled(request.enabled() == null || request.enabled());
			return ResponseEntity.ok(PolicyView.from(policies.save(entity)));
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		policies.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	public record PolicyView(UUID id, String name, String toolNamePattern, ApprovalMode approvalMode,
			int priority, Long timeoutMs, Integer rateLimitPerRun, boolean enabled, Instant createdAt) {

		static PolicyView from(ToolPolicyEntity entity) {
			return new PolicyView(entity.getId(), entity.getName(), entity.getToolNamePattern(),
					entity.getApprovalMode(), entity.getPriority(), entity.getTimeoutMs(),
					entity.getRateLimitPerRun(), entity.isEnabled(), entity.getCreatedAt());
		}
	}

	/**
	 * @param toolNamePattern glob over tool names: {@code *} for any run of characters, {@code ?} for
	 *                        one. Metacharacters are literal, so {@code a.b} means {@code a.b}
	 * @param priority        lower wins, so a specific rule can override a general one
	 * @param rateLimitPerRun how many times the matching tool may run within one run
	 */
	public record PolicyRequest(@NotBlank String name, @NotBlank String toolNamePattern,
			ApprovalMode approvalMode, Integer priority, Long timeoutMs, Integer rateLimitPerRun,
			Boolean enabled) {
	}
}
