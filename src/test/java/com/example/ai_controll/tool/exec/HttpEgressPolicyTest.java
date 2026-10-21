package com.example.ai_controll.tool.exec;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security boundary for tools the model can point wherever it likes.
 *
 * <p>A tool definition is runtime-editable data and the model supplies the arguments, so a
 * URL-templated argument is an arbitrary-request primitive. These tests are about what the harness
 * refuses, which matters more than what it permits.
 */
class HttpEgressPolicyTest {

	private HttpEgressPolicy policy(List<String> allowedHosts, boolean allowPrivate) {
		return new HttpEgressPolicy(TestProperties.builder()
				.allowedHosts(allowedHosts).allowPrivateAddresses(allowPrivate).build());
	}

	@Test
	@DisplayName("nothing is allowed until a host is named")
	void deniesByDefault() {
		// Deny-by-default is the whole point: an empty allowlist must mean "no outbound calls", not
		// "no restrictions".
		HttpEgressPolicy.Decision decision = policy(List.of(), false).check("https://example.com/x");

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).contains("allowed-hosts");
	}

	@Test
	@DisplayName("an allowlisted host is permitted")
	void allowsListedHost() {
		assertThat(policy(List.of("example.com"), false).check("https://example.com/data.json").allowed())
				.isTrue();
	}

	@Test
	@DisplayName("a host that is not listed is refused, and the reason says which are")
	void refusesUnlistedHost() {
		HttpEgressPolicy.Decision decision =
				policy(List.of("example.com"), false).check("https://evil.test/steal");

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).contains("evil.test").contains("example.com");
	}

	@Test
	@DisplayName("a wildcard matches subdomains but not a lookalike parent")
	void wildcardMatchesOnlySubdomains() {
		HttpEgressPolicy wildcard = policy(List.of("*.example.com"), false);

		assertThat(wildcard.check("https://api.example.com/x").allowed()).isTrue();
		assertThat(wildcard.check("https://deep.api.example.com/x").allowed()).isTrue();
		// The dot is kept in the suffix on purpose: without it, notexample.com would match.
		assertThat(wildcard.check("https://notexample.com/x").allowed()).isFalse();
	}

	@Test
	@DisplayName("non-http schemes are refused")
	void refusesOtherSchemes() {
		HttpEgressPolicy open = policy(List.of("example.com"), true);

		// file: would read the local disk; a tool is not a file reader.
		assertThat(open.check("file:///etc/passwd").allowed()).isFalse();
		assertThat(open.check("ftp://example.com/x").allowed()).isFalse();
		assertThat(open.check("jar:file:///x").allowed()).isFalse();
	}

	@Test
	@DisplayName("loopback is refused even when it is on the allowlist")
	void refusesLoopbackUnlessPrivateAllowed() {
		// Naming localhost is not on its own consent to reach the local network; that is a separate,
		// explicit switch, so a redirect or DNS record cannot smuggle the model onto it.
		assertThat(policy(List.of("localhost"), false).check("http://localhost:9200/_search").allowed())
				.isFalse();
		assertThat(policy(List.of("127.0.0.1"), false).check("http://127.0.0.1:8080/x").allowed())
				.isFalse();
	}

	@Test
	@DisplayName("loopback is permitted once private addresses are explicitly enabled")
	void allowsLoopbackWhenEnabled() {
		assertThat(policy(List.of("localhost"), true).check("http://localhost:9200/_search").allowed())
				.isTrue();
	}

	@Test
	@DisplayName("a URL with no host is refused")
	void refusesHostlessUrl() {
		assertThat(policy(List.of("example.com"), true).check("https:///nowhere").allowed()).isFalse();
		assertThat(policy(List.of("example.com"), true).check("not a url at all").allowed()).isFalse();
	}

	@Test
	@DisplayName("host matching ignores case")
	void matchesHostCaseInsensitively() {
		assertThat(policy(List.of("Example.COM"), false).check("https://EXAMPLE.com/x").allowed())
				.isTrue();
	}
}
