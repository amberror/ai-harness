package com.example.ai_controll.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ai_controll.lmstudio.dto.DownloadJob;
import com.example.ai_controll.lmstudio.dto.DownloadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelResponse;
import com.example.ai_controll.lmstudio.dto.ModelInfo;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;

import jakarta.validation.Valid;

/**
 * The harness's control plane over the model server.
 *
 * <p>Deliberately a thin pass-through: these endpoints exist so the console and scripts can manage
 * models without a second tool, not to add behaviour on top of LM Studio.
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

	private final ModelAdminClient modelAdminClient;

	public ModelController(ModelAdminClient modelAdminClient) {
		this.modelAdminClient = modelAdminClient;
	}

	@GetMapping
	public List<ModelInfo> list() {
		return modelAdminClient.listModels();
	}

	@PostMapping("/load")
	public LoadModelResponse load(@Valid @RequestBody LoadModelRequest request) {
		return modelAdminClient.load(request);
	}

	@PostMapping("/unload")
	public UnloadResult unload(@Valid @RequestBody UnloadRequest request) {
		modelAdminClient.unload(request.instanceId());
		return new UnloadResult(request.instanceId(), "unloaded");
	}

	@PostMapping("/download")
	public DownloadJob download(@Valid @RequestBody DownloadModelRequest request) {
		return modelAdminClient.download(request);
	}

	@GetMapping("/download/{jobId}")
	public DownloadJob downloadStatus(@PathVariable String jobId) {
		return modelAdminClient.downloadStatus(jobId);
	}

	/** Request body for unloading, kept separate from the wire DTO so validation lives here. */
	public record UnloadRequest(@jakarta.validation.constraints.NotBlank String instanceId) {
	}

	public record UnloadResult(String instanceId, String status) {
	}
}
