package com.example.ai_controll.lmstudio.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status of a model download, returned both when starting a download and when polling
 * {@code GET /api/v1/models/download/status/{job_id}}.
 *
 * <p>Fields are populated selectively by LM Studio depending on the status, so every one of them
 * other than {@code status} may be null. Notably {@code jobId} is absent when the model was already
 * present locally.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DownloadJob(@JsonProperty("job_id") String jobId,
		@JsonProperty("status") String status,
		@JsonProperty("bytes_per_second") Double bytesPerSecond,
		@JsonProperty("total_size_bytes") Long totalSizeBytes,
		@JsonProperty("downloaded_bytes") Long downloadedBytes,
		@JsonProperty("started_at") Instant startedAt,
		@JsonProperty("estimated_completion") Instant estimatedCompletion,
		@JsonProperty("completed_at") Instant completedAt) {

	public boolean isAlreadyDownloaded() {
		return "already_downloaded".equals(status);
	}

	public boolean isTerminal() {
		return "completed".equals(status) || "failed".equals(status) || isAlreadyDownloaded();
	}

	/** Download progress in the range [0,1], or empty when LM Studio has not reported sizes yet. */
	public Double progress() {
		if (totalSizeBytes == null || downloadedBytes == null || totalSizeBytes <= 0) {
			return null;
		}
		return Math.min(1.0d, (double) downloadedBytes / (double) totalSizeBytes);
	}
}
