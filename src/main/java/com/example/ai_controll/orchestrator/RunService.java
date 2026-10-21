package com.example.ai_controll.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.example.ai_controll.api.view.RunViews;
import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.Transcript;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.observability.EventPayloads;
import com.example.ai_controll.observability.RunEventBroadcaster;
import com.example.ai_controll.orchestrator.approval.ApprovalDecision;
import com.example.ai_controll.orchestrator.approval.ApprovalScope;
import com.example.ai_controll.orchestrator.approval.ApprovalService;
import com.example.ai_controll.orchestrator.approval.PendingApprovalEntity;
import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.domain.RunStatus;
import com.example.ai_controll.orchestrator.store.RunEntity;
import com.example.ai_controll.orchestrator.store.RunMessageEntity;
import com.example.ai_controll.orchestrator.store.RunMessageRepository;
import com.example.ai_controll.orchestrator.store.RunRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Owns the lifecycle of a run: creating it, handing it to the loop, and recording how it ended.
 *
 * <p>The loop itself lives in {@link AgentRunner}. Keeping the two apart means the orchestration
 * logic can be exercised without a database, and persistence can change without touching the part
 * that decides what the model does.
 *
 * <p>Runs execute off the request thread, so starting one returns immediately and the caller watches
 * the live stream. A local model can take minutes, and holding an HTTP connection open for that is
 * both fragile and useless for observation.
 */
@Service
public class RunService {

	private static final Logger log = LoggerFactory.getLogger(RunService.class);

	private final AgentRunner agentRunner;

	private final RunRepository runRepository;

	private final RunMessageRepository messageRepository;

	private final EventJournal journal;

	private final RunEventBroadcaster broadcaster;

	private final ExecutorService runExecutor;

	private final HarnessProperties properties;

	private final ObjectMapper objectMapper;

	private final ApprovalService approvals;

	private final RunCancellation cancellation;

	public RunService(AgentRunner agentRunner, RunRepository runRepository,
			RunMessageRepository messageRepository, EventJournal journal,
			RunEventBroadcaster broadcaster, ExecutorService runExecutor,
			HarnessProperties properties, ObjectMapper objectMapper, ApprovalService approvals,
			RunCancellation cancellation) {
		this.agentRunner = agentRunner;
		this.runRepository = runRepository;
		this.messageRepository = messageRepository;
		this.journal = journal;
		this.broadcaster = broadcaster;
		this.runExecutor = runExecutor;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.approvals = approvals;
		this.cancellation = cancellation;
	}

	/**
	 * Creates a run and begins executing it in the background.
	 *
	 * @return the run id, immediately, so the caller can subscribe to its live stream
	 */
	public UUID start(StartRunCommand command) {
		String modelKey = resolveModelKey(command.modelKey());
		RunConfig config = command.toConfig(modelKey);
		UUID runId = UUID.randomUUID();

		RunEntity run = new RunEntity(runId, modelKey, command.input(), Instant.now());
		// Stored so a run parked for approval can be resumed, and so it can be reproduced later.
		run.setConfigJson(objectMapper.writeValueAsString(config));
		runRepository.save(run);

		journal.append(runId, 0, RunEventType.RUN_STARTED,
				new EventPayloads.RunStarted(modelKey, command.systemPrompt(), command.input(),
						config.sampling().temperature(), config.maxOutputTokens(), config.streaming()));

		runExecutor.submit(() -> execute(runId, config, command.input()));
		return runId;
	}

	/**
	 * Creates a run and executes it on the calling thread.
	 *
	 * <p>For batch evaluation, which needs one repetition finished before the next begins. LM Studio
	 * serves a single model instance, so overlapping runs would queue against each other and corrupt
	 * the timing figures that are part of what the batch is measuring.
	 *
	 * @return the run id, once the run has reached a terminal state
	 */
	public UUID startAndAwait(StartRunCommand command) {
		return startAndAwait(command, runId -> {
		});
	}

	/**
	 * As {@link #startAndAwait(StartRunCommand)}, telling the caller the run id before it begins.
	 *
	 * <p>A batch needs this to be able to stop what it is waiting on. Without it the id only arrives
	 * once the run has ended, so cancelling a fifty-repetition batch would have to wait out the
	 * repetition in progress -- which on a local model is exactly the several minutes someone
	 * pressing stop is trying not to spend.
	 */
	public UUID startAndAwait(StartRunCommand command, Consumer<UUID> onCreated) {
		String modelKey = resolveModelKey(command.modelKey());
		RunConfig config = command.toConfig(modelKey);
		UUID runId = UUID.randomUUID();

		RunEntity run = new RunEntity(runId, modelKey, command.input(), Instant.now());
		run.setConfigJson(objectMapper.writeValueAsString(config));
		runRepository.save(run);

		journal.append(runId, 0, RunEventType.RUN_STARTED,
				new EventPayloads.RunStarted(modelKey, command.systemPrompt(), command.input(),
						config.sampling().temperature(), config.maxOutputTokens(), config.streaming()));

		onCreated.accept(runId);
		execute(runId, config, command.input());
		return runId;
	}

