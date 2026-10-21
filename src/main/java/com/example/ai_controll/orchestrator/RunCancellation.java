package com.example.ai_controll.orchestrator;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lets a person stop a run that is already going.
 *
 * <p>Without this the only way to end a run is to wait for a guardrail or to restart the
 * application, which for a batch of fifty repetitions started with the wrong variant means either
 * losing an hour or losing the results collected so far. A harness meant to drive real work needs a
 * stop button before it needs almost anything else.
 *
 * <p>Cancellation works in two ways at once, because either alone leaves a gap. A flag is checked at
 * every point in the loop where the run is about to commit to more work, which is what makes the
 * stop clean: the run ends in a named state with its transcript intact. The thread is also
 * interrupted, because a run blocked on a model call that takes minutes would otherwise not notice
 * the flag until it returned.
 *
 * <p>The flag is recorded even when no thread has claimed the run yet. Starting a run returns its id
 * before the executor picks it up, so a cancellation arriving in that window must not be lost.
 */
@Component
public class RunCancellation {

	private static final Logger log = LoggerFactory.getLogger(RunCancellation.class);

	private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

	private final Map<UUID, Thread> running = new ConcurrentHashMap<>();

	/** Claims the current thread as the one executing this run. */
	public void register(UUID runId) {
		this.running.put(runId, Thread.currentThread());
		if (this.cancelled.contains(runId)) {
			// Cancelled between being created and being picked up.
			Thread.currentThread().interrupt();
		}
	}

	/** Releases the run, whatever the outcome. Safe to call for a run that was never registered. */
	public void release(UUID runId) {
		this.running.remove(runId);
		this.cancelled.remove(runId);
	}

	/**
	 * Asks a run to stop.
	 *
	 * @return true if the run was executing here; false when nothing is running under that id, which
	 *         is still recorded in case the run is about to start
	 */
	public boolean cancel(UUID runId) {
		this.cancelled.add(runId);
		Thread thread = this.running.get(runId);
		if (thread == null) {
			log.info("Run {} marked for cancellation; no thread is executing it here", runId);
			return false;
		}
		log.info("Cancelling run {}", runId);
		thread.interrupt();
		return true;
	}

	public boolean isCancelled(UUID runId) {
		return this.cancelled.contains(runId);
	}

	/**
	 * Whether this thread should stop, for either reason.
	 *
	 * <p>An interrupt is treated as a cancellation even without the flag, so a run interrupted by a
	 * shutdown ends in a named state rather than as an unexplained failure.
	 */
	public boolean shouldStop(UUID runId) {
		return isCancelled(runId) || Thread.currentThread().isInterrupted();
	}
}
