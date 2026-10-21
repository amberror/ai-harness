package com.example.ai_controll.tool.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a tool call may run, needs a person, or must be refused.
 *
 * <p>The default is to run. Policies opt in to approval rather than out of it, because a harness
 * whose main use is unattended repeated runs would be useless if every call stopped for a human --
 * and because the boundaries that genuinely matter are enforced elsewhere and unconditionally: the
 * HTTP egress allowlist and the stdio command allowlist. This engine governs <em>workflow</em>, not
 * containment, and the distinction is worth keeping clear.
 *
 * <p>Rules are matched by glob on the tool name and ordered by priority, lowest number first. The
 * first match wins, so a specific rule can override a general one without either knowing about the
 * other.
 */
@Service
public class ToolPolicyEngine {

	private final ToolPolicyRepository policies;

	public ToolPolicyEngine(ToolPolicyRepository policies) {
		this.policies = policies;
	}

	/**
	 * Evaluates one call.
	 *
	 * @param toolName    the tool being called
	 * @param callsSoFar  how many times this tool has already run in this run, for rate limits
	 * @param runGrants   tools already approved for the remainder of this run
	 */
	@Transactional(readOnly = true)
	public Decision evaluate(String toolName, int callsSoFar, Set<String> runGrants) {
		if (runGrants.contains(toolName)) {
			// Already approved for this run; asking again for every call would make a run-scoped
			// approval indistinguishable from a per-call one.
			return new Decision(ApprovalMode.AUTO, "approved earlier in this run", null, null);
		}

		Optional<ToolPolicyEntity> matched = policies.findByEnabledTrueOrderByPriorityAscNameAsc().stream()
				.filter(policy -> matches(policy.getToolNamePattern(), toolName))
				.findFirst();

		if (matched.isEmpty()) {
			return new Decision(ApprovalMode.AUTO, "no policy matched", null, null);
		}

		ToolPolicyEntity policy = matched.get();

		// A rate limit is checked before the mode, so an over-limit call is refused rather than sent
		// for an approval that could not be honoured anyway.
		if (policy.getRateLimitPerRun() != null && callsSoFar >= policy.getRateLimitPerRun()) {
			return new Decision(ApprovalMode.DENY,
					"'%s' has already run %d times in this run, which is its limit under policy '%s'"
							.formatted(toolName, callsSoFar, policy.getName()),
					policy.getName(), policy.getTimeoutMs());
		}

		return new Decision(policy.getApprovalMode(), reasonFor(policy, toolName), policy.getName(),
				policy.getTimeoutMs());
	}

	private static String reasonFor(ToolPolicyEntity policy, String toolName) {
		return switch (policy.getApprovalMode()) {
			case AUTO -> "allowed by policy '%s'".formatted(policy.getName());
			case REQUIRE_APPROVAL -> "policy '%s' requires approval for '%s'"
					.formatted(policy.getName(), policy.getToolNamePattern());
			case DENY -> "policy '%s' forbids '%s'".formatted(policy.getName(),
					policy.getToolNamePattern());
		};
	}

	/**
	 * Glob matching over tool names: {@code *} for any run of characters, {@code ?} for one.
	 *
	 * <p>Globs rather than regular expressions, because a policy is something you write while
	 * thinking about tools, not about regex escaping, and {@code http_*} should mean what it looks
	 * like it means.
	 */
	boolean matches(String pattern, String toolName) {
		if (pattern == null || toolName == null) {
			return false;
		}
		String trimmed = pattern.strip();
		if ("*".equals(trimmed)) {
			return true;
		}
		return globToRegex(trimmed).matcher(toolName).matches();
	}

	/**
	 * Compiled globs, since the same handful of patterns is evaluated on every tool call.
	 *
	 * <p>Held per bean rather than statically, and concurrent rather than synchronised. A static map
	 * would outlive the policies whose patterns it caches -- deleting a rule would leave its pattern
	 * behind forever -- and a lock around the whole method would serialise every tool call in every
	 * run behind a cache lookup that almost always hits.
	 */
	private final Map<String, Pattern> compiledGlobs = new java.util.concurrent.ConcurrentHashMap<>();

	private Pattern globToRegex(String glob) {
		return compiledGlobs.computeIfAbsent(glob, pattern -> {
			StringBuilder regex = new StringBuilder("\\A");
			for (int i = 0; i < pattern.length(); i++) {
				char c = pattern.charAt(i);
				switch (c) {
					case '*' -> regex.append(".*");
					case '?' -> regex.append('.');
					// Everything else is literal. Quoting one character at a time avoids the trap where
					// a pattern containing \E would break a Pattern.quote of the whole string.
					default -> regex.append(Pattern.quote(String.valueOf(c)));
				}
			}
			return Pattern.compile(regex.append("\\z").toString());
		});
	}

	/** All rules, for the console. */
	@Transactional(readOnly = true)
	public List<ToolPolicyEntity> all() {
		return policies.findAllByOrderByPriorityAscNameAsc();
	}

	/**
	 * @param mode       what to do with the call
	 * @param reason     why, in terms a person reading the journal can act on
	 * @param policyName which rule decided it, or null when none matched
	 * @param timeoutMs  per-call timeout the rule imposes, if any
	 */
	public record Decision(ApprovalMode mode, String reason, String policyName, Long timeoutMs) {

		public boolean isAuto() {
			return mode == ApprovalMode.AUTO;
		}

		public boolean needsApproval() {
			return mode == ApprovalMode.REQUIRE_APPROVAL;
		}

		public boolean isDenied() {
			return mode == ApprovalMode.DENY;
		}
	}
}
