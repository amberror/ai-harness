package com.example.ai_controll.orchestrator.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.Role;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.conversation.Transcript;
import com.example.ai_controll.lmstudio.port.TokenUsage;
import com.example.ai_controll.orchestrator.AgentRunner;
import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.store.RunEntity;
import com.example.ai_controll.orchestrator.store.RunEventEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;
import com.example.ai_controll.orchestrator.store.RunMessageEntity;
import com.example.ai_controll.orchestrator.store.RunMessageRepository;
import com.example.ai_controll.tool.policy.ApprovalMode;
import com.example.ai_controll.tool.policy.ToolPolicyEntity;
import com.example.ai_controll.tool.policy.ToolPolicyRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Records approval decisions and rebuilds what a parked run needs to carry on.
 *
 * <p>The reconstruction is the interesting part. A parked run holds nothing in memory -- its thread
 * ended -- so resuming means recovering the transcript, the budget and the loop-detector counts from
 * what was written down. That is more work than blocking a virtual thread would have been, and it
 * buys two things: a parked run survives a restart, and the replay path is forced to actually work
 * rather than existing only in principle.
 *
 * <p>Rebuilding the loop counts matters more than it appears. A run resumed with a fresh detector
 * would come back with a clean slate and could loop indefinitely <em>because</em> a human paused to
 * look at it, which is close to the worst possible outcome for a guardrail.
 */
@Service
public class ApprovalService {

	private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

	/** Used when the triggering rule cannot be found; low enough to beat anything hand-written. */
	private static final int DEFAULT_STANDING_PRIORITY = 5;

	private final PendingApprovalRepository approvals;

	private final RunMessageRepository messages;

	private final RunEventRepository events;

	private final ToolPolicyRepository policies;

	private final ObjectMapper objectMapper;

	public ApprovalService(PendingApprovalRepository approvals, RunMessageRepository messages,
			RunEventRepository events, ToolPolicyRepository policies, ObjectMapper objectMapper) {
		this.approvals = approvals;
		this.messages = messages;
		this.events = events;
		this.policies = policies;
		this.objectMapper = objectMapper;
	}

	/** Stores the call a run stopped on. */
	@Transactional
	public PendingApprovalEntity park(UUID runId, AgentRunner.PendingCall pending) {
		PendingApprovalEntity entity = new PendingApprovalEntity(UUID.randomUUID(), runId,
				pending.stepNo(), pending.callId(), pending.toolName(),
				pending.arguments() == null ? null : pending.arguments().toString(),
				objectMapper.writeValueAsString(pending.remainingCalls()), pending.policyName(),
				pending.reason(), Instant.now());
		return approvals.save(entity);
	}

	/** The call a run is currently waiting on. */
	@Transactional(readOnly = true)
	public Optional<PendingApprovalEntity> pendingFor(UUID runId) {
		return approvals.findFirstByRunIdAndDecisionIsNullOrderByRequestedAtAsc(runId);
	}

	/** Everything waiting across all runs. */
	@Transactional(readOnly = true)
	public List<PendingApprovalEntity> allPending() {
		return approvals.findByDecisionIsNullOrderByRequestedAtAsc();
	}

	/**
	 * Records a decision.
	 *
	 * <p>An {@link ApprovalScope#ALWAYS} approval writes a policy rule rather than being remembered
	 * privately, so a standing exemption is visible on the policy page instead of living invisibly
	 * inside one run's history.
	 */
	@Transactional
	public PendingApprovalEntity decide(UUID approvalId, ApprovalDecision decision,
			ApprovalScope scope, String note) {
		PendingApprovalEntity approval = approvals.findById(approvalId)
				.orElseThrow(() -> new IllegalArgumentException("No approval " + approvalId));
		if (!approval.isPending()) {
			throw new IllegalStateException("That approval has already been decided");
		}

		approval.decide(decision, scope, note);
		approvals.save(approval);

		if (decision == ApprovalDecision.APPROVED && scope == ApprovalScope.ALWAYS) {
			createStandingPolicy(approval.getToolName(), approval.getPolicyName());
		}
		log.info("Approval {} for '{}' in run {}: {} ({})", approvalId, approval.getToolName(),
				approval.getRunId(), decision, scope);
		return approval;
	}

	/**
	 * Writes an AUTO rule for a tool, ranked so that it actually takes effect.
	 *
	 * <p>The priority is derived from the rule that demanded the approval rather than fixed, because
	 * a fixed value is only right by luck. A standing rule sharing a priority with the rule it is
	 * meant to override leaves the outcome to tie-breaking, which is how the first version of this
	 * silently did nothing: "always allow" was granted, the rule was written, and the very next run
	 * stopped for approval again.
	 */
	private void createStandingPolicy(String toolName, String triggeringPolicyName) {
		String name = "always-allow-" + toolName;
		if (policies.existsByName(name)) {
			return;
		}

		int priority = policies.findByName(triggeringPolicyName == null ? "" : triggeringPolicyName)
				.map(triggering -> triggering.getPriority() - 1)
				.orElse(DEFAULT_STANDING_PRIORITY);

		policies.save(new ToolPolicyEntity(UUID.randomUUID(), name, toolName, ApprovalMode.AUTO,
				priority, null, null, Instant.now()));
		log.info("Created standing policy '{}' at priority {} from an ALWAYS approval", name, priority);
	}

