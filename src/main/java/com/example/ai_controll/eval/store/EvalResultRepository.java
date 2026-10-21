package com.example.ai_controll.eval.store;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalResultRepository extends JpaRepository<EvalResultEntity, UUID> {

	List<EvalResultEntity> findByBatchIdOrderByRepetitionAsc(UUID batchId);

	/**
	 * Whether any evaluation cites this run. Retention checks it before deleting: a pass rate whose
	 * runs have been pruned is a number with nothing underneath it.
	 */
	boolean existsByRunId(UUID runId);
}
