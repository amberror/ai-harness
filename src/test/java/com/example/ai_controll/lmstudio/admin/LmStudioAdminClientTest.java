package com.example.ai_controll.lmstudio.admin;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.dto.DownloadJob;
import com.example.ai_controll.lmstudio.dto.DownloadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelRequest;
import com.example.ai_controll.lmstudio.dto.LoadModelResponse;
import com.example.ai_controll.lmstudio.dto.ModelInfo;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the raw boundary against recorded LM Studio payloads, so the suite never needs a
 * running model server.
 */
class LmStudioAdminClientTest {

	private static final String BASE_URL = "http://localhost:1234";

	private MockRestServiceServer server;

	private LmStudioAdminClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		HarnessProperties properties = TestProperties.builder().baseUrl(BASE_URL).defaultModel("").build();
		this.client = new LmStudioAdminClient(builder.build(), JsonMapper.builder().build(), properties);
	}

	@Test
	@DisplayName("parses the nested shape of GET /api/v1/models")
	void parsesModelList() {
		server.expect(requestTo(BASE_URL + "/api/v1/models"))
				.andExpect(method(org.springframework.http.HttpMethod.GET))
				.andRespond(withSuccess("""
						{
						  "models": [
						    {
						      "type": "llm",
						      "publisher": "qwen",
						      "key": "qwen/qwen3-4b",
						      "display_name": "Qwen3 4B",
						      "architecture": "qwen3",
						      "quantization": { "name": "Q6_K", "bits_per_weight": 6.0 },
						      "size_bytes": 3200000000,
						      "params_string": "4B",
						      "loaded_instances": [
						        { "id": "qwen/qwen3-4b", "config": { "context_length": 16384, "flash_attention": true } }
						      ],
						      "max_context_length": 32768,
						      "format": "gguf",
						      "capabilities": { "vision": false, "trained_for_tool_use": true },
						      "description": null
						    },
						    {
						      "type": "embedding",
						      "publisher": "nomic",
						      "key": "text-embedding-nomic-embed-text-v1.5",
						      "display_name": "Nomic Embed",
						      "quantization": { "name": "F16", "bits_per_weight": 16.0 },
						      "size_bytes": 274290560,
						      "params_string": null,
						      "loaded_instances": [],
						      "max_context_length": 2048,
						      "format": "gguf"
						    }
						  ]
						}
						""", MediaType.APPLICATION_JSON));

		List<ModelInfo> models = client.listModels();

		assertThat(models).hasSize(2);
		ModelInfo qwen = models.getFirst();
		assertThat(qwen.key()).isEqualTo("qwen/qwen3-4b");
		assertThat(qwen.isLlm()).isTrue();
		assertThat(qwen.supportsToolUse()).isTrue();
		assertThat(qwen.isLoaded()).isTrue();
		assertThat(qwen.quantization().name()).isEqualTo("Q6_K");
		assertThat(qwen.loadedInstances().getFirst().config().contextLength()).isEqualTo(16384);
		assertThat(qwen.maxContextLength()).isEqualTo(32768);

		// The embedding entry has neither capabilities nor an architecture; it must not blow up,
		// and it must not be mistaken for something the harness can drive a loop against.
		ModelInfo embedding = models.get(1);
		assertThat(embedding.isLlm()).isFalse();
		assertThat(embedding.supportsToolUse()).isFalse();
		assertThat(embedding.isLoaded()).isFalse();

		server.verify();
	}

	@Test
	@DisplayName("an empty model list is returned as an empty list, not null")
	void handlesEmptyModelList() {
		server.expect(requestTo(BASE_URL + "/api/v1/models"))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThat(client.listModels()).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("load always asks LM Studio to echo the configuration it actually applied")
	void loadForcesEchoOfResolvedConfig() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/load"))
				.andExpect(method(org.springframework.http.HttpMethod.POST))
				.andExpect(jsonPath("$.model").value("qwen/qwen3-4b"))
				.andExpect(jsonPath("$.context_length").value(16384))
				.andExpect(jsonPath("$.echo_load_config").value(true))
				.andRespond(withSuccess("""
						{
						  "type": "llm",
						  "instance_id": "qwen/qwen3-4b",
						  "load_time_seconds": 4.2,
						  "status": "loaded",
						  "load_config": { "context_length": 16384, "flash_attention": true, "eval_batch_size": 512 }
						}
						""", MediaType.APPLICATION_JSON));

		// Built without echoing, to prove the client turns it on regardless of what the caller asked.
		LoadModelRequest request = new LoadModelRequest("qwen/qwen3-4b", 16384, null, null, null, null, null);
		LoadModelResponse response = client.load(request);

		assertThat(response.instanceId()).isEqualTo("qwen/qwen3-4b");
		assertThat(response.status()).isEqualTo("loaded");
		assertThat(response.loadConfig().contextLength()).isEqualTo(16384);
		server.verify();
	}

	@Test
	@DisplayName("null load options are omitted so LM Studio applies its own defaults")
	void omitsNullLoadOptions() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/load"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers
						.containsString("context_length"))))
				.andRespond(withSuccess("""
						{ "type": "llm", "instance_id": "m", "status": "loaded" }
						""", MediaType.APPLICATION_JSON));

		client.load(LoadModelRequest.of("m"));

		server.verify();
	}

	@Test
	@DisplayName("unload posts the instance id")
	void unloadPostsInstanceId() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/unload"))
				.andExpect(jsonPath("$.instance_id").value("qwen/qwen3-4b"))
				.andRespond(withSuccess("{\"instance_id\":\"qwen/qwen3-4b\"}", MediaType.APPLICATION_JSON));

		client.unload("qwen/qwen3-4b");

		server.verify();
	}

	@Test
	@DisplayName("download status exposes progress and ISO-8601 timestamps")
	void parsesDownloadStatus() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/download/status/job_493c7c9ded"))
				.andRespond(withSuccess("""
						{
						  "job_id": "job_493c7c9ded",
						  "status": "downloading",
						  "bytes_per_second": 10485760,
						  "total_size_bytes": 1000,
						  "downloaded_bytes": 250,
						  "started_at": "2025-10-03T15:33:23.496Z",
						  "estimated_completion": "2025-10-03T15:43:12.102Z"
						}
						""", MediaType.APPLICATION_JSON));

		DownloadJob job = client.downloadStatus("job_493c7c9ded");

		assertThat(job.status()).isEqualTo("downloading");
		assertThat(job.isTerminal()).isFalse();
		assertThat(job.progress()).isEqualTo(0.25d);
		assertThat(job.startedAt()).isNotNull();
		server.verify();
	}

	@Test
	@DisplayName("an already-present model reports no job id and no progress, without dividing by zero")
	void handlesAlreadyDownloaded() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/download"))
				.andRespond(withSuccess("{\"status\":\"already_downloaded\"}", MediaType.APPLICATION_JSON));

		DownloadJob job = client.download(new DownloadModelRequest("qwen/qwen3-4b", null));

		assertThat(job.isAlreadyDownloaded()).isTrue();
		assertThat(job.isTerminal()).isTrue();
		assertThat(job.jobId()).isNull();
		assertThat(job.progress()).isNull();
		server.verify();
	}

	@Test
	@DisplayName("an error response is surfaced with its parsed detail and raw body intact")
	void translatesErrorResponse() {
		server.expect(requestTo(BASE_URL + "/api/v1/models/load"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("""
								{ "error": { "type": "model_not_found", "message": "No model with key 'nope'",
								             "code": "model_not_found", "param": "model" } }
								"""));

		assertThatThrownBy(() -> client.load(LoadModelRequest.of("nope")))
				.isInstanceOf(LmStudioException.class)
				.hasMessageContaining("No model with key")
				.satisfies(thrown -> {
					LmStudioException ex = (LmStudioException) thrown;
					assertThat(ex.isUnreachable()).isFalse();
					assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(ex.getError().type()).isEqualTo("model_not_found");
					assertThat(ex.getError().param()).isEqualTo("model");
					assertThat(ex.getRawBody()).contains("model_not_found");
				});
	}

	@Test
	@DisplayName("an unparseable error body still yields a diagnosable exception")
	void survivesUnparseableErrorBody() {
		server.expect(requestTo(BASE_URL + "/api/v1/models"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.TEXT_HTML)
						.body("<html>Internal Server Error</html>"));

		assertThatThrownBy(() -> client.listModels())
				.isInstanceOf(LmStudioException.class)
				.satisfies(thrown -> {
					LmStudioException ex = (LmStudioException) thrown;
					assertThat(ex.getError()).isNull();
					assertThat(ex.getRawBody()).contains("Internal Server Error");
				});
	}

	@Test
	@DisplayName("isReachable reports false rather than throwing when the server errors")
	void isReachableSwallowsFailures() {
		server.expect(requestTo(BASE_URL + "/api/v1/models"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(""));

		assertThat(client.isReachable()).isFalse();
	}
}
