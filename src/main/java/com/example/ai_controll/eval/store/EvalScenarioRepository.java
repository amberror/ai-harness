package com.example.ai_controll.eval.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Top-level, not nested inside a holder class: Spring Data only scans top-level interfaces, so a
 * tidy-looking grouping produces no beans at all and fails at context startup.
 */
public interface EvalScenarioRepository extends JpaRepository<EvalScenarioEntity, UUID> {

	Optional<EvalScenarioEntity> findByName(String name);

	List<EvalScenarioEntity> findAllByOrderByNameAsc();

	boolean existsByName(String name);
}
