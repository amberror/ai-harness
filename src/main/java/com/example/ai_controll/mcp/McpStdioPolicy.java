package com.example.ai_controll.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;

/**
 * Decides whether an MCP server may be launched as a child process.
 *
 * <p>The same reasoning as the HTTP egress allowlist, applied to a sharper edge. An MCP server
 * configuration is runtime-editable data, and launching a stdio server means <em>running a
 * command</em>. Without this, an API whose job is managing tools would double as an API for
 * executing arbitrary programs, which is a much larger capability than anyone asking for a tool
 * registry intends to grant.
 *
 * <p>So the executable must be named in {@code harness.mcp.stdio.allowed-commands}. Matching is on
 * the command name or its full path, and a name matches however it was spelled -- {@code npx},
 * {@code npx.cmd} and {@code C:\...\npx.cmd} all satisfy an entry of {@code npx} -- because the
 * decision being expressed is "this program may run", not "this exact string may run".
 *
 * <p>Arguments are deliberately <em>not</em> checked. Constraining them convincingly is not possible
 * (an allowed interpreter will run whatever script it is handed), so pretending otherwise would
 * offer false assurance. The allowlist is a decision about which programs you trust, and it should
 * be read that way.
 */
@Component
public class McpStdioPolicy {

	private final HarnessProperties.Stdio config;

	public McpStdioPolicy(HarnessProperties properties) {
		this.config = properties.mcp().stdio();
	}

	/** Checks whether a command may be launched. */
	public Decision check(String command) {
		if (command == null || command.isBlank()) {
			return Decision.refuse("The server configuration has no command to run");
		}

		List<String> allowed = config.allowedCommands();
		if (allowed.isEmpty()) {
			return Decision.refuse(
					"No commands are allowed for stdio MCP servers. Add one to "
							+ "harness.mcp.stdio.allowed-commands to permit launching them.");
		}

		String candidate = normalise(command);
		boolean permitted = allowed.stream().anyMatch(entry -> matches(entry, command, candidate));
		if (!permitted) {
			return Decision.refuse(
					"Command '%s' is not allowed. Allowed: %s".formatted(command, allowed));
		}
		return Decision.permit();
	}

	private static boolean matches(String entry, String rawCommand, String normalisedCommand) {
		String allowed = entry.strip();
		if (allowed.isEmpty()) {
			return false;
		}
		// A full path is compared as given, so an entry naming one specific binary means that binary.
		if (allowed.equalsIgnoreCase(rawCommand)) {
			return true;
		}
		return normalise(allowed).equals(normalisedCommand);
	}

	/**
	 * Reduces a command to its bare executable name: strips any directory, and the Windows
	 * extensions that make the same program look like three different ones.
	 */
	private static String normalise(String command) {
		String name;
		try {
			Path path = Path.of(command);
			name = path.getFileName() == null ? command : path.getFileName().toString();
		}
		catch (RuntimeException ex) {
			name = command;
		}
		name = name.toLowerCase(Locale.ROOT);
		for (String extension : List.of(".exe", ".cmd", ".bat", ".com", ".ps1")) {
			if (name.endsWith(extension)) {
				return name.substring(0, name.length() - extension.length());
			}
		}
		return name;
	}

	/**
	 * @param allowed whether the launch may proceed
	 * @param reason  why not, when it may not
	 */
	public record Decision(boolean allowed, String reason) {

		static Decision permit() {
			return new Decision(true, null);
		}

		static Decision refuse(String reason) {
			return new Decision(false, reason);
		}
	}
}
