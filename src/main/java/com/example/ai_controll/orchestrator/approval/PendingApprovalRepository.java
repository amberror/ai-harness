package com.example.ai_controll.orchestrator.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingApprovalRepository extends JpaRepository<PendingApprovalEntity, UUID> {

	/** The call a run is currently waiting on, if any. */
	Optional<PendingApprovalEntity> findFirstByRunIdAndDecisionIsNullOrderByRequestedAtAsc(UUID runId);

	/** Everything currently waiting, across all runs; the console's inbox. */
	List<PendingApprovalEntity> findByDecisionIsNullOrderByRequestedAtAsc();

	List<PendingApprovalEntity> findByRunIdOrderByRequestedAtAsc(UUID runId);

	/** Removes a run's approval history, for retention. */
	void deleteByRunId(UUID runId);
}
