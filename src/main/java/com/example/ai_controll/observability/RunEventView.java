package com.example.ai_controll.observability;

import java.time.Instant;
import java.util.UUID;

import com.example.ai_controll.orchestrator.domain.RunEventType;

import tools.jackson.databind.JsonNode;

/**
 * A journal entry as seen by the API, the console and evaluation assertions.
 *
 * @param runId      the run this belongs to
 * @param seq        position in the run's journal: monotonic, gap-free, and the cursor a live client
 *                   resumes from. {@link #NOT_JOURNALLED} for live-only entries, which have no
 *                   position because they were never stored
 * @param stepNo     which loop iteration produced it
 * @param type       what happened
 * @param occurredAt when it happened
 * @param durationMs how long the described operation took, where that is meaningful
 * @param payload    type-specific detail, including exact request and response bodies
 */
public record RunEventView(UUID runId, long seq, int stepNo, RunEventType type, Instant occurredAt,
		Long durationMs, JsonNode payload) {

	/** Sequence value for entries that exist only on the live stream and were never stored. */
	public static final long NOT_JOURNALLED = -1L;

	/** Whether this entry was written to the journal or only streamed to live viewers. */
	public boolean isDurable() {
		return type.isDurable();
	}

	/** Whether this entry has a journal position a client could resume from. */
	public boolean isResumable() {
		return seq != NOT_JOURNALLED;
	}
}
