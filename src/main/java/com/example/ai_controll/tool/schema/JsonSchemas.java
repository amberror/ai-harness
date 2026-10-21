package com.example.ai_controll.tool.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;

/**
 * Compiles and applies JSON Schemas, for tool arguments and for a run's final answer alike.
 *
 * <p>Shared rather than duplicated because one decision here is load-bearing and easy to get wrong
 * twice: the messages are pinned to English rather than the JVM's default locale. They are not for a
 * human reading a log -- they are fed back to the model as the correction it has to act on. The
 * library localises them, so on a machine with a non-English default the model would receive
 * something like {@code необхідна властивість 'expression' не знайдена}, text it is far less able to
 * recover from than the English it saw the schema in. It would also make the journal, and any
 * evaluation asserting on it, depend on where the harness happened to be running.
 */
@Component
public class JsonSchemas {

	private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
			SpecificationVersion.DRAFT_2020_12,
			builder -> builder.schemaRegistryConfig(
					SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build()));

	/** Compiled schemas, keyed by their text. Compilation is not free and schemas repeat every step. */
	private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

	/**
	 * Checks an instance against a schema.
	 *
	 * @return the violations, ordered by path so that the same failure always reads the same way
	 */
	public List<ValidationOutcome.Violation> violations(JsonNode schema, JsonNode instance) {
		Schema compiledSchema = this.compiled.computeIfAbsent(schema.toString(),
				text -> this.registry.getSchema(schema));

		List<ValidationOutcome.Violation> violations = new ArrayList<>();
		for (com.networknt.schema.Error error : compiledSchema.validate(instance)) {
			// The library reports the root as an empty path; "$" reads better in feedback aimed at a
			// model that has to act on it.
			String location = error.getInstanceLocation() == null ? ""
					: error.getInstanceLocation().toString();
			violations.add(new ValidationOutcome.Violation(location.isEmpty() ? "$" : "$" + location,
					error.getMessage(), error.getKeyword()));
		}
		violations.sort(Comparator.comparing(ValidationOutcome.Violation::path));
		return violations;
	}
}