	/**
	 * Starts a run from a position in another run's journal.
	 *
	 * <p>Answers "what if it had been asked differently at step 3" without repeating everything
	 * before it. The transcript is rebuilt up to the chosen point and the new run continues from
	 * there under whatever settings are given, linked to its parent so the lineage stays visible.
	 *
	 * <p>The machinery is the one approvals already needed: rebuilding a conversation from what was
	 * recorded is exactly what resuming a parked run does.
	 */
	public UUID fork(UUID parentRunId, int uptoStep, StartRunCommand overrides) {
		RunEntity parent = runRepository.findById(parentRunId)
				.orElseThrow(() -> new IllegalArgumentException("No run " + parentRunId));

		Transcript rebuilt = approvals.transcriptUpToStep(parentRunId, uptoStep);
		if (rebuilt.isEmpty()) {
			throw new IllegalArgumentException(
					"Run %s has nothing recorded at or before step %d".formatted(parentRunId, uptoStep));
		}

		String modelKey = resolveModelKey(
				overrides.modelKey() != null ? overrides.modelKey() : parent.getModelKey());
		RunConfig config = overrides.toConfig(modelKey);

		UUID runId = UUID.randomUUID();
		RunEntity fork = new RunEntity(runId, modelKey,
				overrides.input() == null ? parent.getInput() : overrides.input(), Instant.now());
		fork.setConfigJson(objectMapper.writeValueAsString(config));
		fork.setParentRunId(parentRunId);
		fork.setForkedFromSeq((long) uptoStep);
		runRepository.save(fork);

		journal.append(runId, 0, RunEventType.RUN_STARTED,
				new EventPayloads.RunStarted(modelKey, config.systemPrompt(), fork.getInput(),
						config.sampling().temperature(), config.maxOutputTokens(), config.streaming()));

		// The fork's own steps continue from where the inherited conversation stopped, so its messages
		// cannot be confused with its parent's when this fork is itself forked.
		int inheritedStep = rebuilt.entries().stream()
				.mapToInt(Transcript.Entry::stepNo).max().orElse(0);
		rebuilt.currentStep(inheritedStep);

		// A new user turn is appended only when one was given; forking to re-run the same question
		// under different settings is just as common as forking to ask something different.
		if (overrides.input() != null && !overrides.input().isBlank()) {
			rebuilt.add(Message.user(overrides.input()));
		}

		runExecutor.submit(() -> executeFrom(runId, config, rebuilt, inheritedStep));
		return runId;
	}

	/** Runs the loop over an already-populated transcript, as a fork does. */
	private void executeFrom(UUID runId, RunConfig config, Transcript transcript, int fromStep) {
		Instant startedAt = Instant.now();
		cancellation.register(runId);
		try {
			AgentRunner.Result result = agentRunner.execute(runId, config, transcript, fromStep);
			persistTranscript(runId, transcript);
			finish(runId, result, startedAt);
		}
		catch (LmStudioException ex) {
			persistTranscript(runId, transcript);
			fail(runId, ex, ex.getStatusCode() == null ? null : ex.getStatusCode().value());
		}
		catch (RuntimeException ex) {
			persistTranscript(runId, transcript);
			fail(runId, ex, null);
		}
		finally {
			cancellation.release(runId);
			broadcaster.closeRun(runId);
			journal.forget(runId);
		}
	}

	/**
	 * Runs the loop and records the result.
	 *
	 * <p>Not transactional on purpose. A run can last minutes, and holding a database transaction
	 * open for its duration would both stop the live journal committing and pin a connection for no
	 * benefit. Each write commits on its own.
	 */
	private void execute(UUID runId, RunConfig config, String input) {
		Instant startedAt = Instant.now();
		Transcript transcript = Transcript.of(config.systemPrompt());
		transcript.add(Message.user(input));

		cancellation.register(runId);
		try {
			AgentRunner.Result result = agentRunner.execute(runId, config, transcript);
			persistTranscript(runId, transcript);
			finish(runId, result, startedAt);
		}
		catch (LmStudioException ex) {
			persistTranscript(runId, transcript);
			fail(runId, ex, ex.getStatusCode() == null ? null : ex.getStatusCode().value());
		}
		catch (RuntimeException ex) {
			// A bug in the harness rather than a guardrail. The transcript is still written: the
			// conversation that led to the crash is the most useful thing about it.
			persistTranscript(runId, transcript);
			fail(runId, ex, null);
		}
		finally {
			cancellation.release(runId);
			broadcaster.closeRun(runId);
			journal.forget(runId);
		}
	}

