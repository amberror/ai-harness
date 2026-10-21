package com.example.ai_controll.orchestrator.guard;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.example.ai_controll.lmstudio.port.TokenUsage;

/**
 * Keeps a run inside its token and wall-clock allowance.
 *
 * <p>Token accounting comes from what the server reports rather than from an estimate, so the figure
 * is the real one. It includes reasoning tokens, which on this model routinely dwarf the answer: a
 * budget that counted only visible output would be measuring a small fraction of what was spent.
 *
 * <p>Time spent queued for the model is deducted from the elapsed figure. The wall-clock budget is
 * there to stop a run making progress too slowly to be worth continuing, and a run that sat waiting
 * because another run held the model has not been slow -- it has not been running. Charging it for
 * that would abort runs for something a different run did, which is the sort of harness artefact
 * that later gets reported as a model finding.
 */
public class BudgetTracker {

	private final Integer tokenBudget;

	private final Long wallClockBudgetMs;

	private final Instant startedAt;

	private TokenUsage total = TokenUsage.EMPTY;

	private long queuedMs;

	public BudgetTracker(Integer tokenBudget, Long wallClockBudgetMs, Instant startedAt) {
		this.tokenBudget = tokenBudget;
		this.wallClockBudgetMs = wallClockBudgetMs;
		this.startedAt = startedAt;
	}

	public void add(TokenUsage usage) {
		this.total = this.total.plus(usage);
	}

	public TokenUsage total() {
		return total;
	}

	/** Records time the run spent waiting for the model rather than using it. */
	public void addQueuedMs(long millis) {
		if (millis > 0) {
			this.queuedMs += millis;
		}
	}

	public long queuedMs() {
		return queuedMs;
	}

	/** Time the run has actually been working, with queueing taken out. */
	public long elapsedMs() {
		return Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis() - queuedMs);
	}

	/**
	 * Whether the run must stop now.
	 *
	 * @return why it must stop, or empty to continue
	 */
	public Optional<String> exceeded() {
		if (tokenBudget != null && total.totalTokens() >= tokenBudget) {
			return Optional.of(
					"Token budget exhausted: %d of %d used (%d spent on reasoning)".formatted(
							total.totalTokens(), tokenBudget, total.reasoningTokens()));
		}
		if (wallClockBudgetMs != null && elapsedMs() >= wallClockBudgetMs) {
			return Optional.of("Time budget exhausted: %d ms of %d allowed"
					.formatted(elapsedMs(), wallClockBudgetMs));
		}
		return Optional.empty();
	}
}
