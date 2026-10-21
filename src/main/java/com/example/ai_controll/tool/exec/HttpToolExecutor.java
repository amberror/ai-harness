package com.example.ai_controll.tool.exec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ToolRegistryService;

import tools.jackson.databind.JsonNode;

/**
 * Runs a tool that is defined entirely as data.
 *
 * <p>This is what makes "add a tool at runtime" real rather than a figure of speech: method, URL
 * template, headers and body come from the stored definition, so a working tool can be created
 * through the API with no code and no redeploy.
 *
 * <p>Its executor configuration looks like:
 *
 * <pre>{@code
 * {
 *   "method": "GET",
 *   "url": "https://api.example.com/weather/{city}",
 *   "headers": {"Accept": "application/json"},
 *   "body": "{\"q\": \"{query}\"}"
 * }
 * }</pre>
 *
 * <p>Placeholders in braces are filled from the validated arguments. Values are URL-encoded when
 * substituted into a URL: the model supplies them, and a value containing {@code ?} or {@code /}
 * would otherwise silently change which endpoint gets called.
 */
@Component
public class HttpToolExecutor implements ToolExecutor {

	private static final Logger log = LoggerFactory.getLogger(HttpToolExecutor.class);

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

	private final HttpClient httpClient;

	private final HttpEgressPolicy egressPolicy;

	private final ToolRegistryService registry;

	private final HarnessProperties.HttpTools config;

	private final Duration defaultTimeout;

	public HttpToolExecutor(HttpEgressPolicy egressPolicy, ToolRegistryService registry,
			HarnessProperties properties, HttpClient toolHttpClient) {
		this.egressPolicy = egressPolicy;
		this.registry = registry;
		this.config = properties.tools().http();
		this.defaultTimeout = properties.tools().defaultTimeout();
		// Shared, and never follows redirects: see ToolHttpClientConfig.
		this.httpClient = toolHttpClient;
	}

	@Override
	public ExecutorType type() {
		return ExecutorType.HTTP;
	}

	@Override
	public ToolOutcome execute(ToolInvocation invocation) {
		long startedAt = System.nanoTime();
		JsonNode spec;
		try {
			spec = registry.parseExecutorConfig(invocation.tool());
		}
		catch (RuntimeException ex) {
			return ToolOutcome.failure("bad_tool_config",
					"The tool's executor configuration is not valid JSON", elapsedMs(startedAt));
		}

		String urlTemplate = text(spec, "url");
		if (urlTemplate == null) {
			return ToolOutcome.failure("bad_tool_config",
					"The tool's executor configuration has no 'url'", elapsedMs(startedAt));
		}

		String method = text(spec, "method") == null ? "GET"
				: text(spec, "method").toUpperCase(java.util.Locale.ROOT);
		String url = fill(urlTemplate, invocation.arguments(), true);

		HttpEgressPolicy.Decision decision = egressPolicy.check(url);
		if (!decision.allowed()) {
			return ToolOutcome.failure("egress_denied", decision.reason(), elapsedMs(startedAt));
		}

		String bodyTemplate = text(spec, "body");
		String body = bodyTemplate == null ? null : fill(bodyTemplate, invocation.arguments(), false);

		Duration timeout = invocation.tool().getTimeoutMs() == null ? defaultTimeout
				: Duration.ofMillis(invocation.tool().getTimeoutMs());

		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);

			JsonNode headers = spec.get("headers");
			if (headers != null && headers.isObject()) {
				for (String key : headers.propertyNames()) {
					JsonNode value = headers.get(key);
					if (value != null && value.isString()) {
						request.header(key, fill(value.stringValue(), invocation.arguments(), false));
					}
				}
			}

			request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

			HttpResponse<String> response = httpClient.send(request.build(),
					HttpResponse.BodyHandlers.ofString());
			long elapsed = elapsedMs(startedAt);

			String responseBody = response.body() == null ? "" : response.body();
			if (responseBody.length() > config.maxResponseBytes()) {
				responseBody = responseBody.substring(0, config.maxResponseBytes());
			}

			if (response.statusCode() >= 400) {
				return ToolOutcome.failure("http_%d".formatted(response.statusCode()),
						"The server responded with HTTP %d".formatted(response.statusCode()), elapsed);
			}
			return ToolOutcome.success(responseBody, elapsed);
		}
		catch (java.net.http.HttpTimeoutException ex) {
			return ToolOutcome.failure("timeout",
					"The request timed out after %s".formatted(timeout), elapsedMs(startedAt));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return ToolOutcome.failure("interrupted", "The request was interrupted", elapsedMs(startedAt));
		}
		catch (Exception ex) {
			log.debug("HTTP tool '{}' failed", invocation.toolName(), ex);
			return ToolOutcome.failure("request_failed",
					ex.getClass().getSimpleName() + ": " + ex.getMessage(), elapsedMs(startedAt));
		}
	}

	/**
	 * Substitutes {@code {name}} placeholders from the arguments.
	 *
	 * @param encode whether to URL-encode the substituted value. Always true for a URL: the model
	 *               chooses these values, and one containing {@code ?}, {@code #} or {@code /} would
	 *               otherwise change which endpoint is actually called
	 */
	static String fill(String template, JsonNode arguments, boolean encode) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			JsonNode value = arguments == null ? null : arguments.get(matcher.group(1));
			String replacement = value == null ? ""
					: (value.isString() ? value.stringValue() : value.toString());
			if (encode) {
				replacement = URLEncoder.encode(replacement, StandardCharsets.UTF_8);
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return (value != null && value.isString()) ? value.stringValue() : null;
	}

	private static long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
