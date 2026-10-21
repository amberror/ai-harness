package com.example.ai_controll.tool.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.builtin.BuiltinTool;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers each compiled built-in tool the first time it is seen.
 *
 * <p>Seeded once and then left alone. A built-in's description and schema are exactly the things you
 * want to edit while experimenting -- rewording a description to see whether the model then picks
 * the right tool is one of the most useful things this harness can do -- and re-seeding on every
 * start would silently discard those edits.
 *
 * <p>A tool that has been retired stays retired for the same reason: its absence was a decision.
 */
@Component
public class BuiltinToolSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BuiltinToolSeeder.class);

	private final java.util.List<BuiltinTool> builtins;

	private final ToolRegistryService registry;

	private final ObjectMapper objectMapper;

	public BuiltinToolSeeder(java.util.List<BuiltinTool> builtins, ToolRegistryService registry,
			ObjectMapper objectMapper) {
		this.builtins = builtins;
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(ApplicationArguments args) {
		for (BuiltinTool builtin : builtins) {
			// existsByName, not "is current": a retired tool has a history, and re-creating it would
			// override a deliberate choice to stop offering it.
			if (registry.exists(builtin.name())) {
				continue;
			}
			registry.save(new ToolRegistryService.ToolDefinition(builtin.name(),
					builtin.defaultDescription(), objectMapper.readTree(builtin.defaultSchema()),
					ExecutorType.BUILTIN, null, true, ToolSource.BUILTIN, null),
					"seeded from the compiled built-in");
			log.info("Registered built-in tool '{}'", builtin.name());
		}
	}
}
