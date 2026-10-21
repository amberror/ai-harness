package com.example.ai_controll.tool.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolPolicyRepository extends JpaRepository<ToolPolicyEntity, UUID> {

	/**
	 * Enabled rules, most specific first: lower priority wins.
	 *
	 * <p>Name is a secondary sort so that two rules at the same priority are always resolved the same
	 * way. Without it the winner would depend on whatever order the database happened to return, and
	 * a run could behave differently from one execution to the next for no visible reason.
	 */
	List<ToolPolicyEntity> findByEnabledTrueOrderByPriorityAscNameAsc();

	List<ToolPolicyEntity> findAllByOrderByPriorityAscNameAsc();

	Optional<ToolPolicyEntity> findByName(String name);

	boolean existsByName(String name);
}
