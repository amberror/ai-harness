package com.example.ai_controll.orchestrator.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepository extends JpaRepository<RunEntity, UUID> {

	List<RunEntity> findAllByOrderByStartedAtDesc(Limit limit);

	/**
	 * Runs that have finished, oldest first, for retention. A null end time means the run is still
	 * going or is parked for approval, and either way it is not stale.
	 */
	List<RunEntity> findByEndedAtBefore(Instant cutoff);
}
