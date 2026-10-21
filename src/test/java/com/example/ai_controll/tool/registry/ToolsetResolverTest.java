package com.example.ai_controll.tool.registry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.tool.schema.ToolSchema;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which tools a run is offered, and at which version.
 *
 * <p>Version pinning is the piece that makes the registry's history usable as evidence. Every edit
 * to a tool creates a version so that a change in behaviour can be attributed to a change in
 * wording; without pinning, the same batch run a week apart would silently use whichever description
 * happened to be current, and that attribution could never actually be made.
 */
class ToolsetResolverTest {

	private static final String CALC = "calc_eval";

	private final JsonMapper mapper = JsonMapper.builder().build();

	private ToolRegistryService registry;

	private ToolsetResolver resolver;

	private ToolDefinitionEntity definition(int version, String description, boolean enabled) {
		return new ToolDefinitionEntity(UUID.randomUUID(), CALC, version, version == 2, description,
				"{\"type\":\"object\"}", ExecutorType.BUILTIN, null, enabled, ToolSource.BUILTIN, null,
				Instant.now(), "test");
	}

	@BeforeEach
	void setUp() {
		this.registry = mock(ToolRegistryService.class);
		when(registry.toSchema(any())).thenAnswer(invocation -> {
			ToolDefinitionEntity entity = invocation.getArgument(0);
			return new ToolSchema(entity.getName(), entity.getDescription(),
					mapper.readTree(entity.getParamsSchema()));
		});
		this.resolver = new ToolsetResolver(registry);
	}

	@Test
	@DisplayName("without a pin, the current version is used")
	void resolvesCurrentByDefault() {
		when(registry.findCurrent(CALC)).thenReturn(Optional.of(definition(2, "current wording", true)));

		ResolvedToolset toolset = resolver.resolve(List.of(CALC));

		assertThat(toolset.schemas()).singleElement()
				.extracting(ToolSchema::description).isEqualTo("current wording");
	}

	@Test
	@DisplayName("a pinned version is offered instead of the current one")
	void honoursAPin() {
		when(registry.findVersion(CALC, 1)).thenReturn(Optional.of(definition(1, "old wording", true)));

		ResolvedToolset toolset = resolver.resolve(List.of(CALC), Map.of(CALC, 1));

		// The whole point: "the same scenario with the previous tool description" has to be
		// expressible, or the version history is a museum rather than an instrument.
		assertThat(toolset.schemas()).singleElement()
				.extracting(ToolSchema::description).isEqualTo("old wording");
	}

	@Test
	@DisplayName("a pinned version is offered even if that version has since been disabled")
	void pinBeatsTheEnabledFlag() {
		when(registry.findVersion(CALC, 1)).thenReturn(Optional.of(definition(1, "old wording", false)));

		ResolvedToolset toolset = resolver.resolve(List.of(CALC), Map.of(CALC, 1));

		// The run asked for that exact text. Substituting a different one is precisely the silent
		// change pinning exists to prevent, and the alternative -- refusing -- would make an old
		// experiment unrepeatable the moment a tool was retired.
		assertThat(toolset.names()).containsExactly(CALC);
	}

	@Test
	@DisplayName("a pin to a version that does not exist offers nothing rather than guessing")
	void refusesAMissingVersion() {
		when(registry.findVersion(CALC, 99)).thenReturn(Optional.empty());

		ResolvedToolset toolset = resolver.resolve(List.of(CALC), Map.of(CALC, 99));

		// Falling back to the current version would produce a run that quietly measured something
		// other than what was asked for, which is worse than a run with no tool: that at least fails
		// visibly, with the usual "no tool named ..." feedback.
		assertThat(toolset.isEmpty()).isTrue();
	}

	@Test
	@DisplayName("a disabled current tool is not offered")
	void skipsDisabledTools() {
		when(registry.findCurrent(CALC)).thenReturn(Optional.of(definition(2, "current", false)));

		assertThat(resolver.resolve(List.of(CALC)).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("an unknown tool is skipped rather than failing the run")
	void skipsUnknownTools() {
		when(registry.findCurrent("nope")).thenReturn(Optional.empty());

		// The model is simply never shown it; calling it then fails validation with feedback it can
		// act on, which is a better outcome than refusing to start at all.
		assertThat(resolver.resolve(List.of("nope")).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("an empty tool list resolves to no tools")
	void handlesNoTools() {
		assertThat(resolver.resolve(List.of()).isEmpty()).isTrue();
		assertThat(resolver.resolve(null).isEmpty()).isTrue();
	}
}
