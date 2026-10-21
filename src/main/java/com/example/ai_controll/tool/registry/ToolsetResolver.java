package com.example.ai_controll.tool.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out which tools an agent may see.
 *
 * <p>Kept explicit rather than defaulting to "everything enabled". How many tools are in front of it
 * is one of the strongest influences on whether a small model picks the right one, so which tools a
 * run is offered should be a decision recorded with the run, not an accident of what happens to be
 * in the registry that day.
 */
@Service
public class ToolsetResolver {

	private static final Logger log = LoggerFactory.getLogger(ToolsetResolver.class);

	private final ToolRegistryService registry;

	public ToolsetResolver(ToolRegistryService registry) {
		this.registry = registry;
	}

	/**
	 * Resolves an explicit list of tool names to their current, enabled definitions.
	 *
	 * <p>A name that cannot be resolved is skipped with a warning rather than failing the run: the
	 * model is simply never shown it, and calling it then fails validation with the usual
	 * "no tool named ..." feedback, which is a better outcome than refusing to start.
	 */
	@Transactional(readOnly = true)
	public ResolvedToolset resolve(List<String> toolNames) {
		return resolve(toolNames, Map.of());
	}

	/**
	 * Resolves tool names, pinning any that the run named a version for.
	 *
	 * <p>Pinning is what makes the registry's version history usable as evidence. Every edit to a
	 * tool creates a version precisely so that a change in behaviour can be attributed to a change in
	 * wording -- but without this, a batch run today and the same batch run tomorrow would silently
	 * use whichever description happened to be current, and the attribution could never actually be
	 * made. A pinned version is offered even when it has since been disabled: the run asked for that
	 * exact text, and quietly substituting a different one would be the very thing this prevents.
	 *
	 * @param pinnedVersions tool name to version; names absent from it resolve to the current version
	 */
	@Transactional(readOnly = true)
	public ResolvedToolset resolve(List<String> toolNames, Map<String, Integer> pinnedVersions) {
		if (toolNames == null || toolNames.isEmpty()) {
			return ResolvedToolset.empty();
		}
		Map<String, Integer> pins = pinnedVersions == null ? Map.of() : pinnedVersions;

		List<ToolDefinitionEntity> definitions = new ArrayList<>();
		for (String name : toolNames) {
			Integer pinned = pins.get(name);
			if (pinned != null) {
				Optional<ToolDefinitionEntity> version = registry.findVersion(name, pinned);
				version.ifPresentOrElse(definitions::add, () -> log.warn(
						"Tool '{}' was pinned to version {}, which is not in the registry; it will not "
								+ "be offered to the model", name, pinned));
				continue;
			}
			registry.findCurrent(name).ifPresentOrElse(definition -> {
				if (definition.isEnabled()) {
					definitions.add(definition);
				}
				else {
					log.warn("Tool '{}' is disabled and will not be offered to the model", name);
				}
			}, () -> log.warn("Tool '{}' was requested but is not in the registry", name));
		}
		return ResolvedToolset.of(definitions, registry::toSchema);
	}

	/** Every currently enabled tool. Convenient for exploration, rarely what an experiment wants. */
	@Transactional(readOnly = true)
	public ResolvedToolset resolveAllEnabled() {
		List<ToolDefinitionEntity> enabled = registry.listCurrent().stream()
				.filter(ToolDefinitionEntity::isEnabled)
				.toList();
		return ResolvedToolset.of(enabled, registry::toSchema);
	}
}