	/**
	 * Writes the transcript out.
	 *
	 * <p>Not annotated {@code @Transactional}: it is called from another method of this same bean, and
	 * Spring's transaction advice lives in a proxy that self-invocation bypasses, so the annotation
	 * would be silently inert. Spring Data already wraps {@code saveAll} in its own transaction, which
	 * is exactly the atomicity this needs.
	 */
	private void persistTranscript(UUID runId, Transcript transcript) {
		try {
			// Replaced rather than appended: a run may be persisted several times across pauses, and
			// appending would duplicate every message that existed before the pause.
			messageRepository.deleteAll(messageRepository.findByRunIdOrderBySeqAsc(runId));
			int seq = 0;
			Instant now = Instant.now();
			List<RunMessageEntity> entities = new ArrayList<>();
			for (Transcript.Entry entry : transcript.entries()) {
				Message message = entry.message();
				String argumentsJson = message.hasToolCalls()
						? objectMapper.writeValueAsString(message.toolCalls()) : null;
				// The real step, not zero: forking cuts at a step boundary and cannot do so if every
				// message claims to belong to the same one.
				entities.add(new RunMessageEntity(UUID.randomUUID(), runId, seq++, entry.stepNo(),
						message.role(), message.content(), message.reasoning(), message.toolCallId(),
						message.toolName(), argumentsJson, null, message.truncated(), now));
			}
			messageRepository.saveAll(entities);
		}
		catch (RuntimeException ex) {
			// Losing the transcript must not also cost us the run's recorded outcome, so this is
			// swallowed -- but loudly. A silent version of this hid a bug that stopped every transcript
			// saving for an entire session: the runs looked fine and only a fork revealed the loss.
			log.error("Could not persist the transcript for run {}. The run's outcome is recorded but "
					+ "its conversation is NOT, so replay and forking will not work for it.", runId, ex);
		}
	}

	/**
	 * Records a decision on a parked call and continues the run.
	 *
	 * <p>The continuation happens off the request thread, exactly like a fresh run: approving is a
	 * quick action, and what follows it can take minutes.
	 */
	public void decideAndResume(UUID runId, UUID approvalId, ApprovalDecision decision,
			ApprovalScope scope, String note) {
		PendingApprovalEntity approval = approvals.decide(approvalId, decision, scope, note);
		RunEntity run = runRepository.findById(runId)
				.orElseThrow(() -> new IllegalArgumentException("No run " + runId));

		RunConfig config = readConfig(run);
		AgentRunner.ResumeState resumeState = approvals.rebuild(run, approval);

		run.setStatus(RunStatus.RUNNING);
		run.setError(null);
		runRepository.save(run);

		runExecutor.submit(() -> resume(runId, config, resumeState));
	}

	private void resume(UUID runId, RunConfig config, AgentRunner.ResumeState resumeState) {
		Instant startedAt = Instant.now();
		cancellation.register(runId);
		try {
			AgentRunner.Result result = agentRunner.resume(runId, config, resumeState);
			persistTranscript(runId, resumeState.transcript());
			finish(runId, result, startedAt);
		}
		catch (LmStudioException ex) {
			persistTranscript(runId, resumeState.transcript());
			fail(runId, ex, ex.getStatusCode() == null ? null : ex.getStatusCode().value());
		}
		catch (RuntimeException ex) {
			persistTranscript(runId, resumeState.transcript());
			fail(runId, ex, null);
		}
		finally {
			cancellation.release(runId);
			broadcaster.closeRun(runId);
			journal.forget(runId);
		}
	}

	/**
	 * Stops a run.
	 *
	 * <p>A run that is executing is asked to stop and interrupted, and ends in {@link
	 * RunStatus#CANCELLED} with its transcript and journal intact -- the point of stopping a run is
	 * usually to look at what it did. A run parked for approval has no thread to interrupt, so it is
	 * ended here directly; leaving it parked forever would be the same as not having a stop at all.
	 *
	 * @return false when the run had already reached a terminal state, so there was nothing to stop
	 */
	public boolean cancel(UUID runId) {
		RunEntity run = runRepository.findById(runId)
				.orElseThrow(() -> new IllegalArgumentException("No run " + runId));
		if (run.getStatus() != null && run.getStatus().isTerminal()) {
			return false;
		}

		boolean reached = cancellation.cancel(runId);
		if (!reached) {
			// Parked, or running in another instance. Either way the record is settled here.
			run.setStatus(RunStatus.CANCELLED);
			run.setError("Stopped by a person.");
			run.setEndedAt(Instant.now());
			runRepository.save(run);
			journal.append(runId, run.getStepsUsed(), RunEventType.RUN_FINISHED,
					new EventPayloads.RunFinished(RunStatus.CANCELLED, run.getFinalOutput(),
							run.getStepsUsed(), null, 0L));
			broadcaster.closeRun(runId);
		}
		return true;
	}

