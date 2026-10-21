package com.example.ai_controll.tool.exec.builtin;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.FileAccessPolicy;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a text file from a directory that has been explicitly allowed.
 *
 * <p>Unlike everything else in the built-in set, this one is a real capability rather than a
 * calculation, and it ships <strong>inert</strong>: with no directory configured it refuses every
 * call and says so. That is the same posture the HTTP allowlist and the stdio command list take, for
 * the same reason — the model chooses the argument, so an ungated version of this reads whatever
 * this process can see.
 *
 * <p>Turning it on is a decision, taken in configuration and not through the API:
 *
 * <pre>
 * harness:
 *   tools:
 *     files:
 *       allowed-directories: ["C:/work/notes"]
 * </pre>
 *
 * <p>The output is truncated with an explicit marker rather than silently, and the file's real size
 * is always reported, so a model acting on a partial read can tell that it has one.
 */
@Component
public class FileReadTool implements BuiltinTool {

	private final FileAccessPolicy policy;

	private final ObjectMapper objectMapper;

	public FileReadTool(FileAccessPolicy policy, ObjectMapper objectMapper) {
		this.policy = policy;
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "file_read";
	}

	@Override
	public String defaultDescription() {
		return "Reads a text file from disk and returns its contents. Only files inside directories "
				+ "the operator has allowed can be read; everything else is refused.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "path":{"type":"string","description":"Path to the file to read"},
				   "maxChars":{"type":"integer","minimum":1,
				     "description":"How much of the file to return; the rest is truncated"}},
				 "required":["path"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> path = Args.text(arguments, "path");
		if (path.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments", Args.missing("path", "a file path"), 0L);
		}

		FileAccessPolicy.Decision decision = policy.check(path.get());
		if (!decision.allowed()) {
			// Reported to the model as an ordinary refusal so it can take another route, exactly as a
			// denied HTTP host is.
			return ToolOutcome.failure("access_denied", decision.reason(), 0L);
		}

		Path file = decision.path();
		try {
			if (Files.isDirectory(file)) {
				return ToolOutcome.failure("not_a_file",
						"'%s' is a directory, not a file".formatted(path.get()), 0L);
			}
			long size = Files.size(file);
			if (size > policy.maxBytes()) {
				return ToolOutcome.failure("file_too_large",
						"'%s' is %d bytes; the limit is %d. Read a smaller file."
								.formatted(path.get(), size, policy.maxBytes()),
						0L);
			}

			String content = Files.readString(file, StandardCharsets.UTF_8);
			int limit = Args.integer(arguments, "maxChars").filter(value -> value > 0)
					.orElse(Integer.MAX_VALUE);

			ObjectNode result = objectMapper.createObjectNode();
			result.put("path", file.toString());
			result.put("bytes", size);
			result.put("characters", content.length());
			if (content.length() > limit) {
				result.put("content", content.substring(0, limit));
				// Explicit, never silent: a model that cannot tell it received part of a file will
				// answer confidently from the part it got.
				result.put("truncated", true);
				result.put("truncatedAtChars", limit);
			}
			else {
				result.put("content", content);
				result.put("truncated", false);
			}
			return ToolOutcome.success(result.toString(), 0L);
		}
		catch (MalformedInputException ex) {
			return ToolOutcome.failure("not_text",
					"'%s' is not UTF-8 text; this tool only reads text files".formatted(path.get()), 0L);
		}
		catch (IOException ex) {
			return ToolOutcome.failure("read_failed",
					"Could not read '%s': %s".formatted(path.get(), ex.getMessage()), 0L);
		}
	}
}
