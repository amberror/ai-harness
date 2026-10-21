package com.example.ai_controll.tool.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;

/**
 * Decides which files a tool is allowed to read.
 *
 * <p>The same reasoning as the HTTP allowlist and the stdio command list, applied to the filesystem.
 * The model supplies the path, so a file-reading tool without this is a "read any file this process
 * can see" primitive — private keys, browser profiles, the harness's own database. On a developer
 * machine that is everything.
 *
 * <p>Two rules, and the second is the one that actually does the work:
 *
 * <ul>
 *   <li><strong>Deny by default.</strong> No directory is readable until one is named in
 *       {@code harness.tools.files.allowed-directories}, so the tool ships inert.</li>
 *   <li><strong>Compare real paths, not written ones.</strong> The path is resolved to its canonical
 *       form first, with symbolic links followed. Checking the string as given would be defeated by
 *       {@code /allowed/../../etc/passwd}, and checking the resolved path without following links
 *       would be defeated by a symlink inside an allowed directory pointing anywhere at all.</li>
 * </ul>
 *
 * <p>A refusal is an ordinary outcome reported back to the model, never an exception out of the run.
 */
@Component
public class FileAccessPolicy {

	private final HarnessProperties.Files config;

	public FileAccessPolicy(HarnessProperties properties) {
		this.config = properties.tools().files();
	}

	/**
	 * Checks one path.
	 *
	 * @return the resolved path when allowed, or the reason it was refused
	 */
	public Decision check(String path) {
		List<String> allowed = this.config.allowedDirectories();
		if (allowed.isEmpty()) {
			return Decision.refuse("No directories are readable. Add one to "
					+ "harness.tools.files.allowed-directories to permit reading files.");
		}
		if (path == null || path.isBlank()) {
			return Decision.refuse("No path was given");
		}

		Path candidate;
		try {
			candidate = Path.of(path);
		}
		catch (RuntimeException ex) {
			return Decision.refuse("'%s' is not a usable path".formatted(path));
		}

		Path resolved;
		try {
			// toRealPath follows symbolic links and normalises '..'; it also fails for a file that is
			// not there, which is the right moment to find that out.
			resolved = candidate.toRealPath();
		}
		catch (IOException ex) {
			return Decision.refuse("'%s' does not exist or cannot be read".formatted(path));
		}

		for (String entry : allowed) {
			if (entry == null || entry.isBlank()) {
				continue;
			}
			try {
				Path root = Path.of(entry).toRealPath();
				// startsWith on Path compares whole path elements, so /data-private does not match an
				// allowlisted /data the way a string prefix comparison would.
				if (resolved.startsWith(root)) {
					return Decision.permit(resolved);
				}
			}
			catch (IOException | RuntimeException ex) {
				// A configured directory that does not exist allows nothing, rather than everything.
				continue;
			}
		}

		return Decision.refuse(
				"'%s' is outside every allowed directory. Allowed: %s".formatted(path, allowed));
	}

	/** Whether any directory is readable at all, so a tool can say so before it is called. */
	public boolean isEnabled() {
		return !this.config.allowedDirectories().isEmpty();
	}

	public long maxBytes() {
		return this.config.maxBytes();
	}

	/**
	 * @param path   the resolved, real path, when the read is allowed
	 * @param reason why it was refused, when it was not
	 */
	public record Decision(boolean allowed, Path path, String reason) {

		static Decision permit(Path path) {
			return new Decision(true, path, null);
		}

		static Decision refuse(String reason) {
			return new Decision(false, null, reason);
		}
	}
}
