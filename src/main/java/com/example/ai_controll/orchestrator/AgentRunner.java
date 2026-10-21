package com.example.ai_controll.orchestrator;

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
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.ContextAssembler;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.TokenEstimator;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.conversation.Transcript;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.ModelContextResolver;
import com.example.ai_controll.lmstudio.ModelSlot;
import com.example.ai_controll.lmstudio.port.ChatModelClient;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.lmstudio.port.ChatStreamEvent;
import com.example.ai_controll.lmstudio.port.TokenUsage;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.observability.EventPayloads;
import com.example.ai_controll.observability.RawBody;
import com.example.ai_controll.orchestrator.approval.ApprovalDecision;
import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.domain.RunStatus;
import com.example.ai_controll.orchestrator.guard.BudgetTracker;
import com.example.ai_controll.orchestrator.guard.LoopDetector;
import com.example.ai_controll.orchestrator.output.ResponseValidator;
import com.example.ai_controll.tool.exec.ToolExecutor;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.parse.ToolCallExtractor;
import com.example.ai_controll.tool.policy.ToolPolicyEngine;
import com.example.ai_controll.tool.registry.ResolvedToolset;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolsetResolver;
import com.example.ai_controll.tool.schema.ToolArgumentValidator;
import com.example.ai_controll.tool.schema.ValidationOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The agentic loop.
 *
 * <p>Everything else in the harness exists to serve this class. Its job is not to make the model
 * succeed; it is to make the model's behaviour <em>bounded and legible</em>. Every run ends in a
 * named state, every state says which guardrail produced it, and every decision on the way is in the
 * journal.
 *
 * <p>The guardrails, and what each defends against:
 *
 * <ul>
 *   <li><strong>Step ceiling</strong> -- a model that never stops calling tools.</li>
 *   <li><strong>Token and time budgets</strong> -- one making progress too slowly to be worth
 *       continuing. The remaining budget is clamped into each request, so it binds rather than
 *       merely being observed between steps.</li>
 *   <li><strong>Validation with repair feedback</strong> -- invented tool names, malformed JSON,
 *       missing fields; rejected with a correction the model can act on.</li>
 *   <li><strong>Repair ceiling</strong> -- a model that cannot act on the correction.</li>
 *   <li><strong>Loop detection</strong> -- the same call repeated; nudged once, then stopped.</li>
 *   <li><strong>Policy</strong> -- calls that need a person, or must not run at all.</li>
 *   <li><strong>Output truncation</strong> -- a tool result too large for the context window.</li>
 *   <li><strong>Context assembly</strong> -- a conversation that outgrew the window, which the
 *       server would otherwise truncate from the front, taking the system prompt and the task with
 *       it.</li>
 *   <li><strong>Answer schema</strong> -- a final answer that does not match the contract the run
 *       requires, rejected with the same kind of correction a bad tool call gets.</li>
 *   <li><strong>Cancellation</strong> -- a person deciding the run should stop.</li>
 * </ul>
 *
 * <p>A run that needs approval does not block. It ends its thread in {@link RunStatus#AWAITING_APPROVAL}
 * carrying the parked call, and {@link #resume} rebuilds the loop's state from what was recorded and
 * carries on. Blocking a virtual thread would be cheaper to write, but every parked run would then
 * vanish on restart -- and the replay path this forces into existence is the same one phase 5 needs.
 */
@Component
public class AgentRunner {

	private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

	private final ChatModelClient chatClient;

	private final ToolsetResolver toolsetResolver;

	private final ToolCallExtractor extractor;

	private final ToolArgumentValidator validator;

	private final ToolPolicyEngine policyEngine;

	private final List<ToolExecutor> executors;

	private final EventJournal journal;

	private final ObjectMapper objectMapper;

	private final ContextAssembler contextAssembler;

	private final TokenEstimator tokenEstimator;

	private final ModelContextResolver contextResolver;

	private final ModelSlot modelSlot;

	private final RunCancellation cancellation;

	private final ResponseValidator responseValidator;

	private final int maxRawBodyChars;

	private final int maxAttempts;

	private final long retryBackoffMs;

	public AgentRunner(ChatModelClient chatClient, ToolsetResolver toolsetResolver,
			ToolCallExtractor extractor, ToolArgumentValidator validator,
			ToolPolicyEngine policyEngine, List<ToolExecutor> executors, EventJournal journal,
			ObjectMapper objectMapper, ContextAssembler contextAssembler,
			TokenEstimator tokenEstimator, ModelContextResolver contextResolver, ModelSlot modelSlot,
			RunCancellation cancellation, ResponseValidator responseValidator,
			HarnessProperties properties) {
		this.chatClient = chatClient;
		this.toolsetResolver = toolsetResolver;
		this.extractor = extractor;
		this.validator = validator;
		this.policyEngine = policyEngine;
		this.executors = List.copyOf(executors);
		this.journal = journal;
		this.objectMapper = objectMapper;
		this.contextAssembler = contextAssembler;
		this.tokenEstimator = tokenEstimator;
		this.contextResolver = contextResolver;
		this.modelSlot = modelSlot;
		this.cancellation = cancellation;
		this.responseValidator = responseValidator;
		this.maxRawBodyChars = properties.journal().maxRawBodyChars();
		this.maxAttempts = Math.max(1, properties.lmStudio().maxRetries() + 1);
		this.retryBackoffMs = properties.lmStudio().retryBackoff().toMillis();
	}

	/** Starts a run from the beginning. */
	public Result execute(UUID runId, RunConfig config, Transcript transcript) {
		return execute(runId, config, transcript, 0);
	}

	/**
	 * Starts a run whose step numbering continues from an inherited conversation.
	 *
	 * <p>A fork carries its parent's messages, each stamped with the step it belonged to. Restarting
	 * at zero would give the fork's own messages step numbers that collide with the inherited ones,
	 * and forking that fork would then cut at whichever boundary came first rather than the one
	 * asked for -- silently, and in a feature whose entire purpose is cutting at a chosen point.
	 *
	 * @param fromStep the last step already present in the transcript
	 */
	public Result execute(UUID runId, RunConfig config, Transcript transcript, int fromStep) {
		ResolvedToolset toolset = toolsetResolver.resolve(config.toolNames(), config.toolVersions());
		State state = new State(transcript, new BudgetTracker(config.tokenBudget(),
				config.wallClockBudgetMs(), Instant.now()), new LoopDetector(config.loopThreshold()));
		state.contextLength = resolveContextLength(config);
		state.stepNo = Math.max(0, fromStep);
		// A fork inherits another run's steps, so its own allowance starts fresh from where the
		// inherited conversation stopped. Counting the parent's steps against it would mean a fork
		// taken late in a long run had almost no room to do anything.
		state.stepCeiling = state.stepNo + config.maxSteps();
		return loop(runId, config, toolset, state);
	}

	/**
	 * Carries on a run that stopped for approval.
	 *
	 * <p>The state is rebuilt rather than remembered: transcript, budget totals and loop counts all
	 * come back from what was recorded. Rebuilding the loop counts matters more than it looks -- a run
	 * resumed with a clean detector could loop forever precisely because a human paused to look at it.
	 */
	public Result resume(UUID runId, RunConfig config, ResumeState resumeState) {
		ResolvedToolset toolset = toolsetResolver.resolve(config.toolNames(), config.toolVersions());

		BudgetTracker budget = new BudgetTracker(config.tokenBudget(), config.wallClockBudgetMs(),
				Instant.now());
		budget.add(resumeState.usage());

		LoopDetector detector = new LoopDetector(config.loopThreshold());
		resumeState.priorCalls().forEach(prior -> detector.seed(prior.toolName(), prior.arguments()));

		State state = new State(resumeState.transcript(), budget, detector);
		state.contextLength = resolveContextLength(config);
		state.stepNo = resumeState.stepNo();
		// Absolute, unlike a fork: these are this run's own steps, already counted. Granting a fresh
		// allowance on every resume would let a run that parks repeatedly exceed its step limit
		// without bound -- the guardrail would be reset by the act of asking a human about it.
		state.stepCeiling = config.maxSteps();
		state.transcript.currentStep(state.stepNo);
		state.toolCallsTotal = resumeState.toolCallsTotal();
		state.toolCallsInvalid = resumeState.toolCallsInvalid();
		state.runGrants.addAll(resumeState.runGrants());
		state.toolCallCounts.putAll(resumeState.toolCallCounts());

		journal.append(runId, state.stepNo, RunEventType.RUN_RESUMED,
				new EventPayloads.RunResumed(resumeState.decision().name(), resumeState.toolName(),
						resumeState.scopeName()));

		// The decided call first, then whatever else that step had queued behind it.
		Optional<Result> terminal = applyDecision(runId, config, toolset, state, resumeState);
		if (terminal.isPresent()) {
			return terminal.get();
		}
		terminal = processCalls(runId, config, toolset, state, resumeState.remainingCalls());
		if (terminal.isPresent()) {
			return terminal.get();
		}

		return loop(runId, config, toolset, state);
	}

	// -------------------------------------------------------------------------------------------
	// The loop
	// -------------------------------------------------------------------------------------------

	private Result loop(UUID runId, RunConfig config, ResolvedToolset toolset, State state) {
		while (state.stepNo < state.stepCeiling) {
			// Checked before committing to another model call rather than only after one, so a stop
			// takes effect at the next boundary instead of one whole step later.
			if (cancellation.shouldStop(runId)) {
				return cancelled(state);
			}

			state.stepNo++;
			// Stamped on every message added from here, so a fork can cut at a step boundary.
			state.transcript.currentStep(state.stepNo);

			Optional<String> overBudget = state.budget.exceeded();
			if (overBudget.isPresent()) {
				return budgetExceeded(runId, state, overBudget.get());
			}

			ChatResult response;
			try {
				response = callModel(runId, config, toolset, state);
			}
			catch (ContextTooLarge ex) {
				// Named rather than reported as a failure: the remedy is specific and the run did
				// nothing wrong.
				return state.terminal(RunStatus.ABORTED_CONTEXT, null, ex.getMessage());
			}
			catch (RuntimeException ex) {
				if (cancellation.shouldStop(runId)) {
					// The call was interrupted because someone stopped the run, not because it broke.
					return cancelled(state);
				}
				journal.append(runId, state.stepNo, RunEventType.RUN_FAILED, new EventPayloads.RunFailed(
						ex.getMessage(), ex.getClass().getSimpleName(), null));
				return state.terminal(RunStatus.FAILED, null, ex.getMessage());
			}

			state.budget.add(response.usage());
			recordResponse(runId, state.stepNo, response);

			if (response.truncatedBeforeAnswering()) {
				return state.terminal(RunStatus.ABORTED_BUDGET, null,
						"The model exhausted its output allowance before producing an answer; "
								+ "raise maxOutputTokens or lower the reasoning effort.");
			}

			ToolCallExtractor.Extraction extraction = extractor.extract(response);

			if (!extraction.hasToolCalls()) {
				String answer = extraction.remainingContent();
				state.transcript.add(Message.assistant(answer, response.reasoning()));

				Optional<Result> rejected = checkAnswer(runId, config, state, answer);
				if (rejected.isPresent()) {
					return rejected.get();
				}
				if (state.answerRejected) {
					// Corrected and put back to the model; the next step is its second attempt.
					state.answerRejected = false;
					continue;
				}

				journal.append(runId, state.stepNo, RunEventType.RUN_FINISHED,
						new EventPayloads.RunFinished(RunStatus.FINISHED, answer, state.stepNo,
								state.budget.total(), state.budget.elapsedMs()));
				return state.terminal(RunStatus.FINISHED, answer, null);
			}

			// The model wants to continue. A completed answer is honoured even if it took the last of
			// the budget, but starting more work on an exhausted budget is not.
			Optional<String> spent = state.budget.exceeded();
			if (spent.isPresent()) {
				return budgetExceeded(runId, state, spent.get());
			}

			state.transcript.add(Message.assistantToolCalls(extraction.remainingContent(),
					response.reasoning(), extraction.toolCalls()));

			Optional<Result> terminal = processCalls(runId, config, toolset, state,
					extraction.toolCalls());
			if (terminal.isPresent()) {
				return terminal.get();
			}
		}

		journal.append(runId, state.stepNo, RunEventType.RUN_FINISHED,
				new EventPayloads.RunFinished(RunStatus.ABORTED_MAX_STEPS, null, state.stepNo,
						state.budget.total(), state.budget.elapsedMs()));
		return state.terminal(RunStatus.ABORTED_MAX_STEPS, null,
				"Reached the limit of %d steps without a final answer.".formatted(config.maxSteps()));
	}

	/**
	 * Runs one step's tool calls.
	 *
	 * @return a terminal result when a guardrail or an approval stops the run, otherwise empty
	 */
	private Optional<Result> processCalls(UUID runId, RunConfig config, ResolvedToolset toolset,
			State state, List<ToolCallRequest> calls) {

		int repairsThisStep = 0;

		for (int i = 0; i < calls.size(); i++) {
			if (cancellation.shouldStop(runId)) {
				// Between calls, so a stop never lands in the middle of a tool that is already running.
				return Optional.of(cancelled(state));
			}

			ToolCallRequest call = calls.get(i);
			state.toolCallsTotal++;

			ValidationOutcome outcome = validator.validate(call, toolset.schemas(),
					config.lenientArguments());

			if (!outcome.isValid()) {
				state.toolCallsInvalid++;
				repairsThisStep++;
				journal.append(runId, state.stepNo, RunEventType.TOOL_CALL_INVALID,
						new EventPayloads.ToolCallInvalid(call.name(), call.rawArguments(),
								outcome.status().name(), outcome.feedback(), "native"));
				state.transcript.add(Message.toolResult(call.id(), call.name(),
						outcome.feedback().toString(), false));

				if (repairsThisStep > config.maxRepairsPerStep()) {
					return Optional.of(state.terminal(RunStatus.ABORTED_UNREPAIRABLE, null,
							("The model produced %d invalid tool calls in one step, more than the %d "
									+ "allowed. It is not acting on the corrections it is being given.")
											.formatted(repairsThisStep, config.maxRepairsPerStep())));
				}
				continue;
			}

			journal.append(runId, state.stepNo, RunEventType.TOOL_CALL_PARSED,
					new EventPayloads.ToolCallParsed(call.name(), outcome.arguments(), "native", false));

			Optional<LoopDetector.Repetition> repetition =
					state.loopDetector.record(call.name(), outcome.arguments());
			if (repetition.isPresent()) {
				LoopDetector.Repetition repeat = repetition.get();
				journal.append(runId, state.stepNo, RunEventType.LOOP_DETECTED,
						new EventPayloads.LoopDetected(repeat.signature(), repeat.count(),
								repeat.threshold()));

				if (repeat.count() > repeat.threshold()) {
					return Optional.of(state.terminal(RunStatus.ABORTED_LOOP, null,
							"The model repeated the same tool call without making progress."));
				}
				state.transcript.add(Message.toolResult(call.id(), call.name(), repeat.nudge(), false));
				continue;
			}

			ToolPolicyEngine.Decision decision = policyEngine.evaluate(call.name(),
					state.callsSoFar(call.name()), state.runGrants);

			if (decision.isDenied()) {
				// Reported to the model as a tool failure so it can take another route, rather than
				// ending the run on its behalf.
				journal.append(runId, state.stepNo, RunEventType.TOOL_CALL_DENIED,
						new EventPayloads.ToolCallDenied(call.name(), decision.policyName(),
								decision.reason()));
				state.transcript.add(Message.toolResult(call.id(), call.name(),
						"{\"error\":\"policy_denied\",\"message\":\"%s\"}"
								.formatted(escape(decision.reason())), false));
				continue;
			}

			if (decision.needsApproval()) {
				// Park here, carrying the rest of the step. A model may emit several calls at once and
				// dropping the ones queued behind this would make the run quietly do less than it was
				// asked to.
				List<ToolCallRequest> remaining = new ArrayList<>(calls.subList(i + 1, calls.size()));
				journal.append(runId, state.stepNo, RunEventType.TOOL_APPROVAL_REQUIRED,
						new EventPayloads.ApprovalRequired(call.name(), outcome.arguments(),
								decision.policyName(), decision.reason()));

				Result parked = state.terminal(RunStatus.AWAITING_APPROVAL, null, decision.reason());
				return Optional.of(parked.withPending(new PendingCall(state.stepNo, call.id(),
						call.name(), outcome.arguments(), remaining, decision.policyName(),
						decision.reason())));
			}

			runToolAndRecord(runId, config, toolset, state, call, outcome.arguments(),
					decision.timeoutMs());
		}

		return Optional.empty();
	}

	/** Applies an approval decision to the call a run was parked on. */
	private Optional<Result> applyDecision(UUID runId, RunConfig config, ResolvedToolset toolset,
			State state, ResumeState resumeState) {

		ToolCallRequest call = new ToolCallRequest(resumeState.callId(), resumeState.toolName(), "");

		if (resumeState.decision() == ApprovalDecision.DENIED) {
			journal.append(runId, state.stepNo, RunEventType.APPROVAL_DENIED,
					new EventPayloads.ApprovalDecided(resumeState.toolName(), "DENIED",
							resumeState.scopeName(), resumeState.note()));
			state.transcript.add(Message.toolResult(call.id(), call.name(),
					"{\"error\":\"approval_denied\",\"message\":\"A person declined this call.%s\"}"
							.formatted(resumeState.note() == null ? ""
									: " " + escape(resumeState.note())), false));
			return Optional.empty();
		}

		journal.append(runId, state.stepNo, RunEventType.APPROVAL_GRANTED,
				new EventPayloads.ApprovalDecided(resumeState.toolName(), "APPROVED",
						resumeState.scopeName(), resumeState.note()));

		runToolAndRecord(runId, config, toolset, state, call, resumeState.arguments(), null);
		return Optional.empty();
	}

	// -------------------------------------------------------------------------------------------

	private void runToolAndRecord(UUID runId, RunConfig config, ResolvedToolset toolset, State state,
			ToolCallRequest call, JsonNode arguments, Long timeoutMs) {

		state.countCall(call.name());
		ToolDefinitionEntity definition = toolset.definition(call.name()).orElse(null);
		if (definition == null) {
			// The toolset changed between parking and resuming.
			state.transcript.add(Message.toolResult(call.id(), call.name(),
					"{\"error\":\"tool_unavailable\",\"message\":\"This tool is no longer available.\"}",
					false));
			return;
		}

		journal.append(runId, state.stepNo, RunEventType.TOOL_EXEC_STARTED,
				new EventPayloads.ToolExecStarted(call.name(), definition.getExecutorType().name(),
						definition.getVersion(), arguments));

		ToolExecutor executor = executors.stream()
				.filter(candidate -> candidate.type() == definition.getExecutorType())
				.findFirst().orElse(null);

		ToolOutcome outcome = (executor == null)
				? ToolOutcome.failure("no_executor",
						"No executor is registered for type %s".formatted(definition.getExecutorType()), 0L)
				: executor.execute(
						new ToolInvocation(runId, state.stepNo, call.id(), definition, arguments));

		// Truncate before the result reaches the transcript: a large body would otherwise consume the
		// context window the rest of the run still needs, which on a small model is fatal.
		if (outcome.output() != null && outcome.output().length() > config.toolOutputMaxChars()) {
			outcome = outcome.truncatedTo(RawBody.cap(outcome.output(), config.toolOutputMaxChars()));
		}

		journal.append(runId, state.stepNo,
				outcome.succeeded() ? RunEventType.TOOL_EXEC_SUCCEEDED : RunEventType.TOOL_EXEC_FAILED,
				new EventPayloads.ToolExecFinished(call.name(), outcome.succeeded(), outcome.errorKind(),
						outcome.output(), outcome.truncated()),
				outcome.durationMs());

		if (!outcome.succeeded()) {
			log.info("Tool '{}' failed in run {} step {}: {}", call.name(), runId, state.stepNo,
					outcome.errorKind());
		}
		state.transcript.add(Message.toolResult(call.id(), call.name(), outcome.output(),
				outcome.truncated()));
	}

	private Result budgetExceeded(UUID runId, State state, String reason) {
		journal.append(runId, state.stepNo, RunEventType.BUDGET_EXCEEDED,
				new EventPayloads.BudgetExceeded(reason, state.budget.total(), state.budget.elapsedMs()));
		return state.terminal(RunStatus.ABORTED_BUDGET, null, reason);
	}

	private ChatResult callModel(UUID runId, RunConfig config, ResolvedToolset toolset, State state) {
		Integer allowance = remainingOutputAllowance(config, state.budget);

		// What the model is shown is decided here rather than by whatever the conversation has grown
		// to. A request that overflows the window is truncated by the server from the front, which
		// takes the system prompt and the task first.
		ContextAssembler.Assembly assembly = contextAssembler.assemble(state.transcript.messages(),
				toolset.schemas(), state.contextLength, allowance == null ? 0 : allowance);

		if (assembly.compacted() || !assembly.fits()) {
			journal.append(runId, state.stepNo, RunEventType.CONTEXT_COMPACTED,
					new EventPayloads.ContextCompacted(
							state.contextLength == null ? 0 : state.contextLength, assembly.budgetTokens(),
							assembly.estimatedTokensBefore(), assembly.estimatedTokensAfter(),
							assembly.droppedMessages(), assembly.shortenedResults(), assembly.fits()));
		}
		if (!assembly.fits()) {
			throw new ContextTooLarge(
					("The conversation needs about %d tokens but only %d are available in a %d-token "
							+ "window, even after compaction. Load the model with a larger context_length, "
							+ "lower toolOutputMaxChars, or offer fewer tools.").formatted(
									assembly.estimatedTokensAfter(), assembly.budgetTokens(),
									state.contextLength == null ? 0 : state.contextLength));
		}

		ChatRequest request = new ChatRequest(config.modelKey(), assembly.messages(),
				toolset.schemas(), config.sampling(), allowance, List.of(), config.responseFormat());

		ChatResult result;
		long queuedMs;
		try {
			// One generation at a time unless configured otherwise: a second request does not run
			// alongside the first, it queues inside the server and inherits its latency.
			queuedMs = modelSlot.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The run was interrupted while waiting for the model", ex);
		}
		try {
			result = callWithRetry(runId, state.stepNo, config, request);
		}
		finally {
			modelSlot.release();
		}
		state.budget.addQueuedMs(queuedMs);

		// The estimate is checked against what the server actually charged, so the next step's
		// compaction decision rests on a ratio measured from this model rather than assumed of it.
		int chars = tokenEstimator.charsOf(assembly.messages(), toolset.schemas());
		tokenEstimator.calibrate(chars, assembly.messages().size(), toolset.schemas().size(),
				result.usage().promptTokens());

		journal.append(runId, state.stepNo, RunEventType.MODEL_REQUEST,
				new EventPayloads.ModelRequest(RawBody.cap(result.rawRequestJson(), maxRawBodyChars),
						request.messages().size(), request.tools().size(),
						assembly.estimatedTokensAfter(), queuedMs));
		return result;
	}

	/**
	 * Makes the call, trying again on a failure that is plausibly transient.
	 *
	 * <p>Worth having because the commonest such failure is entirely ordinary: LM Studio answers 503
	 * for a few seconds while a model loads, and without a retry that ends a run and, inside a batch,
	 * taints a whole variant for a reason that has nothing to do with the model. Every retry is
	 * journalled, because a retry nobody can see is a way of hiding instability rather than surviving
	 * it.
	 */
	private ChatResult callWithRetry(UUID runId, int stepNo, RunConfig config, ChatRequest request) {
		for (int attempt = 1; ; attempt++) {
			try {
				return config.streaming() ? streamModel(runId, stepNo, request)
						: chatClient.complete(request);
			}
			catch (RuntimeException ex) {
				if (attempt >= maxAttempts || !isRetryable(ex) || cancellation.shouldStop(runId)) {
					throw ex;
				}
				journal.append(runId, stepNo, RunEventType.MODEL_RETRY,
						new EventPayloads.ModelRetry(attempt, maxAttempts, ex.getMessage(),
								retryBackoffMs));
				log.info("Model call failed on attempt {} of {} for run {}: {}", attempt, maxAttempts,
						runId, ex.getMessage());
				sleepBeforeRetry();
			}
		}
	}

	/**
	 * Whether a failure is worth trying again.
	 *
	 * <p>Deliberately narrow. A 4xx means the request was wrong and will be wrong the second time, so
	 * retrying there only doubles the cost of a bug. An unreachable server, a rate limit and a
	 * server-side error are the cases where trying again is the difference between a lost run and a
	 * short pause.
	 */
	private static boolean isRetryable(RuntimeException ex) {
		if (!(ex instanceof LmStudioException lmStudio)) {
			return false;
		}
		if (lmStudio.isUnreachable()) {
			return true;
		}
		int status = lmStudio.getStatusCode().value();
		return status == 429 || status >= 500;
	}

	private void sleepBeforeRetry() {
		try {
			Thread.sleep(retryBackoffMs);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The run was interrupted while waiting to retry",
					interrupted);
		}
	}

	/**
	 * Holds the answer to the contract the run declared, if it declared one.
	 *
	 * @return a terminal result when the model has used up its corrections; otherwise empty, with a
	 *         rejection that leaves the run going flagged on the state instead
	 */
	private Optional<Result> checkAnswer(UUID runId, RunConfig config, State state, String answer) {
		if (!config.hasResponseSchema()) {
			return Optional.empty();
		}
		ResponseValidator.Result verdict = responseValidator.validate(answer, config.responseSchema());
		if (verdict.valid()) {
			return Optional.empty();
		}

		state.outputRepairs++;
		journal.append(runId, state.stepNo, RunEventType.OUTPUT_INVALID,
				new EventPayloads.OutputInvalid(RawBody.cap(answer, maxRawBodyChars), verdict.reason(),
						verdict.feedback()));

		if (state.outputRepairs > config.maxOutputRepairs()) {
			return Optional.of(state.terminal(RunStatus.ABORTED_UNREPAIRABLE, answer,
					("The model produced %d answers that did not match the required schema, more than "
							+ "the %d corrections allowed.").formatted(state.outputRepairs,
									config.maxOutputRepairs())));
		}

		// Addressed to the model as a user turn: there is no tool call to answer, and a plain
		// instruction carrying the schema is what a small model can actually act on.
		state.transcript.add(Message.user(verdict.feedback().toString()));
		state.answerRejected = true;
		return Optional.empty();
	}

	private Result cancelled(State state) {
		// The interrupt is cleared so that persisting the transcript afterwards is not itself
		// interrupted. The run is already ending; the flag has done its job.
		Thread.interrupted();
		return state.terminal(RunStatus.CANCELLED, null, "Stopped by a person.");
	}

	/** The window this run works within: what it was told, or what the server says it loaded. */
	private Integer resolveContextLength(RunConfig config) {
		if (config.contextLength() != null && config.contextLength() > 0) {
			return config.contextLength();
		}
		return contextResolver.contextLengthFor(config.modelKey()).orElse(null);
	}

	/**
	 * The output allowance for the next call, clamped to what the token budget has left.
	 *
	 * <p>Without this the budget would be advisory rather than binding: it is checked between steps,
	 * so a single call is free to spend far more than the whole allowance and still report success.
	 */
	private Integer remainingOutputAllowance(RunConfig config, BudgetTracker budget) {
		if (config.tokenBudget() == null) {
			return config.maxOutputTokens();
		}
		int remaining = config.tokenBudget() - budget.total().totalTokens();
		if (config.maxOutputTokens() == null) {
			return Math.max(1, remaining);
		}
		return Math.max(1, Math.min(config.maxOutputTokens(), remaining));
	}

	private ChatResult streamModel(UUID runId, int stepNo, ChatRequest request) {
		List<ChatResult> completed = new ArrayList<>(1);
		List<String> failure = new ArrayList<>(1);

		chatClient.stream(request, event -> {
			switch (event) {
				case ChatStreamEvent.ReasoningDelta delta -> journal.publishEphemeral(runId, stepNo,
						RunEventType.REASONING_DELTA, new EventPayloads.Delta(delta.text()));
				case ChatStreamEvent.ContentDelta delta -> journal.publishEphemeral(runId, stepNo,
						RunEventType.CONTENT_DELTA, new EventPayloads.Delta(delta.text()));
				case ChatStreamEvent.Completed done -> completed.add(done.result());
				case ChatStreamEvent.Failed fail -> failure.add(fail.message());
				default -> {
					// Nothing the aggregate result does not already carry.
				}
			}
		});

		if (!failure.isEmpty()) {
			throw new IllegalStateException(failure.getFirst());
		}
		if (completed.isEmpty()) {
			throw new IllegalStateException("The model stream ended without producing a result");
		}
		return completed.getFirst();
	}

	private void recordResponse(UUID runId, int stepNo, ChatResult result) {
		journal.append(runId, stepNo, RunEventType.MODEL_RESPONSE,
				new EventPayloads.ModelResponse(RawBody.cap(result.rawResponseJson(), maxRawBodyChars),
						result.finishReason(), result.usage(), result.latency().toMillis(),
						result.content() == null ? 0 : result.content().length(),
						result.reasoning() == null ? 0 : result.reasoning().length(),
						result.toolCalls().size(),
						result.toolCalls().stream().map(ToolCallRequest::name).toList()),
				result.latency().toMillis());

		if (result.truncatedBeforeAnswering()) {
			journal.append(runId, stepNo, RunEventType.OUTPUT_TRUNCATED,
					new EventPayloads.OutputTruncated(result.usage().completionTokens(),
							result.usage().reasoningTokens(), result.usage().reasoningShare(),
							"The output allowance was spent before an answer was produced. Raise "
									+ "maxOutputTokens, or lower the model's reasoning effort."));
		}
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	// -------------------------------------------------------------------------------------------
	// State
	// -------------------------------------------------------------------------------------------

	/** Mutable working state for one pass through the loop. */
	private static final class State {

		private final Transcript transcript;

		private final BudgetTracker budget;

		private final LoopDetector loopDetector;

		private final Map<String, Integer> toolCallCounts = new HashMap<>();

		private final Set<String> runGrants = new HashSet<>();

		private int stepNo;

		/**
		 * The step number this run may not pass. Set by the entry point rather than derived, because
		 * a fork and a resume need different answers: a fork starts a fresh allowance from the
		 * inherited position, a resume continues within the one the run already has.
		 */
		private int stepCeiling;

		private int toolCallsTotal;

		private int toolCallsInvalid;

		/** How many answers were sent back for not matching the run's response schema. */
		private int outputRepairs;

		/** Set when the last answer was rejected and the model has been given another go. */
		private boolean answerRejected;

		/** The model's context window, resolved once: asking the server every step would be silly. */
		private Integer contextLength;

		State(Transcript transcript, BudgetTracker budget, LoopDetector loopDetector) {
			this.transcript = transcript;
			this.budget = budget;
			this.loopDetector = loopDetector;
		}

		int callsSoFar(String toolName) {
			return toolCallCounts.getOrDefault(toolName, 0);
		}

		void countCall(String toolName) {
			toolCallCounts.merge(toolName, 1, Integer::sum);
		}

		Result terminal(RunStatus status, String finalOutput, String error) {
			return new Result(status, finalOutput, stepNo, budget.total(), error, toolCallsTotal,
					toolCallsInvalid, toolCallCounts, runGrants, null);
		}
	}

	/**
	 * How a run ended.
	 *
	 * @param status         the named terminal state; never a bare success or failure
	 * @param pending        the parked call, when the status is AWAITING_APPROVAL
	 * @param toolCallCounts per-tool counts, carried so a resumed run keeps its rate limits
	 */
	public record Result(RunStatus status, String finalOutput, int stepsUsed, TokenUsage usage,
			String error, int toolCallsTotal, int toolCallsInvalid, Map<String, Integer> toolCallCounts,
			Set<String> runGrants, PendingCall pending) {

		public Result {
			toolCallCounts = toolCallCounts == null ? Map.of() : Map.copyOf(toolCallCounts);
			runGrants = runGrants == null ? Set.of() : Set.copyOf(runGrants);
		}

		Result withPending(PendingCall pendingCall) {
			return new Result(status, finalOutput, stepsUsed, usage, error, toolCallsTotal,
					toolCallsInvalid, toolCallCounts, runGrants, pendingCall);
		}

		public boolean isAwaitingApproval() {
			return status == RunStatus.AWAITING_APPROVAL;
		}
	}

	/**
	 * The conversation cannot be made to fit the model's window.
	 *
	 * <p>Its own type so the loop can name the outcome. Reported as a plain failure it would look
	 * like a bug in the harness, when it is in fact a configuration the operator can fix in one step.
	 */
	private static final class ContextTooLarge extends RuntimeException {

		ContextTooLarge(String message) {
			super(message);
		}
	}

	/** A tool call the run stopped on, with everything needed to carry on afterwards. */
	public record PendingCall(int stepNo, String callId, String toolName, JsonNode arguments,
			List<ToolCallRequest> remainingCalls, String policyName, String reason) {
	}

	/** A call that already happened, replayed into the loop detector when resuming. */
	public record PriorCall(String toolName, JsonNode arguments) {
	}

	/**
	 * Everything needed to pick a parked run back up.
	 *
	 * @param priorCalls calls already made, so the loop detector resumes with its counts intact
	 * @param runGrants  tools approved for the rest of this run
	 */
	public record ResumeState(Transcript transcript, int stepNo, int toolCallsTotal,
			int toolCallsInvalid, TokenUsage usage, Map<String, Integer> toolCallCounts,
			Set<String> runGrants, List<PriorCall> priorCalls, String callId, String toolName,
			JsonNode arguments, List<ToolCallRequest> remainingCalls, ApprovalDecision decision,
			String scopeName, String note) {

		public ResumeState {
			toolCallCounts = toolCallCounts == null ? Map.of() : Map.copyOf(toolCallCounts);
			runGrants = runGrants == null ? Set.of() : Set.copyOf(runGrants);
			priorCalls = priorCalls == null ? List.of() : List.copyOf(priorCalls);
			remainingCalls = remainingCalls == null ? List.of() : List.copyOf(remainingCalls);
		}
	}
}
