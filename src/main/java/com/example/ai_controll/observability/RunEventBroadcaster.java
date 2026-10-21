package com.example.ai_controll.observability;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans journal entries out to live subscribers over Server-Sent Events.
 *
 * <p>Kept separate from persistence so that a slow or dead browser can never affect a run. A failed
 * push removes the subscriber and is otherwise ignored: watching a run must not be able to break it.
 */
@Component
public class RunEventBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(RunEventBroadcaster.class);

	/** No timeout: a run driven by a small local model can legitimately take many minutes. */
	private static final long NO_TIMEOUT = 0L;

	private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

	/** Registers a live subscriber for a run. */
	public SseEmitter subscribe(UUID runId) {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		List<SseEmitter> forRun = subscribers.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
		forRun.add(emitter);

		emitter.onCompletion(() -> remove(runId, emitter));
		emitter.onTimeout(() -> remove(runId, emitter));
		emitter.onError(ex -> remove(runId, emitter));

		return emitter;
	}

	/** Pushes an entry to everyone currently watching the run. */
	public void broadcast(RunEventView event) {
		List<SseEmitter> forRun = subscribers.get(event.runId());
		if (forRun == null || forRun.isEmpty()) {
			return;
		}
		for (SseEmitter emitter : forRun) {
			try {
				SseEmitter.SseEventBuilder builder = SseEmitter.event()
						.name(event.type().name())
						.data(event);
				// Only journalled entries carry an id. The browser echoes the last id back as
				// Last-Event-ID when reconnecting, so advertising an id for something that was never
				// stored would send the client back to a position it could not be caught up from.
				if (event.isResumable()) {
					builder.id(Long.toString(event.seq()));
				}
				emitter.send(builder);
			}
			catch (IOException | IllegalStateException ex) {
				// A disconnected browser is entirely ordinary; drop it and carry on.
				log.debug("Dropping subscriber for run {}: {}", event.runId(), ex.toString());
				remove(event.runId(), emitter);
			}
		}
	}

	/** Signals that no further entries will arrive, so clients can stop listening. */
	public void closeRun(UUID runId) {
		List<SseEmitter> forRun = subscribers.remove(runId);
		if (forRun == null) {
			return;
		}
		for (SseEmitter emitter : forRun) {
			try {
				emitter.complete();
			}
			catch (RuntimeException ex) {
				log.debug("Error completing emitter for run {}", runId, ex);
			}
		}
	}

	/** Number of live subscribers, for diagnostics. */
	public int subscriberCount(UUID runId) {
		List<SseEmitter> forRun = subscribers.get(runId);
		return forRun == null ? 0 : forRun.size();
	}

	private void remove(UUID runId, SseEmitter emitter) {
		List<SseEmitter> forRun = subscribers.get(runId);
		if (forRun != null) {
			forRun.remove(emitter);
			if (forRun.isEmpty()) {
				subscribers.remove(runId, forRun);
			}
		}
	}
}
