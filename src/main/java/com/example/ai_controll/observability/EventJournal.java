package com.example.ai_controll.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.orchestrator.domain.RunEventType;
import com.example.ai_controll.orchestrator.store.RunEventEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The append-only record of everything the harness does.
 *
 * <p>This is the single source of truth for monitoring, replay and evaluation assertions, which is
 * why it is built before anything depends on it: retrofitting a journal onto an orchestrator that
 * already exists means reconstructing what happened instead of recording it.
 *
 * <p>Two properties matter and are enforced here rather than left to callers:
 *
 * <ul>
 *   <li><strong>Each entry commits immediately.</strong> Writes run in their own transaction so that
 *       a live viewer sees a run progress rather than getting everything at the end, and so that a
 *       run that dies still leaves behind the evidence of how far it got.</li>
 *   <li><strong>Journalling never breaks a run.</strong> A failure to record is logged and swallowed;
 *       losing an observation is bad, losing the work being observed is worse.</li>
 * </ul>
 */
@Service
public class EventJournal {

	private static final Logger log = LoggerFactory.getLogger(EventJournal.class);

	private final RunEventRepository repository;

	private final JournalWriter writer;

	private final RunEventBroadcaster broadcaster;

	private final ObjectMapper objectMapper;

	private final HarnessMetrics metrics;

	/**
	 * Per-run sequence counters. A run is driven by a single thread, so this only has to be safe
	 * across runs; it is seeded from the database so a resumed or forked run continues its numbering.
	 */
	private final Map<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

	public EventJournal(RunEventRepository repository, JournalWriter writer,
			RunEventBroadcaster broadcaster, ObjectMapper objectMapper, HarnessMetrics metrics) {
		this.repository = repository;
		this.writer = writer;
		this.broadcaster = broadcaster;
		this.objectMapper = objectMapper;
		this.metrics = metrics;
	}

	/** Records an event and pushes it to live viewers. */
	public RunEventView append(UUID runId, int stepNo, RunEventType type, Object payload) {
		return append(runId, stepNo, type, payload, null);
	}

	/**
	 * Records an event and pushes it to live viewers.
	 *
	 * @param durationMs how long the described operation took, where that is meaningful
	 */
	public RunEventView append(UUID runId, int stepNo, RunEventType type, Object payload,
			Long durationMs) {
		long seq = nextSeq(runId);
		Instant now = Instant.now();
		JsonNode payloadNode = toNode(payload);

		if (type.isDurable()) {
			try {
				writer.write(new RunEventEntity(runId, seq, stepNo, type, now, durationMs,
						payloadNode == null ? null : payloadNode.toString()));
			}
			catch (RuntimeException ex) {
				// Deliberately swallowed: an unrecorded observation must not abort the run it observes.
				log.error("Failed to journal {} for run {} at seq {}", type, runId, seq, ex);
			}
		}

		RunEventView view = new RunEventView(runId, seq, stepNo, type, now, durationMs, payloadNode);
		broadcaster.broadcast(view);
		try {
			// Aggregates are derived from the record rather than instrumented alongside it, so the two
			// cannot disagree. Swallowed for the same reason journalling is: measuring the work must
			// never be able to stop the work.
			metrics.record(view);
		}
		catch (RuntimeException ex) {
			log.warn("Could not record metrics for {} on run {}", type, runId, ex);
		}
		return view;
	}

	/**
	 * Publishes a live-only event. Used for token-level deltas, which are far too voluminous to store
	 * for every run and are already summarised by the aggregate response event.
	 */
	public void publishEphemeral(UUID runId, int stepNo, RunEventType type, Object payload) {
		if (type.isDurable()) {
			throw new IllegalArgumentException(
					"%s is a durable event type; use append() so it is recorded".formatted(type));
		}
		// Marked NOT_JOURNALLED rather than given a sequence number. The sequence is the cursor a
		// client resumes from, so letting entries that are never stored consume numbers from it would
		// leave permanent holes and make "give me everything after seq N" unanswerable.
		broadcaster.broadcast(new RunEventView(runId, RunEventView.NOT_JOURNALLED, stepNo, type,
				Instant.now(), null, toNode(payload)));
	}

	/** Every recorded event for a run, in order. */
	@Transactional(readOnly = true)
	public List<RunEventView> eventsFor(UUID runId) {
		return repository.findByRunIdOrderBySeqAsc(runId).stream().map(this::toView).toList();
	}

	/** Recorded events after a position, for a client catching up before joining the live stream. */
	@Transactional(readOnly = true)
	public List<RunEventView> eventsAfter(UUID runId, long seq) {
		return repository.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, seq).stream()
				.map(this::toView).toList();
	}

	/** Releases the in-memory counter once a run can produce no more events. */
	public void forget(UUID runId) {
		sequences.remove(runId);
	}

	private long nextSeq(UUID runId) {
		return sequences.computeIfAbsent(runId, id -> new AtomicLong(repository.nextSeq(id)))
				.getAndIncrement();
	}

	private JsonNode toNode(Object payload) {
		if (payload == null) {
			return null;
		}
		try {
			return objectMapper.valueToTree(payload);
		}
		catch (RuntimeException ex) {
			log.warn("Could not serialise event payload of type {}", payload.getClass(), ex);
			return objectMapper.createObjectNode().put("serialisationError", ex.toString());
		}
	}

	private RunEventView toView(RunEventEntity entity) {
		JsonNode payload = null;
		if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
			try {
				payload = objectMapper.readTree(entity.getPayload());
			}
			catch (RuntimeException ex) {
				payload = objectMapper.createObjectNode().put("unparseablePayload", entity.getPayload());
			}
		}
		return new RunEventView(entity.getRunId(), entity.getSeq(), entity.getStepNo(), entity.getType(),
				entity.getOccurredAt(), entity.getDurationMs(), payload);
	}
}
