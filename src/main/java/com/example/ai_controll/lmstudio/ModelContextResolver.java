package com.example.ai_controll.lmstudio;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.lmstudio.dto.ModelInfo;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;

/**
 * How large the context window of a given model actually is.
 *
 * <p>This is the one number the compaction cannot guess at, and it is not a property of the model
 * file: LM Studio applies a {@code context_length} at load time, and the same weights can be
 * resident with 4k or with 32k. Only the loaded instance knows, which is why the harness asks the
 * native admin API rather than inferring anything from the OpenAI-compatible endpoint -- that
 * surface does not report it at all.
 *
 * <p>Answers are cached per model key because the loop asks on every step, and forgotten whenever a
 * model is loaded or unloaded through the console, since that is exactly when the answer changes. A
 * server that cannot be reached yields an empty answer rather than an exception: not knowing the
 * window is a reason to skip compaction and say so, not a reason to fail a run.
 */
@Component
public class ModelContextResolver {

	private static final Logger log = LoggerFactory.getLogger(ModelContextResolver.class);

	private final ModelAdminClient admin;

	private final HarnessProperties.Context settings;

	private final Map<String, Integer> cache = new ConcurrentHashMap<>();

	public ModelContextResolver(ModelAdminClient admin, HarnessProperties properties) {
		this.admin = admin;
		this.settings = properties.context();
	}

	/**
	 * The window for a model, in tokens.
	 *
	 * @return empty when the server cannot say and no default is configured, in which case the
	 *         harness sends whatever the conversation has grown to and the server decides
	 */
	public Optional<Integer> contextLengthFor(String modelKey) {
		if (modelKey == null || modelKey.isBlank()) {
			return Optional.ofNullable(this.settings.defaultLengthOrNull());
		}
		Integer cached = this.cache.get(modelKey);
		if (cached != null) {
			return Optional.of(cached);
		}
		Optional<Integer> discovered = lookUp(modelKey);
		discovered.ifPresent(length -> this.cache.put(modelKey, length));
		return discovered.or(() -> Optional.ofNullable(this.settings.defaultLengthOrNull()));
	}

	/** Drops what is remembered about a model, after loading or unloading changes it. */
	public void forget(String modelKey) {
		if (modelKey == null || modelKey.isBlank()) {
			this.cache.clear();
			return;
		}
		this.cache.remove(modelKey);
	}

	private Optional<Integer> lookUp(String modelKey) {
		try {
			return this.admin.listModels().stream()
					.filter(model -> modelKey.equals(model.key()))
					.findFirst()
					.flatMap(ModelContextResolver::windowOf);
		}
		catch (RuntimeException ex) {
			// Not knowing is a normal state -- the server may simply not be running yet.
			log.debug("Could not read the context length for '{}': {}", modelKey, ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * The window a model is actually running with.
	 *
	 * <p>The loaded instance's configuration wins over the model's maximum. A 32k-capable model
	 * loaded at 4k would otherwise be compacted against a window eight times larger than it has,
	 * which is the same silent overflow the compaction exists to prevent.
	 */
	private static Optional<Integer> windowOf(ModelInfo model) {
		return model.loadedInstances().stream()
				.map(ModelInfo.LoadedInstance::config)
				.filter(config -> config != null && config.contextLength() != null
						&& config.contextLength() > 0)
				.map(ModelInfo.InstanceConfig::contextLength)
				.findFirst()
				.or(() -> Optional.ofNullable(model.maxContextLength()).filter(length -> length > 0));
	}
}
