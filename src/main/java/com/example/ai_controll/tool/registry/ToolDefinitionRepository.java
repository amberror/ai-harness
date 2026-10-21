package com.example.ai_controll.tool.registry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolDefinitionRepository extends JpaRepository<ToolDefinitionEntity, UUID> {

	/** The version currently in force for a name. */
	Optional<ToolDefinitionEntity> findByCurrentOf(String name);

	/** Every current tool, whatever its enabled state. */
	List<ToolDefinitionEntity> findByCurrentOfNotNullOrderByNameAsc();

	/** All versions of one tool, newest first, for inspecting how a schema evolved. */
	List<ToolDefinitionEntity> findByNameOrderByVersionDesc(String name);

	Optional<ToolDefinitionEntity> findByNameAndVersion(String name, int version);

	@Query("select coalesce(max(t.version), 0) from ToolDefinitionEntity t where t.name = :name")
	int highestVersion(@Param("name") String name);

	boolean existsByName(String name);
}
