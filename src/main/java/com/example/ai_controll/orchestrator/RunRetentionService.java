package com.example.ai_controll.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.eval.store.EvalResultRepository;
import com.example.ai_controll.orchestrator.approval.PendingApprovalRepository;
import com.example.ai_controll.orchestrator.store.RunEntity;
import com.example.ai_controll.orchestrator.store.RunEventRepository;
import com.example.ai_controll.orchestrator.store.RunMessageRepository;
import com.example.ai_controll.orchestrator.store.RunRepository;

/**
 * Removes old runs, so the journal that makes the harness useful does not also make it unusable.
 *
 * <p>The journal keeps the exact request and response bodies for every step, capped at 32 KB each.
 * That is what lets a run be explained months later, and it is also several hundred kilobytes per
 * run: a few weeks of batches is a database file measured in gigabytes, on a machine that is
 * probably also holding several quantised models. Nothing removed anything until now.
 *
 * <p>Two rules keep the deletion from destroying evidence someone still needs:
 *
 * <ul>
 *   <li><strong>Off by default.</strong> Retention starts at zero, meaning keep everything. Deleting
 *       a run is not recoverable, and a harness should not quietly discard measurements because it
 *       was left running.</li>
 *   <li><strong>A run cited by an evaluation is never pruned</strong>, however old. A batch's pass
 *       rate is only worth anything while the runs behind it can still be opened; pruning them would
 *       leave a number with nothing underneath it, which is exactly the situation the eval pages
 *       were built to avoid.</li>
 * </ul>
 */
@Service
public class RunRetentionService {

	private static final Logger log = LoggerFactory.getLogger(RunRetentionService.class);

	private final RunRepository runs;

	private final RunEventRepository events;

	private final RunMessageRepository messages;

	private final PendingApprovalRepository approvals;

	private final EvalResultRepository evalResults;

	private final HarnessProperties.Journal settings;

	public RunRetentionService(RunRepository runs, RunEventRepository events,
			RunMessageRepository messages, PendingApprovalRepository approvals,
			EvalResultRepository evalResults, HarnessProperties properties) {
		this.runs = runs;
		this.events = events;
		this.messages = messages;
		this.approvals = approvals;
		this.evalResults = evalResults;
		this.settings = properties.journal();
	}

	/**
	 * Deletes one run and everything recorded about it.
	 *
	 * <p>The journal, transcript and any parked approval go together. Leaving orphans behind would be
	 * worse than leaving the run: rows that belong to a run nobody can open are not evidence, they are
	 * just weight.
	 *
	 * @return false when there was no such run
	 */
	@Transactional
	public boolean delete(UUID runId) {
		if (!this.runs.existsById(runId)) {
			return false;
		}
		this.events.deleteByRunId(runId);
		this.messages.deleteByRunId(runId);
		this.approvals.deleteByRunId(runId);
		this.runs.deleteById(runId);
		log.info("Deleted run {} and its journal", runId);
		return true;
	}

	/**
	 * Removes runs that ended before a cut-off.
	 *
	 * @return how many were deleted
	 */
	@Transactional
	public int pruneOlderThan(Instant cutoff) {
		List<RunEntity> stale = this.runs.findByEndedAtBefore(cutoff);
		int deleted = 0;
		for (RunEntity run : stale) {
			if (this.evalResults.existsByRunId(run.getId())) {
				// Cited by a batch. Its pass rate is only meaningful while this can still be read.
				continue;
			}
			this.events.deleteByRunId(run.getId());
			this.messages.deleteByRunId(run.getId());
			this.approvals.deleteByRunId(run.getId());
			this.runs.deleteById(run.getId());
			deleted++;
		}
		if (deleted > 0) {
			log.info("Pruned {} run(s) that ended before {}", deleted, cutoff);
		}
		return deleted;
	}

	/**
	 * The scheduled sweep. Hourly rather than daily so that a long-lived instance does not accumulate
	 * a day's worth before acting, and so the setting can be seen taking effect while someone is
	 * watching.
	 */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void pruneOnSchedule() {
		int days = this.settings.retentionDays();
		if (days <= 0) {
			return;
		}
		pruneOlderThan(Instant.now().minus(Duration.ofDays(days)));
	}
}
