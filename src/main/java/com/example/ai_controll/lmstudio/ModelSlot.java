package com.example.ai_controll.lmstudio;

import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;

/**
 * Limits how many generation requests reach the model server at once.
 *
 * <p>LM Studio serves a single model instance. Two runs started together do not run in parallel:
 * the second queues inside the server, and its recorded latency then contains the first run's
 * generation. That corrupts exactly the figures the harness exists to collect -- and worse, it makes
 * the wall-clock budget fire on time the run spent waiting rather than time it spent working, so a
 * run gets aborted for something another run did.
 *
 * <p>The gate is held only around the model call, not for the whole run. Holding it for a run would
 * serialise everything including tool execution, and a run parked for approval would have to give it
 * up anyway. Time spent waiting here is measured and handed back to the budget tracker, so a queued
 * run is delayed but not penalised.
 */
@Component
public class ModelSlot {

	private final Semaphore permits;

	private final int size;

	public ModelSlot(HarnessProperties properties) {
		this.size = Math.max(1, properties.lmStudio().maxConcurrentRequests());
		// Fair, so a run that has been waiting is not overtaken indefinitely by fresh arrivals. The
		// cost of fairness is irrelevant next to the seconds a generation takes.
		this.permits = new Semaphore(this.size, true);
	}

	/**
	 * Waits for the model to be free.
	 *
	 * <p>Interruptible on purpose: cancelling a run has to be able to reach a run that is only queued,
	 * not yet generating. Without that, "stop" would take as long as everything ahead of it.
	 *
	 * @return how long the wait was, in milliseconds, for the budget tracker to discount
	 * @throws InterruptedException when the run was cancelled while queued
	 */
	public long acquire() throws InterruptedException {
		long startedAt = System.nanoTime();
		this.permits.acquire();
		return (System.nanoTime() - startedAt) / 1_000_000L;
	}

	public void release() {
		this.permits.release();
	}

	/** How many requests may be in flight; shown in the console so the setting is not invisible. */
	public int size() {
		return this.size;
	}

	/** How many runs are waiting for the model right now. */
	public int queueLength() {
		return this.permits.getQueueLength();
	}
}
