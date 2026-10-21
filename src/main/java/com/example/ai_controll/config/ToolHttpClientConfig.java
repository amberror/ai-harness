package com.example.ai_controll.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP client tools use to reach the outside world.
 *
 * <p>Deliberately separate from the client that talks to LM Studio, and not merely to avoid sharing
 * a connection pool. The two need opposite redirect policies, and for the tool client that policy is
 * a security boundary rather than a preference:
 *
 * <p><strong>The client never follows redirects; {@code HttpFetchTool} follows them itself.</strong>
 * If the client followed a 302 on its own, an allowlisted host could hand the harness straight to one
 * that is not, inside a request whose egress check had already passed — the difference between an
 * allowlist and the appearance of one, given the model chooses these URLs.
 *
 * <p>Refusing them outright was the first answer, and it failed on the most ordinary URLs in
 * existence: a bare domain, an {@code http://} link, anything canonicalised. Following them in the
 * tool instead means every hop goes back through {@code HttpEgressPolicy}, which is a stronger
 * guarantee than the original rule — that one checked the URL the model asked for, this checks every
 * URL actually contacted.
 *
 * <p>The LM Studio client follows redirects normally, because its address is configuration the
 * operator set, not an argument the model produced.
 */
@Configuration(proxyBeanMethods = false)
public class ToolHttpClientConfig {

	@Bean
	public HttpClient toolHttpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}
}
