package com.example.ai_controll.config;

import java.time.Duration;
import java.util.List;

/**
 * Builds {@link HarnessProperties} for tests.
 *
 * <p>This exists because the record grew a new section three times, and each time every test that
 * constructed one directly stopped compiling. That is churn with no diagnostic value: none of those
 * tests care about journal caps or MCP timeouts, they just need a valid object. Naming the defaults
 * once means adding a section touches this file and nothing else.
 *
 * <p>The defaults deliberately mirror production's <em>restrictive</em> posture — empty allowlists
 * for both HTTP egress and stdio commands — so a test that needs permission has to say so, and a
 * test that accidentally reaches the network fails rather than succeeding quietly.
 */
public final class TestProperties {

	private TestProperties() {
	}

	/** Sensible defaults for a test that does not care about configuration. */
	public static HarnessProperties defaults() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String baseUrl = "http://localhost:1234";

		private String defaultModel = "test-model";

		private com.example.ai_controll.tool.exec.EgressMode egressMode =
				com.example.ai_controll.tool.exec.EgressMode.ALLOWLIST;

		private List<String> allowedHosts = List.of();

		private List<String> blockedHosts = List.of();

		private boolean allowPrivateAddresses;

		private List<String> allowedCommands = List.of();

		private List<String> allowedDirectories = List.of();

		private int maxRawBodyChars = 32768;

		private int contextLength;

		private int maxConcurrentRequests = 1;

		private int maxRetries;

		private Duration retryBackoff = Duration.ofMillis(1);

		public Builder baseUrl(String value) {
			this.baseUrl = value;
			return this;
		}

		public Builder defaultModel(String value) {
			this.defaultModel = value;
			return this;
		}

		public Builder allowedHosts(List<String> value) {
			this.allowedHosts = value;
			return this;
		}

		public Builder blockedHosts(List<String> value) {
			this.blockedHosts = value;
			return this;
		}

		/** ALLOWLIST by default here as in production: a test must ask to reach anything. */
		public Builder egressMode(com.example.ai_controll.tool.exec.EgressMode value) {
			this.egressMode = value;
			return this;
		}

		public Builder allowPrivateAddresses(boolean value) {
			this.allowPrivateAddresses = value;
			return this;
		}

		public Builder allowedCommands(List<String> value) {
			this.allowedCommands = value;
			return this;
		}

		/** Directories a file-reading tool may see. Empty by default, as in production. */
		public Builder allowedDirectories(List<String> value) {
			this.allowedDirectories = value;
			return this;
		}

		public Builder maxRawBodyChars(int value) {
			this.maxRawBodyChars = value;
			return this;
		}

		/** A fixed context window, for tests about compaction rather than about the server. */
		public Builder contextLength(int value) {
			this.contextLength = value;
			return this;
		}

		public Builder maxConcurrentRequests(int value) {
			this.maxConcurrentRequests = value;
			return this;
		}

		/**
		 * Retries are off by default here, unlike in production. A test asserting that a failure is
		 * reported should not first have to sit through the recovery attempts.
		 */
		public Builder maxRetries(int value) {
			this.maxRetries = value;
			return this;
		}

		public Builder retryBackoff(Duration value) {
			this.retryBackoff = value;
			return this;
		}

		public HarnessProperties build() {
			return new HarnessProperties(
					new HarnessProperties.LmStudio(baseUrl, "", Duration.ofSeconds(1),
							Duration.ofSeconds(5), defaultModel, maxConcurrentRequests, maxRetries,
							retryBackoff),
					new HarnessProperties.Journal(maxRawBodyChars, 0),
					new HarnessProperties.Tools(
							new HarnessProperties.HttpTools(egressMode, allowedHosts, blockedHosts,
									allowPrivateAddresses, 1048576),
							new HarnessProperties.Files(allowedDirectories, 1048576),
							new HarnessProperties.Learning(2000, 4096, ""),
							Duration.ofSeconds(30), 4000),
					new HarnessProperties.Mcp(new HarnessProperties.Stdio(allowedCommands),
							Duration.ofSeconds(60), Duration.ofSeconds(30)),
					new HarnessProperties.Eval(new HarnessProperties.Judge(4096, "")),
					new HarnessProperties.Context(contextLength, 128, 2, 240));
		}
	}
}