	/** The settings a run was started with, so a resumed run behaves exactly as the original did. */
	private RunConfig readConfig(RunEntity run) {
		if (run.getConfigJson() == null || run.getConfigJson().isBlank()) {
			throw new IllegalStateException(
					"Run %s has no stored configuration and cannot be resumed".formatted(run.getId()));
		}
		return objectMapper.readValue(run.getConfigJson(), RunConfig.class);
	}

	private void finish(UUID runId, AgentRunner.Result result, Instant startedAt) {
		if (result.isAwaitingApproval() && result.pending() != null) {
			// The run keeps its transcript and totals; only its thread ends.
			approvals.park(runId, result.pending());
		}

		runRepository.findById(runId).ifPresent(run -> {
			run.setStatus(result.status());
			run.setFinalOutput(result.finalOutput());
			run.setStepsUsed(result.stepsUsed());
			// Assigned, not accumulated: a resumed run's tracker already contains what came before, so
			// adding its total again would double-count the whole first half of the run.
			run.setUsage(result.usage().promptTokens(), result.usage().completionTokens(),
					result.usage().reasoningTokens());
			run.setToolCallsTotal(result.toolCallsTotal());
			run.setToolCallsInvalid(result.toolCallsInvalid());
			run.setError(result.error());
			// A parked run has not ended; giving it an end time would make it look finished.
			run.setEndedAt(result.isAwaitingApproval() ? null : Instant.now());
			runRepository.save(run);
		});

		// AgentRunner journals RUN_FINISHED for the states it reaches deliberately; the guardrail
		// aborts are recorded here, so every run ends with exactly one terminal entry. A parked run
		// has not ended at all, so it gets none.
		if (result.status() != RunStatus.FINISHED && result.status() != RunStatus.ABORTED_MAX_STEPS
				&& !result.isAwaitingApproval()) {
			journal.append(runId, result.stepsUsed(), RunEventType.RUN_FINISHED,
					new EventPayloads.RunFinished(result.status(), result.finalOutput(),
							result.stepsUsed(), result.usage(),
							Duration.between(startedAt, Instant.now()).toMillis()));
		}

		log.info("Run {} ended as {} after {} step(s), {} tool call(s), {} invalid", runId,
				result.status(), result.stepsUsed(), result.toolCallsTotal(), result.toolCallsInvalid());
	}

	private void fail(UUID runId, Exception ex, Integer upstreamStatus) {
		log.warn("Run {} failed", runId, ex);

		runRepository.findById(runId).ifPresent(run -> {
			run.setStatus(RunStatus.FAILED);
			run.setError(ex.getMessage());
			run.setEndedAt(Instant.now());
			runRepository.save(run);
		});

		// RUN_FAILED says what went wrong; RUN_FINISHED says the run is over. Both, because the
		// journal is read two ways: to diagnose one run, and to count how runs end. Without the
		// second, a run that died from an escaped exception would be the only kind with no terminal
		// entry at all, and anything counting outcomes would silently omit exactly the outcomes worth
		// counting.
		journal.append(runId, 0, RunEventType.RUN_FAILED,
				new EventPayloads.RunFailed(ex.getMessage(), ex.getClass().getSimpleName(),
						upstreamStatus));
		journal.append(runId, 0, RunEventType.RUN_FINISHED,
				new EventPayloads.RunFinished(RunStatus.FAILED, null, 0, null, 0L));
	}

	// -------------------------------------------------------------------------------------------
	// Queries
	// -------------------------------------------------------------------------------------------

	public Optional<RunViews.RunDetail> detail(UUID runId) {
		return runRepository.findById(runId).map(run -> new RunViews.RunDetail(
				RunViews.RunSummary.from(run), run.getFinalOutput(),
				messageRepository.findByRunIdOrderBySeqAsc(runId).stream()
						.map(RunViews.MessageView::from).toList()));
	}

	public Optional<RunViews.RunSummary> summary(UUID runId) {
		return runRepository.findById(runId).map(RunViews.RunSummary::from);
	}

	public List<RunViews.RunSummary> recent(int limit) {
		return runRepository.findAllByOrderByStartedAtDesc(Limit.of(limit)).stream()
				.map(RunViews.RunSummary::from).toList();
	}

	private String resolveModelKey(String requested) {
		if (requested != null && !requested.isBlank()) {
			return requested;
		}
		if (properties.lmStudio().hasDefaultModel()) {
			return properties.lmStudio().defaultModel();
		}
		throw new IllegalArgumentException(
				"No model given and harness.lm-studio.default-model is not set. "
						+ "List the available keys with GET /api/models.");
	}
}
