package com.example.ai_controll.orchestrator.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.tool.schema.JsonSchemas;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holding the final answer to a contract, the way tool arguments already are.
 *
 * <p>The feedback matters as much as the verdict: it goes back into the conversation, and a small
 * model acts on a concrete instruction far more reliably than on a complaint.
 */
class ResponseValidatorTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final ResponseValidator validator = new ResponseValidator(mapper, new JsonSchemas());

	private JsonNode schema() {
		return mapper.readTree("""
				{"type":"object",
				 "properties":{"answer":{"type":"number"},"unit":{"type":"string"}},
				 "required":["answer"]}
				""");
	}

	@Test
	@DisplayName("a conforming answer passes and is handed back parsed")
	void acceptsValidAnswer() {
		ResponseValidator.Result result = validator.validate("{\"answer\": 42}", schema());

		assertThat(result.valid()).isTrue();
		// Parsed once here rather than again by the caller: two parses are two chances to disagree.
		assertThat(result.value().get("answer").asInt()).isEqualTo(42);
	}

	@Test
	@DisplayName("a fenced answer is accepted, because the model did produce the object")
	void unwrapsCodeFences() {
		// Told to answer in JSON, a small model wraps it in Markdown more often than not. Failing
		// that would measure the model's formatting habits rather than whether it got the answer.
		String fenced = "```json\n{\"answer\": 7, \"unit\": \"kg\"}\n```";

		assertThat(validator.validate(fenced, schema()).valid()).isTrue();
	}

	@Test
	@DisplayName("a missing required field names the field")
	void reportsMissingField() {
		ResponseValidator.Result result = validator.validate("{\"unit\":\"kg\"}", schema());

		assertThat(result.valid()).isFalse();
		assertThat(result.reason()).isEqualTo("schema_violation");
		assertThat(result.feedback().get("message").stringValue()).contains("answer");
		// The schema goes back with the correction: the model has to be able to see what it missed.
		assertThat(result.feedback().get("expected_schema")).isNotNull();
	}

	@Test
	@DisplayName("a wrong type is rejected rather than coerced")
	void rejectsWrongType() {
		// Quietly accepting "42" as a number would destroy the measurement: the harness exists to
		// report what the model did, not to make it look better than it was.
		ResponseValidator.Result result = validator.validate("{\"answer\":\"42\"}", schema());

		assertThat(result.valid()).isFalse();
	}

	@Test
	@DisplayName("prose instead of JSON gets a correction it can act on")
	void rejectsProse() {
		ResponseValidator.Result result = validator.validate("The answer is 42, roughly.", schema());

		assertThat(result.valid()).isFalse();
		assertThat(result.reason()).isEqualTo("malformed_json");
		assertThat(result.feedback().get("hint").stringValue()).contains("JSON object");
		// Quoting it back is what makes the correction actionable.
		assertThat(result.feedback().get("received").stringValue()).contains("The answer is 42");
	}

	@Test
	@DisplayName("an empty answer is named as such")
	void rejectsEmptyAnswer() {
		// A distinct reason from malformed JSON: this is the signature of an allowance spent entirely
		// on reasoning, and the remedy is different.
		assertThat(validator.validate("", schema()).reason()).isEqualTo("empty_answer");
		assertThat(validator.validate(null, schema()).reason()).isEqualTo("empty_answer");
	}

	@Test
	@DisplayName("a fence with no language tag is unwrapped too")
	void unwrapsBareFences() {
		assertThat(ResponseValidator.unwrap("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
		assertThat(ResponseValidator.unwrap("{\"a\":1}")).isEqualTo("{\"a\":1}");
		assertThat(ResponseValidator.unwrap(null)).isEmpty();
	}
}
