package com.example.ai_controll.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Root configuration for the harness. Everything that points at the outside world lives here so
 * that swapping the model, the server or the timeouts never requires touching code.
 */
@ConfigurationProperties(prefix = "harness")
public record HarnessProperties(@DefaultValue LmStudio lmStudio, @DefaultValue Journal journal,
		@DefaultValue Tools tools, @DefaultValue Mcp mcp, @DefaultValue Eval eval,
		@DefaultValue Context context) {

	/**
	 * How the harness fits a growing conversation into the model's context window.
	 *
	 * @param defaultLength        window to assume when the server does not report one; 0 means ask
	 *                             the server and, failing that, compact nothing and let it decide
	 * @param safetyMarginTokens   slack between the estimate and the real window. The estimate is a
	 *                             calibrated approximation, and being a little under is free whereas
	 *                             being over costs the front of the conversation
	 * @param keepRecentExchanges  exchanges never dropped, however tight the window. Below two, a
	 *                             model loses the tool result it asked for on the previous step
	 * @param minToolResultChars   floor a tool result is shortened to rather than removed, so the
	 *                             model can still see which call it answers
	 */
	public record Context(@DefaultValue("0") int defaultLength,
			@DefaultValue("128") int safetyMarginTokens,
			@DefaultValue("2") int keepRecentExchanges,
			@DefaultValue("240") int minToolResultChars) {

		public Integer defaultLengthOrNull() {
			return defaultLength > 0 ? defaultLength : null;
		}
	}

	/** Settings for evaluation. */
	public record Eval(@DefaultValue Judge judge) {
	}

	/**
	 * Settings for the model-graded assertion.
	 *
	 * @param maxOutputTokens allowance for the judge's own reply. Generous by measurement, not by
	 *                        guesswork: the judge is subject to the same reasoning tax as the model
	 *                        under test, and at 1024 this model reliably spent the whole allowance
	 *                        thinking and returned no verdict at all
	 * @param modelKey        which model judges; empty means the same one being measured, which is
	 *                        the cheapest option and the least independent
	 */
	public record Judge(@DefaultValue("4096") int maxOutputTokens,
			@DefaultValue("") String modelKey) {

		public boolean hasModelKey() {
			return modelKey != null && !modelKey.isBlank();
		}
	}

	/**
	 * Settings for MCP servers.
	 *
	 * @param requestTimeout    ceiling on one MCP call
	 * @param initializeTimeout ceiling on the handshake; a stdio server has to start a process first,
	 *                          which on a cold npm cache can take a while
	 */
	public record Mcp(@DefaultValue Stdio stdio,
			@DefaultValue("60s") java.time.Duration requestTimeout,
			@DefaultValue("30s") java.time.Duration initializeTimeout) {
	}

	/**
	 * Launch rules for stdio MCP servers.
	 *
	 * <p>Empty by default, so no server can be launched until a command is named. A server
	 * configuration is runtime-editable data and launching one runs a program, so without this an API
	 * for managing tools would also be an API for executing arbitrary commands.
	 *
	 * @param allowedCommands executables that may be launched, by name ({@code npx}, {@code python})
	 *                        or full path. Arguments are not constrained: an allowed interpreter will
	 *                        run whatever it is given, so checking them would be false assurance
	 */
	public record Stdio(@DefaultValue({}) java.util.List<String> allowedCommands) {
	}

	/** Settings for running tools. */
	public record Tools(@DefaultValue HttpTools http, @DefaultValue Files files,
			@DefaultValue Learning learning,
			@DefaultValue("30s") java.time.Duration defaultTimeout,
			@DefaultValue("4000") int maxOutputChars) {
	}

	/**
	 * Settings for reading the journal back as evidence about how the model copes with each tool.
	 *
	 * @param sampleEvents    how many recent journal entries to look at. A window rather than all
	 *                        history, so a tool reworked last week is not judged on how it behaved
	 *                        before that
	 * @param maxOutputTokens allowance for the model that drafts a description. Generous for the same
	 *                        measured reason the judge's is: at a tight allowance this model spends
	 *                        everything reasoning about the wording and returns nothing
	 * @param modelKey        which model drafts; empty means the one under test, which is cheap and
	 *                        not independent
	 */
	public record Learning(@DefaultValue("2000") int sampleEvents,
			@DefaultValue("4096") int maxOutputTokens,
			@DefaultValue("") String modelKey) {

		public boolean hasModelKey() {
			return modelKey != null && !modelKey.isBlank();
		}
	}

	/**
	 * Which files a tool may read.
	 *
	 * <p>Empty by default, so {@code file_read} ships inert. The model supplies the path, so an
	 * ungated file tool reads whatever this process can see -- keys, browser profiles, the harness's
	 * own database. Naming a directory is a decision, and it is taken here rather than through the
	 * API precisely so that the API for managing tools is not also an API for reading the disk.
	 *
	 * @param allowedDirectories roots that may be read from. A path is resolved to its real location,
	 *                           with symbolic links followed, before being compared -- checking the
	 *                           text as written would be defeated by {@code ..} or by a link
	 * @param maxBytes           ceiling on a single file
	 */
	public record Files(@DefaultValue({}) java.util.List<String> allowedDirectories,
			@DefaultValue("1048576") long maxBytes) {
	}

	/**
	 * Egress rules for HTTP tools.
	 *
	 * <p>A tool definition is runtime-editable data and the model supplies the arguments, so an
	 * unrestricted HTTP tool hands the model an arbitrary-request primitive against whatever this
	 * machine can reach. The two protections are separate because they are worth different amounts:
	 * refusing <em>private</em> addresses costs no configuration and stops the damaging cases, while
	 * the host allowlist additionally keeps the harness off the public internet and costs a line per
	 * site. {@link com.example.ai_controll.tool.exec.EgressMode} chooses how much of that to apply.
	 *
	 * @param mode                  ALLOWLIST (only named hosts), PUBLIC (any public host, private
	 *                              addresses still refused) or OPEN (no checks at all)
	 * @param allowedHosts          exact hosts, or a single leading wildcard such as *.example.com;
	 *                              the gate in ALLOWLIST mode, and unused in PUBLIC mode
	 * @param blockedHosts          never reachable, in any mode but OPEN, whatever else permits them
	 * @param allowPrivateAddresses whether any host may resolve to a private or loopback address;
	 *                              off by default, in every mode, so neither a DNS record nor a
	 *                              wildcard allowlist entry can put the model on the local network
	 * @param maxResponseBytes      ceiling on a response body, before extraction and paging
	 */
	public record HttpTools(
			@DefaultValue("ALLOWLIST") com.example.ai_controll.tool.exec.EgressMode mode,
			@DefaultValue({}) java.util.List<String> allowedHosts,
			@DefaultValue({}) java.util.List<String> blockedHosts,
			@DefaultValue("false") boolean allowPrivateAddresses,
			@DefaultValue("1048576") int maxResponseBytes) {
	}

	/**
	 * Journal settings.
	 *
	 * @param maxRawBodyChars how much of a raw request or response body to retain. Streamed responses
	 *                        are thousands of Server-Sent Event envelopes rather than one document,
	 *                        and a single long answer can run to hundreds of kilobytes of mostly
	 *                        repeated framing. Anything beyond the cap is dropped with an explicit
	 *                        marker, never silently.
	 * @param retentionDays   how long a finished run is kept. Zero, the default, keeps everything:
	 *                        deleting a run cannot be undone, and a harness should not discard
	 *                        measurements merely because it was left running. Runs cited by an
	 *                        evaluation are never pruned whatever this says
	 */
	public record Journal(@DefaultValue("32768") int maxRawBodyChars,
			@DefaultValue("0") int retentionDays) {
	}

	/**
	 * Connection settings for the LM Studio server.
	 *
	 * @param baseUrl        root URL of the LM Studio server, without a trailing slash
	 * @param apiToken       optional bearer token; LM Studio only requires it when authentication
	 *                       is switched on in the server settings, or when using local MCP plugins
	 * @param connectTimeout how long to wait for the TCP connection
	 * @param readTimeout    how long to wait for a response; generous by default, because a cold
	 *                       model load plus a long generation can easily exceed a minute
	 * @param defaultModel   model key used when a request does not name one
	 * @param maxConcurrentRequests how many generation requests may be in flight at once. One by
	 *                       default because LM Studio serves a single model instance: a second
	 *                       request does not run alongside the first, it queues behind it, and every
	 *                       latency figure the harness records would then include someone else's
	 *                       generation
	 * @param maxRetries     how many times a request that failed on transport or a server-side 5xx is
	 *                       tried again. One by default: a model still loading answers 503 for a few
	 *                       seconds, and losing a whole batch to that is worse than waiting. Every
	 *                       retry is journalled, because a retry that hides instability is worse than
	 *                       no retry at all
	 * @param retryBackoff   pause before a retry
	 */
	public record LmStudio(@DefaultValue("http://localhost:1234") String baseUrl,
			@DefaultValue("") String apiToken,
			@DefaultValue("5s") Duration connectTimeout,
			@DefaultValue("300s") Duration readTimeout,
			@DefaultValue("") String defaultModel,
			@DefaultValue("1") int maxConcurrentRequests,
			@DefaultValue("1") int maxRetries,
			@DefaultValue("2s") Duration retryBackoff) {

		public boolean hasApiToken() {
			return apiToken != null && !apiToken.isBlank();
		}

		public boolean hasDefaultModel() {
			return defaultModel != null && !defaultModel.isBlank();
		}
	}
}