	/**
	 * Rebuilds everything the loop needs to carry on from where it stopped.
	 */
	@Transactional(readOnly = true)
	public AgentRunner.ResumeState rebuild(RunEntity run, PendingApprovalEntity approval) {
		Transcript transcript = rebuildTranscript(run.getId());

		List<AgentRunner.PriorCall> priorCalls = new ArrayList<>();
		Map<String, Integer> toolCallCounts = new HashMap<>();
		Set<String> runGrants = new HashSet<>();

		for (RunEventEntity event : events.findByRunIdOrderBySeqAsc(run.getId())) {
			JsonNode payload = readPayload(event);
			if (payload == null) {
				continue;
			}
			switch (event.getType()) {
				case TOOL_CALL_PARSED -> {
					String tool = text(payload, "tool");
					if (tool != null) {
						priorCalls.add(new AgentRunner.PriorCall(tool, payload.get("arguments")));
					}
				}
				case TOOL_EXEC_STARTED -> {
					String tool = text(payload, "tool");
					if (tool != null) {
						toolCallCounts.merge(tool, 1, Integer::sum);
					}
				}
				case APPROVAL_GRANTED -> {
					// A run-scoped grant lives in the journal rather than a table, so the journal stays
					// the single record of why anything was allowed to happen.
					if ("RUN".equals(text(payload, "scope"))) {
						String tool = text(payload, "tool");
						if (tool != null) {
							runGrants.add(tool);
						}
					}
				}
				default -> {
					// Not relevant to rebuilding the loop's state.
				}
			}
		}

		if (approval.getDecision() == ApprovalDecision.APPROVED
				&& approval.getScope() == ApprovalScope.RUN) {
			runGrants.add(approval.getToolName());
		}

		return new AgentRunner.ResumeState(transcript, approval.getStepNo(), run.getToolCallsTotal(),
				run.getToolCallsInvalid(),
				new TokenUsage(run.getInputTokens(), run.getOutputTokens(), run.getReasoningTokens(),
						run.getInputTokens() + run.getOutputTokens()),
				toolCallCounts, runGrants, priorCalls, approval.getCallId(), approval.getToolName(),
				readJson(approval.getArgumentsJson()), readCalls(approval.getRemainingCallsJson()),
				approval.getDecision(),
				approval.getScope() == null ? null : approval.getScope().name(), approval.getNote());
	}

	/**
	 * The conversation as it stood at the end of a given step.
	 *
	 * <p>Used by forking. Messages carry the step they belong to, so cutting at a step boundary
	 * yields exactly what the model had been shown by that point -- which is the only sensible place
	 * to cut, since cutting mid-step could leave a tool call with no result.
	 */
	@Transactional(readOnly = true)
	public Transcript transcriptUpToStep(UUID runId, int uptoStep) {
		Transcript transcript = new Transcript();
		for (RunMessageEntity stored : messages.findByRunIdOrderBySeqAsc(runId)) {
			if (stored.getStepNo() > uptoStep) {
				break;
			}
			transcript.addAt(toMessage(stored), stored.getStepNo());
		}
		return transcript;
	}

	private Transcript rebuildTranscript(UUID runId) {
		Transcript transcript = new Transcript();
		for (RunMessageEntity stored : messages.findByRunIdOrderBySeqAsc(runId)) {
			transcript.addAt(toMessage(stored), stored.getStepNo());
		}
		return transcript;
	}

	private Message toMessage(RunMessageEntity stored) {
		Role role = stored.getRole();
		return switch (role) {
			case SYSTEM -> Message.system(stored.getContent());
			case USER -> Message.user(stored.getContent());
			case TOOL -> Message.toolResult(stored.getToolCallId(), stored.getToolName(),
					stored.getContent(), stored.isTruncated());
			case ASSISTANT -> stored.getArgumentsJson() == null
					? Message.assistant(stored.getContent(), stored.getReasoning())
					: Message.assistantToolCalls(stored.getContent(), stored.getReasoning(),
							readCalls(stored.getArgumentsJson()));
		};
	}

	private List<ToolCallRequest> readCalls(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			List<ToolCallRequest> calls = new ArrayList<>();
			JsonNode node = objectMapper.readTree(json);
			if (node.isArray()) {
				for (JsonNode element : node) {
					calls.add(new ToolCallRequest(text(element, "id"), text(element, "name"),
							text(element, "rawArguments")));
				}
			}
			return calls;
		}
		catch (RuntimeException ex) {
			log.warn("Could not read stored tool calls; continuing without them: {}", json, ex);
			return List.of();
		}
	}

	private JsonNode readJson(String json) {
		if (json == null || json.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(json);
		}
		catch (RuntimeException ex) {
			return objectMapper.createObjectNode();
		}
	}

	private JsonNode readPayload(RunEventEntity event) {
		if (event.getPayload() == null || event.getPayload().isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(event.getPayload());
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return (value != null && value.isString()) ? value.stringValue() : null;
	}
}
