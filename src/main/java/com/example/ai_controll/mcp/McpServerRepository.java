package com.example.ai_controll.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServerEntity, UUID> {

	Optional<McpServerEntity> findByLabel(String label);

	List<McpServerEntity> findAllByOrderByLabelAsc();

	List<McpServerEntity> findByEnabledTrue();

	boolean existsByLabel(String label);
}
