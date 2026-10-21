package com.example.ai_controll.lmstudio.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.dto.DownloadJob;
import com.example.ai_controll.lmstudio.dto.DownloadModelRequest;
import com.example.ai_controll.lmstudio.dto.LmStudioError;
import com.example.ai_controll.lmstudio.dto.LoadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelResponse;
import com.example.ai_controll.lmstudio.dto.ModelInfo;
import com.example.ai_controll.lmstudio.dto.ModelListResponse;
import com.example.ai_controll.lmstudio.dto.UnloadModelRequest;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Talks to LM Studio's native {@code /api/v1/models*} endpoints.
 *
 * <p>The harness uses the native API for model management even though it drives inference through
 * the OpenAI-compatible endpoint, because the native API is the only one that exposes load-time
 * configuration -- above all {@code context_length}, the main lever for keeping a small model inside
 * a workable budget.
 */
@Component
public class LmStudioAdminClient implements ModelAdminClient {

	private static final Logger log = LoggerFactory.getLogger(LmStudioAdminClient.class);

	private static final String MODELS = "/api/v1/models";

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final String baseUrl;

	public LmStudioAdminClient(RestClient lmStudioRestClient, ObjectMapper objectMapper,
			HarnessProperties properties) {
		this.restClient = lmStudioRestClient;
		this.objectMapper = objectMapper;
		this.baseUrl = properties.lmStudio().baseUrl();
	}

	@Override
	public List<ModelInfo> listModels() {
		ModelListResponse response = call(() -> restClient.get()
				.uri(MODELS)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.onStatus(HttpStatusCode::isError, this::raise)
				.body(ModelListResponse.class));
		return response == null ? List.of() : response.models();
	}

	@Override
	public LoadModelResponse load(LoadModelRequest request) {
		LoadModelRequest body = request.withEchoedConfig();
		log.info("Loading model '{}' (context_length={})", body.model(), body.contextLength());
		return call(() -> restClient.post()
				.uri(MODELS + "/load")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.onStatus(HttpStatusCode::isError, this::raise)
				.body(LoadModelResponse.class));
	}

	@Override
	public void unload(String instanceId) {
		log.info("Unloading model instance '{}'", instanceId);
		call(() -> restClient.post()
				.uri(MODELS + "/unload")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new UnloadModelRequest(instanceId))
				.retrieve()
				.onStatus(HttpStatusCode::isError, this::raise)
				.toBodilessEntity());
	}

	@Override
	public DownloadJob download(DownloadModelRequest request) {
		log.info("Requesting download of '{}' (quantization={})", request.model(), request.quantization());
		return call(() -> restClient.post()
				.uri(MODELS + "/download")
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::isError, this::raise)
				.body(DownloadJob.class));
	}

	@Override
	public DownloadJob downloadStatus(String jobId) {
		return call(() -> restClient.get()
				.uri(MODELS + "/download/status/{jobId}", jobId)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.onStatus(HttpStatusCode::isError, this::raise)
				.body(DownloadJob.class));
	}

	@Override
	public boolean isReachable() {
		try {
			listModels();
			return true;
		}
		catch (LmStudioException ex) {
			log.debug("LM Studio reachability check failed: {}", ex.getMessage());
			return false;
		}
	}

	/**
	 * Runs a call, turning a connection failure into a diagnosable exception. Everything else is
	 * already translated by {@link #raise}.
	 */
	private <T> T call(Supplier<T> action) {
		try {
			return action.get();
		}
		catch (ResourceAccessException ex) {
			throw LmStudioException.unreachable(baseUrl, ex);
		}
	}

	/**
	 * Translates an HTTP error response into an {@link LmStudioException}, preserving the raw body.
	 */
	private void raise(org.springframework.http.HttpRequest request, ClientHttpResponse response)
			throws IOException {
		String rawBody = readBody(response);
		LmStudioError error = parseError(rawBody);
		String detail = (error != null && error.message() != null) ? error.message() : rawBody;
		throw new LmStudioException(
				"LM Studio returned %s for %s %s: %s".formatted(response.getStatusCode(),
						request.getMethod(), request.getURI().getPath(), detail),
				response.getStatusCode(), error, rawBody, null);
	}

	private String readBody(ClientHttpResponse response) {
		try (InputStream body = response.getBody()) {
			return new String(body.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return "";
		}
	}

	private LmStudioError parseError(String rawBody) {
		if (rawBody == null || rawBody.isBlank()) {
			return null;
		}
		try {
			LmStudioError.Envelope envelope = objectMapper.readValue(rawBody, LmStudioError.Envelope.class);
			return envelope == null ? null : envelope.error();
		}
		catch (RuntimeException ex) {
			log.debug("Could not parse LM Studio error body: {}", rawBody, ex);
			return null;
		}
	}
}
