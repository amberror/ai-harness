package com.example.ai_controll.mcp;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate on launching MCP servers as child processes.
 *
 * <p>This is the sharpest edge in the harness. An MCP server configuration is runtime-editable data,
 * and launching a stdio server means running a program, so without this an API for managing tools
 * would also be an API for executing arbitrary commands.
 */
class McpStdioPolicyTest {

	private McpStdioPolicy policy(List<String> allowedCommands) {
		return new McpStdioPolicy(TestProperties.builder().allowedCommands(allowedCommands).build());
	}

	@Test
	@DisplayName("nothing may be launched until a command is named")
	void deniesByDefault() {
		McpStdioPolicy.Decision decision = policy(List.of()).check("npx");

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).contains("allowed-commands");
	}

	@Test
	@DisplayName("an allowlisted command is permitted")
	void allowsListedCommand() {
		assertThat(policy(List.of("npx")).check("npx").allowed()).isTrue();
	}

	@Test
	@DisplayName("a command that is not listed is refused, and the reason says which are")
	void refusesUnlistedCommand() {
		McpStdioPolicy.Decision decision = policy(List.of("npx")).check("bash");

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).contains("bash").contains("npx");
	}

	@Test
	@DisplayName("a full path to an allowlisted program is permitted")
	void allowsFullPathToListedProgram() {
		// The decision being expressed is "this program may run", so the spelling used to reach it
		// should not change the answer.
		assertThat(policy(List.of("npx")).check("C:\\Program Files\\nodejs\\npx.cmd").allowed()).isTrue();
		assertThat(policy(List.of("python")).check("/usr/bin/python").allowed()).isTrue();
	}

	@Test
	@DisplayName("Windows executable extensions do not create a loophole")
	void ignoresWindowsExtensions() {
		// npx, npx.cmd and npx.exe are the same program; treating them as three different strings
		// would mean an allowlist that silently fails on Windows.
		McpStdioPolicy allowsNpx = policy(List.of("npx"));

		assertThat(allowsNpx.check("npx.cmd").allowed()).isTrue();
		assertThat(allowsNpx.check("npx.exe").allowed()).isTrue();
		assertThat(allowsNpx.check("NPX.CMD").allowed()).isTrue();
	}

	@Test
	@DisplayName("a different program in an allowed directory is still refused")
	void refusesOtherProgramsOnThePath() {
		// The allowlist names programs, not directories. Allowing npx must not allow everything that
		// happens to sit beside it.
		assertThat(policy(List.of("npx")).check("C:\\Program Files\\nodejs\\node.exe").allowed())
				.isFalse();
	}

	@Test
	@DisplayName("an entry naming a full path means that specific binary")
	void allowsSpecificBinaryByPath() {
		McpStdioPolicy pinned = policy(List.of("/opt/tools/my-mcp-server"));

		assertThat(pinned.check("/opt/tools/my-mcp-server").allowed()).isTrue();
		// The bare name also matches, since it is the same program by name.
		assertThat(pinned.check("my-mcp-server").allowed()).isTrue();
		assertThat(pinned.check("/opt/tools/something-else").allowed()).isFalse();
	}

	@Test
	@DisplayName("a missing command is refused rather than launched")
	void refusesEmptyCommand() {
		assertThat(policy(List.of("npx")).check(null).allowed()).isFalse();
		assertThat(policy(List.of("npx")).check("").allowed()).isFalse();
		assertThat(policy(List.of("npx")).check("   ").allowed()).isFalse();
	}

	@Test
	@DisplayName("a blank allowlist entry does not match everything")
	void ignoresBlankEntries() {
		// A stray empty string in configuration must not silently become a wildcard.
		assertThat(policy(List.of("", "  ")).check("bash").allowed()).isFalse();
	}
}
