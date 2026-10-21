package com.example.ai_controll.tool.exec;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three egress modes.
 *
 * <p>The point of splitting them out is that one protection was doing two jobs at very different
 * prices. Refusing private addresses stops the damaging cases and costs no configuration; the host
 * allowlist additionally keeps the harness off the public internet and costs a line per site. These
 * assert that relaxing the second does not relax the first.
 */
class EgressModeTest {

	private HttpEgressPolicy policy(EgressMode mode, List<String> allowed, List<String> blocked,
			boolean allowPrivate) {
		return new HttpEgressPolicy(TestProperties.builder()
				.egressMode(mode)
				.allowedHosts(allowed)
				.blockedHosts(blocked)
				.allowPrivateAddresses(allowPrivate)
				.build());
	}

	@Test
	@DisplayName("ALLOWLIST is unchanged: an empty list reaches nothing")
	void allowlistDeniesByDefault() {
		HttpEgressPolicy policy = policy(EgressMode.ALLOWLIST, List.of(), List.of(), false);

		HttpEgressPolicy.Decision decision = policy.check("https://example.com/");
		assertThat(decision.allowed()).isFalse();
		// The refusal names the way out, both of them, because "no hosts are allowed" without a remedy
		// is where someone gives up on the harness.
		assertThat(decision.reason()).contains("allowed-hosts").contains("PUBLIC");
	}

	@Test
	@DisplayName("PUBLIC reaches any public host without naming it")
	void publicModeNeedsNoList() {
		assertThat(policy(EgressMode.PUBLIC, List.of(), List.of(), false)
				.check("https://example.com/some/page").allowed()).isTrue();
	}

	@Test
	@DisplayName("PUBLIC still refuses the private network")
	void publicModeStillRefusesPrivate() {
		HttpEgressPolicy policy = policy(EgressMode.PUBLIC, List.of(), List.of(), false);

		// The whole argument for the mode: the protection worth having does not come from the domain
		// list, so dropping the list does not drop it.
		assertThat(policy.check("http://localhost:8080/actuator").allowed()).isFalse();
		assertThat(policy.check("http://127.0.0.1:9200/_search").allowed()).isFalse();
		assertThat(policy.check("http://10.0.0.5/admin").allowed()).isFalse();
		assertThat(policy.check("http://192.168.1.1/").allowed()).isFalse();
		// The cloud metadata endpoint, which is link-local and the single most valuable thing to
		// keep a model away from on a hosted machine.
		assertThat(policy.check("http://169.254.169.254/latest/meta-data/").allowed()).isFalse();
	}

	@Test
	@DisplayName("naming a host does not exempt it from the address check")
	void namingDoesNotGrantPrivateAccess() {
		// A wildcard entry is not consent to reach whatever an internal subdomain resolves to, so the
		// two switches stay independent in every mode.
		assertThat(policy(EgressMode.PUBLIC, List.of("localhost"), List.of(), false)
				.check("http://localhost:8080/").allowed()).isFalse();
		assertThat(policy(EgressMode.ALLOWLIST, List.of("localhost"), List.of(), false)
				.check("http://localhost:8080/").allowed()).isFalse();
	}

	@Test
	@DisplayName("private access is one explicit switch, and it works in both modes")
	void privateAccessIsItsOwnSwitch() {
		assertThat(policy(EgressMode.PUBLIC, List.of(), List.of(), true)
				.check("http://localhost:8080/").allowed()).isTrue();
		assertThat(policy(EgressMode.ALLOWLIST, List.of("localhost"), List.of(), true)
				.check("http://localhost:8080/").allowed()).isTrue();
	}

	@Test
	@DisplayName("a blocked host is refused whatever else permits it")
	void blockedHostsWinInEveryMode() {
		assertThat(policy(EgressMode.PUBLIC, List.of(), List.of("ads.example.com"), false)
				.check("https://ads.example.com/x").allowed()).isFalse();
		// Even when someone has also allowlisted it: the denial is the more deliberate statement.
		assertThat(policy(EgressMode.ALLOWLIST, List.of("ads.example.com"), List.of("ads.example.com"),
				false).check("https://ads.example.com/x").allowed()).isFalse();
		assertThat(policy(EgressMode.PUBLIC, List.of(), List.of("*.tracker.net"), false)
				.check("https://a.tracker.net/x").allowed()).isFalse();
	}

	@Test
	@DisplayName("OPEN is what it says, and nothing else is")
	void openModeChecksNothing() {
		HttpEgressPolicy open = policy(EgressMode.OPEN, List.of(), List.of("blocked.example.com"), false);

		assertThat(open.check("http://127.0.0.1:8080/").allowed()).isTrue();
		assertThat(open.check("http://169.254.169.254/latest/meta-data/").allowed()).isTrue();
		// Even the blocked list, because a mode that checks things is not this mode.
		assertThat(open.check("https://blocked.example.com/").allowed()).isTrue();
	}

	@Test
	@DisplayName("the scheme is checked in every mode")
	void schemeIsAlwaysChecked() {
		// file:// and friends never become reachable, whatever the mode says about hosts.
		for (EgressMode mode : EgressMode.values()) {
			assertThat(policy(mode, List.of(), List.of(), true).check("file:///etc/passwd").allowed())
					.as(mode.name())
					.isFalse();
		}
	}
}
