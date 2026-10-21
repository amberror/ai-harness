package com.example.ai_controll.orchestrator.store;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunMessageRepository extends JpaRepository<RunMessageEntity, UUID> {

	List<RunMessageEntity> findByRunIdOrderBySeqAsc(UUID runId);

	/** Removes a run's transcript, for retention. */
	void deleteByRunId(UUID runId);
}
