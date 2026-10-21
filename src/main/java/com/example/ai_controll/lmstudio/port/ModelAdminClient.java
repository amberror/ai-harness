package com.example.ai_controll.lmstudio.port;

import java.util.List;

import com.example.ai_controll.lmstudio.dto.DownloadJob;
import com.example.ai_controll.lmstudio.dto.DownloadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelResponse;
import com.example.ai_controll.lmstudio.dto.ModelInfo;

/**
 * The harness's control plane over the model server: what is available, what is resident in memory,
 * and with which configuration.
 *
 * <p>Kept as a port so the harness is not welded to LM Studio. The inference path is a separate
 * interface, because the two concerns have genuinely different lifetimes: model management is
 * occasional and administrative, inference is per-step and on the hot path.
 */
public interface ModelAdminClient {

	/** All models known to the server, loaded or not. */
	List<ModelInfo> listModels();

	/** Loads a model into memory, returning the configuration it was actually loaded with. */
	LoadModelResponse load(LoadModelRequest request);

	/** Evicts a loaded model instance from memory. */
	void unload(String instanceId);

	/** Starts a download, or reports that the model is already present. */
	DownloadJob download(DownloadModelRequest request);

	/** Polls the progress of a download started by {@link #download}. */
	DownloadJob downloadStatus(String jobId);

	/** Whether the server is currently answering. Never throws. */
	boolean isReachable();
}
