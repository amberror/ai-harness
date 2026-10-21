package com.example.ai_controll.tool.exec.builtin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.tool.exec.HttpEgressPolicy;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fetches a URL and returns something a model can afford to read.
 *
 * <p>The first version returned the raw body, which was honest and nearly useless: a page is mostly
 * markup, and handing 100,000 characters of it to a model with an 8k window spends the whole context
 * on angle brackets. Three things changed, and each of them was costing the model an answer:
 *
 * <ul>
 *   <li><strong>HTML is reduced to text.</strong> Measured on github.com, 575,809 characters became
 *       7,654 — and the title and meta description come back as their own fields, which is often the
 *       entire answer to "what is this page" for the price of twenty tokens.</li>
 *   <li><strong>Truncation is recoverable.</strong> The old version cut the body off with no way
 *       forward, so a model that needed something further down was simply stuck. It now reports the
 *       total size and accepts an {@code offset}, so reading page two is a second call rather than a
 *       dead end.</li>
 *   <li><strong>Redirects are followed.</strong> They were refused outright, which failed on the most
 *       ordinary URLs in existence — a bare domain, an {@code http://} link, anything canonicalised.
 *       Each hop is checked against the egress policy separately, which is a stronger guarantee than
 *       the old one: the original rule checked the URL the model asked for, and this checks every URL
 *       actually contacted.</li>
 * </ul>
 *
 * <p>JSON is passed through untouched, so {@code json_extract} can read a field straight out of it.
 * {@code raw: true} disables extraction for the cases where the markup <em>is</em> the question.
 *
 * <p>Subject to {@link HttpEgressPolicy}: the model chooses the URL, so this is precisely where an
 * unconstrained tool would hand it an arbitrary-request primitive.
 */
@Component
public class HttpFetchTool implements BuiltinTool {

	/** Enough for a canonical redirect chain; more than that is a loop or a misconfiguration. */
	private static final int MAX_REDIRECTS = 5;

	/** What a single call returns unless asked otherwise. Roughly a page of prose. */
	private static final int DEFAULT_MAX_CHARS = 3000;

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final HttpClient httpClient;

	private final HttpEgressPolicy egressPolicy;

	private final HarnessProperties.HttpTools config;

	private final ObjectMapper objectMapper;

	public HttpFetchTool(HttpEgressPolicy egressPolicy, HarnessProperties properties,
			HttpClient toolHttpClient, ObjectMapper objectMapper) {
		this.egressPolicy = egressPolicy;
		this.config = properties.tools().http();
		// Shared, and configured never to follow redirects on its own: this class follows them by
		// hand so that every hop goes back through the egress policy. See ToolHttpClientConfig.
		this.httpClient = toolHttpClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "http_fetch";
	}

	@Override
	public String defaultDescription() {
		return "Fetches a web page or API response and returns its readable text. HTML is stripped to "
				+ "prose and the page title is returned separately; JSON is returned as-is. Long pages "
				+ "come back in pieces: the reply says totalChars, and you can call again with a larger "
				+ "maxChars or with offset set past what you already read.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "url":{"type":"string",
				     "description":"Absolute URL to fetch, e.g. https://example.com/data.json"},
				   "maxChars":{"type":"integer","minimum":1,
				     "description":"How much text to return; defaults to 3000"},
				   "offset":{"type":"integer","minimum":0,
				     "description":"Skip this many characters first, to continue reading a long page"},
				   "raw":{"type":"boolean",
				     "description":"Return the body exactly as sent, without stripping HTML"}},
				 "required":["url"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<String> requested = Args.text(arguments, "url");
		if (requested.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					Args.missing("url", "an absolute http or https URL"), 0L);
		}

		long startedAt = System.nanoTime();
		try {
			return fetch(requested.get(), arguments, startedAt);
		}
		catch (java.net.http.HttpTimeoutException ex) {
			return ToolOutcome.failure("timeout",
					"The request timed out after %d seconds".formatted(TIMEOUT.toSeconds()),
					elapsedMs(startedAt));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ToolOutcome.failure("interrupted", "The request was interrupted", elapsedMs(startedAt));
		}
		catch (Exception ex) {
			return ToolOutcome.failure("request_failed", describe(ex, requested.get()),
					elapsedMs(startedAt));
		}
	}

	/**
	 * A network failure in terms the model can act on.
	 *
	 * <p>Taken from a real run: asked the time in Toronto, the model picked
	 * {@code worldtimeapi.org/api/timezone/America/Toronto} — exactly the right API — and was told
	 * {@code ConnectException: null}, because that exception carries no message. "null" tells the
	 * model nothing about whether to retry, try a different URL, or give up, so it spent the rest of
	 * its budget guessing. Naming the host and the kind of failure costs nothing and answers that.
	 */
	static String describe(Exception ex, String url) {
		String host;
		try {
			host = URI.create(url).getHost();
		}
		catch (RuntimeException ignored) {
			host = url;
		}
		return switch (ex) {
			case java.net.UnknownHostException ignored ->
				"There is no host named '%s'. Check the address, or use a different site.".formatted(host);
			case java.net.ConnectException ignored ->
				("Could not connect to '%s'. The site is refusing connections or is down; a different "
						+ "source is more likely to work than retrying this one.").formatted(host);
			case javax.net.ssl.SSLException ignored ->
				("The secure connection to '%s' failed: %s. The site may be down or misconfigured; "
						+ "retrying will probably not help.").formatted(host, reason(ex));
			case java.io.IOException ignored ->
				"The request to '%s' failed: %s".formatted(host, reason(ex));
			default -> "%s: %s".formatted(ex.getClass().getSimpleName(), reason(ex));
		};
	}

	/** Exceptions here routinely carry no message at all, and "null" is not a reason. */
	private static String reason(Exception ex) {
		String message = ex.getMessage();
		return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
	}

	private ToolOutcome fetch(String requestedUrl, JsonNode arguments, long startedAt)
			throws Exception {

		String target = requestedUrl.strip();
		HttpResponse<String> response = null;
		int redirects = 0;

		while (true) {
			// Every hop, not just the first. An allowlisted host handing the harness to one that is
			// not is exactly the trick this closes, and it is invisible to a check done once.
			HttpEgressPolicy.Decision decision = egressPolicy.check(target);
			if (!decision.allowed()) {
				String reason = redirects == 0 ? decision.reason()
						: "A redirect led to a URL that is not permitted: " + decision.reason();
				// Refusal is an outcome the model is told about, so it can choose another route or
				// explain the limitation, rather than a failure that kills the run.
				return ToolOutcome.failure("egress_denied", reason, elapsedMs(startedAt));
			}

			response = httpClient.send(request(target), HttpResponse.BodyHandlers.ofString());
			Optional<String> location = redirectTarget(response, target);
			if (location.isEmpty()) {
				break;
			}
			if (++redirects > MAX_REDIRECTS) {
				return ToolOutcome.failure("too_many_redirects",
						"Gave up after %d redirects starting at %s".formatted(MAX_REDIRECTS, requestedUrl),
						elapsedMs(startedAt));
			}
			target = location.get();
		}

		long elapsed = elapsedMs(startedAt);
		int status = response.statusCode();
		if (status >= 400) {
			// Reported as a failure carrying the status, because "the page said 404" is something the
			// model can reason about and act on, unlike a generic error.
			return ToolOutcome.failure("http_%d".formatted(status),
					"The server responded with HTTP %d for %s".formatted(status, target), elapsed);
		}

		String contentType = response.headers().firstValue("content-type").orElse("");
		String body = response.body() == null ? "" : response.body();
		if (body.length() > config.maxResponseBytes()) {
			body = body.substring(0, config.maxResponseBytes());
		}

		boolean raw = Boolean.TRUE.equals(bool(arguments, "raw"));
		boolean html = !raw && HtmlTextExtractor.isHtml(contentType);
		String content = html ? HtmlTextExtractor.toText(body) : body;

		ObjectNode result = objectMapper.createObjectNode();
		result.put("url", requestedUrl);
		if (!target.equals(requestedUrl)) {
			// Named separately so a model reasoning about the answer knows where it actually came from.
			result.put("finalUrl", target);
		}
		result.put("status", status);
		result.put("contentType", contentType);
		result.put("format", raw ? "raw" : (html ? "text" : "body"));
		if (html) {
			HtmlTextExtractor.title(body).ifPresent(title -> result.put("title", title));
			HtmlTextExtractor.description(body).ifPresent(text -> result.put("description", text));
		}

		int offset = Math.max(0, Args.integer(arguments, "offset").orElse(0));
		int maxChars = Math.max(1, Args.integer(arguments, "maxChars").orElse(DEFAULT_MAX_CHARS));
		result.put("totalChars", content.length());

		if (offset >= content.length()) {
			result.put("offset", offset);
			result.put("returnedChars", 0);
			result.put("truncated", false);
			result.put("content", "");
			// Told plainly, because a model that asked for page four of a three-page document should
			// stop rather than keep paging into nothing.
			result.put("note", "The offset is past the end of the content; there is nothing more to read.");
			return ToolOutcome.success(result.toString(), elapsed);
		}

		int end = Math.min(content.length(), offset + maxChars);
		String slice = content.substring(offset, end);
		boolean more = end < content.length();

		result.put("offset", offset);
		result.put("returnedChars", slice.length());
		result.put("truncated", more);
		if (more) {
			// The recovery instruction travels with the truncation, so the model does not have to
			// remember the convention from a tool description it saw several steps ago.
			result.put("continueWithOffset", end);
		}
		result.put("content", slice);
		return ToolOutcome.success(result.toString(), elapsed);
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.GET()
				.timeout(TIMEOUT)
				.header("Accept", "text/plain, application/json, text/html;q=0.9, */*;q=0.5")
				// Some sites answer a request with no user agent with a 403, which the model then has
				// to reason about pointlessly. Honest about what is calling.
				.header("User-Agent", "ai-controll-harness/1.0 (+http_fetch tool)")
				.build();
	}

	/** The absolute URL a redirect points at, or empty when the response is not one. */
	private static Optional<String> redirectTarget(HttpResponse<String> response, String from) {
		int status = response.statusCode();
		if (status < 300 || status > 399) {
			return Optional.empty();
		}
		return response.headers().firstValue("location")
				.map(location -> {
					try {
						// Resolved against the URL it came from, because a Location header is very often
						// relative and a model asked to fix that itself would be doing the client's job.
						return URI.create(from).resolve(location.strip()).toString();
					}
					catch (RuntimeException ex) {
						return null;
					}
				})
				.filter(location -> location != null && !location.equals(from));
	}

	private static Boolean bool(JsonNode arguments, String field) {
		JsonNode value = arguments == null ? null : arguments.get(field);
		return (value != null && value.isBoolean()) ? value.asBoolean() : null;
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
