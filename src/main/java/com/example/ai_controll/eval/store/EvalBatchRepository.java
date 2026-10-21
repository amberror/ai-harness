package com.example.ai_controll.eval.store;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalBatchRepository extends JpaRepository<EvalBatchEntity, UUID> {

	List<EvalBatchEntity> findAllByOrderByStartedAtDesc(Limit limit);

	/** Every batch for one scenario, newest first: the comparison between variants. */
	List<EvalBatchEntity> findByScenarioIdOrderByStartedAtDesc(UUID scenarioId);
}
