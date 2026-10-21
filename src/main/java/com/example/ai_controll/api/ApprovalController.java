package com.example.ai_controll.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.orchestrator.RunService;
import com.example.ai_controll.orchestrator.approval.ApprovalDecision;
import com.example.ai_controll.orchestrator.approval.ApprovalScope;
import com.example.ai_controll.orchestrator.approval.ApprovalService;
import com.example.ai_controll.orchestrator.approval.PendingApprovalEntity;

/**
 * The approval queue: what is waiting, and deciding it.
 *
 * <p>A decision resumes the run in the background, so this returns as soon as the decision is
 * recorded. Approving is a quick action; what follows it can take minutes.
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

	private final ApprovalService approvals;

	private final RunService runService;

	public ApprovalController(ApprovalService approvals, RunService runService) {
		this.approvals = approvals;
		this.runService = runService;
	}

	/** Everything currently waiting, oldest first. */
	@GetMapping
	public List<ApprovalView> pending() {
		return approvals.allPending().stream().map(ApprovalView::from).toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<ApprovalView> get(@PathVariable UUID id) {
		return approvals.allPending().stream()
				.filter(approval -> approval.getId().equals(id))
				.findFirst()
				.map(ApprovalView::from)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Approves a parked call and resumes the run.
	 *
	 * @param request the scope, which is recorded either way: "why did that run unattended" needs an
	 *                answer, and a run-scoped grant is otherwise invisible
	 */
	@PostMapping("/{id}/approve")
	public ResponseEntity<Void> approve(@PathVariable UUID id,
			@RequestBody(required = false) DecisionRequest request) {
		return decide(id, ApprovalDecision.APPROVED, request);
	}

	/** Declines a parked call. The run continues; the model is told it was refused. */
	@PostMapping("/{id}/deny")
	public ResponseEntity<Void> deny(@PathVariable UUID id,
			@RequestBody(required = false) DecisionRequest request) {
		return decide(id, ApprovalDecision.DENIED, request);
	}

	private ResponseEntity<Void> decide(UUID id, ApprovalDecision decision,
			DecisionRequest request) {
		PendingApprovalEntity approval = approvals.allPending().stream()
				.filter(pending -> pending.getId().equals(id))
				.findFirst()
				.orElse(null);
		if (approval == null) {
			return ResponseEntity.notFound().build();
		}

		ApprovalScope scope = (request == null || request.scope() == null) ? ApprovalScope.CALL
				: request.scope();
		// A denial is never broadened: refusing "always" would be a standing ban written by accident,
		// and the policy page is where a standing rule belongs.
		if (decision == ApprovalDecision.DENIED) {
			scope = ApprovalScope.CALL;
		}

		runService.decideAndResume(approval.getRunId(), id, decision, scope,
				request == null ? null : request.note());
		return ResponseEntity.accepted().build();
	}

	/**
	 * @param scope CALL for this invocation, RUN for the rest of this run, ALWAYS to write a
	 *              standing policy rule. Ignored for a denial
	 */
	public record DecisionRequest(ApprovalScope scope, String note) {
	}

	public record ApprovalView(UUID id, UUID runId, int stepNo, String toolName, String argumentsJson,
			String policyName, String reason, Instant requestedAt) {

		static ApprovalView from(PendingApprovalEntity entity) {
			return new ApprovalView(entity.getId(), entity.getRunId(), entity.getStepNo(),
					entity.getToolName(), entity.getArgumentsJson(), entity.getPolicyName(),
					entity.getReason(), entity.getRequestedAt());
		}
	}
}
