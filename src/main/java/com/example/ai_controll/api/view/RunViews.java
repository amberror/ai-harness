package com.example.ai_controll.api.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.ai_controll.conversation.Role;
import com.example.ai_controll.orchestrator.domain.RunStatus;
import com.example.ai_controll.orchestrator.store.RunEntity;
import com.example.ai_controll.orchestrator.store.RunMessageEntity;

/** Read models for runs, shared by the REST API and the console. */
public final class RunViews {

	private RunViews() {
	}

	/** Enough to render a row in a list without loading the transcript or the journal. */
	public record RunSummary(UUID id, RunStatus status, String modelKey, String input,
			Instant startedAt, Instant endedAt, Long durationMs, int stepsUsed, int inputTokens,
			int outputTokens, int toolCallsTotal, int toolCallsInvalid, String error) {

		public static RunSummary from(RunEntity run) {
			Long durationMs = (run.getEndedAt() == null) ? null
					: java.time.Duration.between(run.getStartedAt(), run.getEndedAt()).toMillis();
			return new RunSummary(run.getId(), run.getStatus(), run.getModelKey(), run.getInput(),
					run.getStartedAt(), run.getEndedAt(), durationMs, run.getStepsUsed(),
					run.getInputTokens(), run.getOutputTokens(), run.getToolCallsTotal(),
					run.getToolCallsInvalid(), run.getError());
		}

		/** A short form of the input, for list rows. */
		public String inputPreview() {
			if (input == null) {
				return "";
			}
			String flattened = input.replaceAll("\s+", " ").strip();
			return flattened.length() <= 90 ? flattened : flattened.substring(0, 89) + "\u2026";
		}
	}

	/**
	 * One transcript entry.
	 *
	 * <p>Reasoning is carried alongside the content rather than merged into it: the console shows it
	 * separately, because being able to see what the model thought without confusing it for what the
	 * model said is most of the value of watching a small model at all.
	 */
	public record MessageView(int seq, int stepNo, Role role, String content, String reasoning,
			String toolCallId, String toolName, boolean truncated) {

		public static MessageView from(RunMessageEntity entity) {
			return new MessageView(entity.getSeq(), entity.getStepNo(), entity.getRole(),
					entity.getContent(), entity.getReasoning(), entity.getToolCallId(),
					entity.getToolName(), entity.isTruncated());
		}

		public boolean hasReasoning() {
			return reasoning != null && !reasoning.isBlank();
		}

		public boolean hasContent() {
			return content != null && !content.isBlank();
		}
	}

	/** A run with its transcript. The journal is fetched separately; it is much larger. */
	public record RunDetail(RunSummary summary, String finalOutput, List<MessageView> transcript) {
	}
}
