package com.example.ai_controll.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.ai_controll.api.view.RunViews;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.observability.RunEventBroadcaster;
import com.example.ai_controll.observability.RunEventView;
import com.example.ai_controll.orchestrator.RunService;
import com.example.ai_controll.orchestrator.StartRunCommand;

import jakarta.validation.Valid;

/**
 * Starting runs and observing them.
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

	private final RunService runService;

	private final EventJournal journal;

	private final RunEventBroadcaster broadcaster;

	private final com.example.ai_controll.orchestrator.RunRetentionService retention;

	public RunController(RunService runService, EventJournal journal,
			RunEventBroadcaster broadcaster,
			com.example.ai_controll.orchestrator.RunRetentionService retention) {
		this.runService = runService;
		this.journal = journal;
		this.broadcaster = broadcaster;
		this.retention = retention;
	}

	/**
	 * Starts a run and returns immediately with its id.
	 *
	 * <p>The run executes in the background; follow it on {@code /api/runs/{id}/stream}. A local model
	 * can take minutes, so holding the HTTP connection open for the duration would be fragile and
	 * would give no visibility while it happened.
	 */
	@PostMapping
	public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRunCommand command) {
		UUID runId = runService.start(command);
		return ResponseEntity.accepted().body(Map.of(
				"runId", runId,
				"stream", "/api/runs/%s/stream".formatted(runId),
				"detail", "/api/runs/%s".formatted(runId)));
	}

	/**
	 * Stops a run.
	 *
	 * <p>Cooperative, not violent: the run stops at the next point where it would have committed to
	 * more work, and keeps its transcript and journal. Stopping a run is nearly always a prelude to
	 * looking at what it did, so throwing that away would defeat the purpose.
	 *
	 * @return 200 when the run was stopped, 409 when it had already finished
	 */
	@PostMapping("/{runId}/cancel")
	public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID runId) {
		boolean stopped = runService.cancel(runId);
		return stopped
				? ResponseEntity.ok(Map.of("runId", runId, "cancelled", true))
				: ResponseEntity.status(409).body(Map.of("runId", runId, "cancelled", false,
						"message", "The run had already reached a terminal state."));
	}

	/**
	 * Deletes a run and everything recorded about it.
	 *
	 * <p>Irreversible, and it takes the journal with it. A run cited by an evaluation batch can still
	 * be deleted this way -- unlike the scheduled prune, which refuses to -- because an explicit
	 * request to remove a specific run is a decision, not a policy.
	 */
	@DeleteMapping("/{runId}")
	public ResponseEntity<Void> delete(@PathVariable UUID runId) {
		return retention.delete(runId) ? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

	/**
	 * Removes runs that finished before a cut-off, ignoring the configured retention period.
	 *
	 * <p>Here because the journal is large by design -- the exact bytes of every request and response
	 * -- and reclaiming space should not require waiting for a schedule or opening the database.
	 */
	@PostMapping("/prune")
	public Map<String, Object> prune(@RequestParam int olderThanDays) {
		int deleted = retention.pruneOlderThan(
				java.time.Instant.now().minus(java.time.Duration.ofDays(Math.max(0, olderThanDays))));
		return Map.of("deleted", deleted, "olderThanDays", olderThanDays);
	}

	@GetMapping
	public List<RunViews.RunSummary> recent(@RequestParam(defaultValue = "50") int limit) {
		return runService.recent(Math.clamp(limit, 1, 500));
	}

	@GetMapping("/{runId}")
	public ResponseEntity<RunViews.RunDetail> detail(@PathVariable UUID runId) {
		return runService.detail(runId).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** The recorded journal for a run, optionally only the part after a given position. */
	@GetMapping("/{runId}/events")
	public List<RunEventView> events(@PathVariable UUID runId,
			@RequestParam(required = false) Long afterSeq) {
		return afterSeq == null ? journal.eventsFor(runId) : journal.eventsAfter(runId, afterSeq);
	}

	/**
	 * Starts a new run from a position in this one's journal.
	 *
	 * <p>Answers "what if it had been asked differently at step 3" without repeating everything
	 * before it. The transcript is rebuilt up to the chosen step and the new run continues from
	 * there, linked to its parent so the lineage stays visible.
	 *
	 * @param uptoStep the last step to keep. Cutting at a step boundary rather than an arbitrary
	 *                 journal position matters: mid-step, a tool call could be left with no result
	 */
	@PostMapping("/{runId}/fork")
	public ResponseEntity<Map<String, Object>> fork(@PathVariable UUID runId,
			@RequestParam int uptoStep,
			@RequestBody(required = false) StartRunCommand overrides) {
		StartRunCommand command = overrides == null ? StartRunCommand.defaults() : overrides;
		UUID forkId = runService.fork(runId, uptoStep, command);
		return ResponseEntity.accepted().body(Map.of(
				"runId", forkId,
				"forkedFrom", runId,
				"atStep", uptoStep,
				"stream", "/api/runs/%s/stream".formatted(forkId)));
	}

	/**
	 * Live journal for a run over Server-Sent Events.
	 *
	 * <p>Replays what has already happened before attaching to the live feed, so a client that
	 * connects late -- or reconnects, via {@code Last-Event-ID} -- sees the whole run rather than only
	 * the part that happened to occur while it was listening.
	 */
	@GetMapping("/{runId}/stream")
	public SseEmitter stream(@PathVariable UUID runId,
			@RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
			@RequestParam(required = false) Long afterSeq) {

		Long resumeFrom = lastEventId != null ? lastEventId : afterSeq;

		// Subscribe before replaying. An event landing between the two arrives twice -- once live,
		// once in the backlog -- whereas the opposite order would drop it from both. Duplicates are
		// the better failure: every journalled event carries its seq, so a client discards one it has
		// already seen, but nothing can recover an event it was never sent.
		SseEmitter emitter = broadcaster.subscribe(runId);

		List<RunEventView> backlog = (resumeFrom == null) ? journal.eventsFor(runId)
				: journal.eventsAfter(runId, resumeFrom);
		try {
			for (RunEventView event : backlog) {
				emitter.send(SseEmitter.event()
						.id(Long.toString(event.seq()))
						.name(event.type().name())
						.data(event));
			}
		}
		catch (Exception ex) {
			emitter.completeWithError(ex);
			return emitter;
		}

		// A run that already ended produces nothing further, so close rather than leaving the client
		// waiting on a stream that will never speak again.
		runService.summary(runId).ifPresent(summary -> {
			if (summary.status().isTerminal()) {
				emitter.complete();
			}
		});

		return emitter;
	}
}
