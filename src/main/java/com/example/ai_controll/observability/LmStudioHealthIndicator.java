package com.example.ai_controll.observability;

import java.util.List;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.dto.ModelInfo;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;

/**
 * Reports whether the model server is answering, and what it currently has resident.
 *
 * <p>Surfacing the loaded instances in the health payload is intentional: "the harness works but
 * nothing is loaded" and "the harness cannot reach LM Studio" are very different problems that look
 * identical from a failing run, and this is the cheapest place to tell them apart.
 */
@Component("lmStudio")
public class LmStudioHealthIndicator implements HealthIndicator {

	private final ModelAdminClient modelAdminClient;

	private final HarnessProperties properties;

	public LmStudioHealthIndicator(ModelAdminClient modelAdminClient, HarnessProperties properties) {
		this.modelAdminClient = modelAdminClient;
		this.properties = properties;
	}

	@Override
	public Health health() {
		String baseUrl = properties.lmStudio().baseUrl();
		try {
			List<ModelInfo> models = modelAdminClient.listModels();
			List<String> loaded = models.stream()
					.filter(ModelInfo::isLoaded)
					.map(ModelInfo::key)
					.toList();

			return Health.up()
					.withDetail("baseUrl", baseUrl)
					.withDetail("modelsAvailable", models.size())
					.withDetail("loadedInstances", loaded)
					.build();
		}
		catch (LmStudioException ex) {
			return Health.down()
					.withDetail("baseUrl", baseUrl)
					.withDetail("reason", ex.isUnreachable() ? "unreachable" : "error")
					.withDetail("message", ex.getMessage())
					.build();
		}
	}
}
