package com.example.ai_controll.tool.policy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which calls may run unattended.
 *
 * <p>This engine governs workflow, not containment. The boundaries that actually contain the model
 * — the HTTP egress allowlist and the stdio command allowlist — are enforced elsewhere and cannot be
 * switched off by a policy, which is why defaulting to "run it" here is a reasonable posture rather
 * than a lax one.
 */
class ToolPolicyEngineTest {

	private ToolPolicyEngine engineWith(ToolPolicyEntity... rules) {
		ToolPolicyRepository repository = mock(ToolPolicyRepository.class);
		List<ToolPolicyEntity> sorted = java.util.Arrays.stream(rules)
				.sorted(java.util.Comparator.comparingInt(ToolPolicyEntity::getPriority)).toList();
		when(repository.findByEnabledTrueOrderByPriorityAscNameAsc()).thenReturn(sorted);
		return new ToolPolicyEngine(repository);
	}

	private ToolPolicyEntity rule(String name, String pattern, ApprovalMode mode, int priority) {
		return new ToolPolicyEntity(UUID.randomUUID(), name, pattern, mode, priority, null, null,
				Instant.now());
	}

	private ToolPolicyEntity limited(String name, String pattern, int limit) {
		return new ToolPolicyEntity(UUID.randomUUID(), name, pattern, ApprovalMode.AUTO, 10, null,
				limit, Instant.now());
	}

	@Test
	@DisplayName("a call matching nothing simply runs")
	void unmatchedCallRuns() {
		// Policies opt in to approval. A harness whose main use is unattended repeated runs would be
		// useless if every call stopped for a human.
		ToolPolicyEngine.Decision decision = engineWith().evaluate("anything", 0, Set.of());

		assertThat(decision.isAuto()).isTrue();
		assertThat(decision.policyName()).isNull();
	}

	@Test
	@DisplayName("a matching rule decides the call")
	void matchingRuleDecides() {
		ToolPolicyEngine.Decision decision = engineWith(
				rule("careful", "http_*", ApprovalMode.REQUIRE_APPROVAL, 100))
				.evaluate("http_fetch", 0, Set.of());

		assertThat(decision.needsApproval()).isTrue();
		assertThat(decision.policyName()).isEqualTo("careful");
		assertThat(decision.reason()).contains("http_*");
	}

	@Test
	@DisplayName("the lowest priority wins, so a specific rule beats a general one")
	void lowerPriorityWins() {
		// A specific rule must be able to override a catch-all without either knowing about the other.
		ToolPolicyEngine engine = engineWith(
				rule("catch-all", "*", ApprovalMode.REQUIRE_APPROVAL, 1000),
				rule("trusted", "clock_now", ApprovalMode.AUTO, 10));

		assertThat(engine.evaluate("clock_now", 0, Set.of()).isAuto()).isTrue();
		assertThat(engine.evaluate("http_fetch", 0, Set.of()).needsApproval()).isTrue();
	}

	@Test
	@DisplayName("a denying rule refuses the call")
	void denyRuleRefuses() {
		ToolPolicyEngine.Decision decision = engineWith(
				rule("no-writes", "*_write", ApprovalMode.DENY, 10))
				.evaluate("file_write", 0, Set.of());

		assertThat(decision.isDenied()).isTrue();
		assertThat(decision.reason()).contains("forbids");
	}

	@Test
	@DisplayName("a run-scoped grant short-circuits the rules")
	void runGrantOverridesPolicy() {
		// Asking again for every call would make a run-scoped approval indistinguishable from a
		// per-call one, which is the whole reason for offering the scope.
		ToolPolicyEngine.Decision decision = engineWith(
				rule("careful", "*", ApprovalMode.REQUIRE_APPROVAL, 10))
				.evaluate("http_fetch", 0, Set.of("http_fetch"));

		assertThat(decision.isAuto()).isTrue();
		assertThat(decision.reason()).contains("approved earlier in this run");
	}

	@Test
	@DisplayName("a rate limit refuses a call once the run has used it up")
	void rateLimitRefusesOnceExhausted() {
		ToolPolicyEngine engine = engineWith(limited("twice", "http_fetch", 2));

		assertThat(engine.evaluate("http_fetch", 0, Set.of()).isAuto()).isTrue();
		assertThat(engine.evaluate("http_fetch", 1, Set.of()).isAuto()).isTrue();

		ToolPolicyEngine.Decision third = engine.evaluate("http_fetch", 2, Set.of());
		assertThat(third.isDenied()).isTrue();
		assertThat(third.reason()).contains("already run 2 times");
	}

	@Test
	@DisplayName("glob patterns match the way they look")
	void globsBehaveIntuitively() {
		// Globs rather than regular expressions, because a policy is written while thinking about
		// tools, not about escaping.
		assertThat(engineWith().matches("*", "anything")).isTrue();
		assertThat(engineWith().matches("http_*", "http_fetch")).isTrue();
		assertThat(engineWith().matches("http_*", "fetch_http")).isFalse();
		assertThat(engineWith().matches("*_write", "file_write")).isTrue();
		assertThat(engineWith().matches("calc_eval", "calc_eval")).isTrue();
		assertThat(engineWith().matches("calc_eval", "calc_evaluate")).isFalse();
		assertThat(engineWith().matches("tool_?", "tool_a")).isTrue();
		assertThat(engineWith().matches("tool_?", "tool_ab")).isFalse();
	}

	@Test
	@DisplayName("regex metacharacters in a pattern are literal")
	void treatsRegexCharactersAsLiteral() {
		// A pattern is a glob, so a dot is a dot. Leaking regex semantics would make 'a.b' match 'axb'
		// and quietly widen a rule someone wrote to be narrow.
		assertThat(engineWith().matches("a.b", "a.b")).isTrue();
		assertThat(engineWith().matches("a.b", "axb")).isFalse();
		assertThat(engineWith().matches("a+b", "a+b")).isTrue();
		assertThat(engineWith().matches("a(b", "a(b")).isTrue();
	}

	@Test
	@DisplayName("a standing allow beats the rule that demanded the approval")
	void standingAllowOutranksTheRuleItOverrides() {
		// The bug this guards against was silent and complete: an ALWAYS approval wrote its rule at the
		// same priority as the rule demanding approval, so the winner came down to tie-breaking and the
		// very next run parked again. The grant appeared to work and did nothing.
		ToolPolicyEngine engine = engineWith(
				rule("approve-calc", "calc_eval", ApprovalMode.REQUIRE_APPROVAL, 10),
				rule("always-allow-calc_eval", "calc_eval", ApprovalMode.AUTO, 9));

		assertThat(engine.evaluate("calc_eval", 0, Set.of()).isAuto()).isTrue();
	}

	@Test
	@DisplayName("a disabled rule is not consulted")
	void disabledRulesAreIgnored() {
		ToolPolicyRepository repository = mock(ToolPolicyRepository.class);
		// The repository only ever returns enabled rules, so a disabled one simply is not there.
		when(repository.findByEnabledTrueOrderByPriorityAscNameAsc()).thenReturn(List.of());

		assertThat(new ToolPolicyEngine(repository).evaluate("http_fetch", 0, Set.of()).isAuto())
				.isTrue();
	}
}
