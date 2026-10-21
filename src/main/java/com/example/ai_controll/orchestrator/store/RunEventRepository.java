package com.example.ai_controll.orchestrator.store;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.example.ai_controll.orchestrator.domain.RunEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunEventRepository extends JpaRepository<RunEventEntity, Long> {

	List<RunEventEntity> findByRunIdOrderBySeqAsc(UUID runId);

	/** Removes a run's whole journal, for retention. */
	void deleteByRunId(UUID runId);

	/**
	 * Recent events of given types, across every run.
	 *
	 * <p>The journal is per-run everywhere else, because explaining one run is what it is for. This is
	 * the one question that cuts the other way: how a tool behaves is a property of many runs, and the
	 * evidence for it -- which field the model omits, how often a call is rejected -- was already being
	 * recorded and simply never read back.
	 */
	@Query("select e from RunEventEntity e where e.type in :types order by e.occurredAt desc")
	List<RunEventEntity> findRecentByTypes(@Param("types") Collection<RunEventType> types,
			org.springframework.data.domain.Limit limit);

	/** Events after a given position, for a client resuming a live stream. */
	List<RunEventEntity> findByRunIdAndSeqGreaterThanOrderBySeqAsc(UUID runId, long seq);

	/**
	 * The next sequence number for a run. Computed in the database rather than kept in memory so
	 * that a resumed or forked run continues the numbering correctly.
	 */
	@Query("select coalesce(max(e.seq), -1) + 1 from RunEventEntity e where e.runId = :runId")
	long nextSeq(@Param("runId") UUID runId);
}
