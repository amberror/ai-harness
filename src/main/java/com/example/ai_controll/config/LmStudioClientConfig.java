package com.example.ai_controll.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP client used to talk to LM Studio.
 *
 * <p>The harness deliberately hand-rolls this boundary rather than delegating to a framework, so
 * that the exact JSON sent and received can be captured in the run journal. The JDK HTTP client is
 * used because it also gives us a clean streaming body for the SSE reader added in phase 1.
 */
@Configuration(proxyBeanMethods = false)
public class LmStudioClientConfig {

	/** Shared JDK client; virtual threads make the blocking calls on top of it cheap. */
	@Bean
	public HttpClient lmStudioHttpClient(HarnessProperties properties) {
		return HttpClient.newBuilder()
				.connectTimeout(properties.lmStudio().connectTimeout())
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Bean
	public RestClient lmStudioRestClient(HarnessProperties properties, HttpClient lmStudioHttpClient) {
		HarnessProperties.LmStudio lmStudio = properties.lmStudio();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(lmStudioHttpClient);
		requestFactory.setReadTimeout(lmStudio.readTimeout());

		return RestClient.builder()
				.baseUrl(lmStudio.baseUrl())
				.requestFactory(requestFactory)
				.defaultHeaders(headers -> {
					if (lmStudio.hasApiToken()) {
						headers.setBearerAuth(lmStudio.apiToken());
					}
				})
				.build();
	}
}
