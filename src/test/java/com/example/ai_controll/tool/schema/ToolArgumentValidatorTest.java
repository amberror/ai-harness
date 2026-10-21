package com.example.ai_controll.tool.schema;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.conversation.ToolCallRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that turns a raw model into a predictable one.
 *
 * <p>The assertions care as much about the <em>content of the rejection</em> as about the verdict:
 * the feedback goes straight back to the model, so a correction that does not name the offending
 * field or the tools that do exist is of no use to a 4B model trying to recover.
 */
class ToolArgumentValidatorTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final ToolArgumentValidator validator =
			new ToolArgumentValidator(mapper, new ArgumentCoercer(mapper), new JsonSchemas());

	private final ToolSchema weather = tool("get_weather", """
			{"type":"object",
			 "properties":{"city":{"type":"string"},"days":{"type":"integer"}},
			 "required":["city"],
			 "additionalProperties":false}""");

	private final ToolSchema clock = tool("clock_now", """
			{"type":"object","properties":{},"additionalProperties":false}""");

	private final List<ToolSchema> available = List.of(weather, clock);

	private ToolSchema tool(String name, String schema) {
		return new ToolSchema(name, "Test tool " + name, mapper.readTree(schema));
	}

	private ToolCallRequest call(String name, String args) {
		return new ToolCallRequest("call_1", name, args);
	}

	@Nested
	@DisplayName("valid calls")
	class ValidCalls {

		@Test
		@DisplayName("well-formed arguments pass and are returned parsed")
		void acceptsValidArguments() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\"}"), available, false);

			assertThat(outcome.isValid()).isTrue();
			assertThat(outcome.arguments().get("city").asString()).isEqualTo("Kyiv");
			assertThat(outcome.violations()).isEmpty();
		}

		@Test
		@DisplayName("a zero-argument tool may be called with empty arguments")
		void acceptsEmptyArgumentsForZeroArgTool() {
			// Models emit "", "{}" or nothing at all for a tool that takes no arguments. None of these
			// is a defect, and treating them as one would make the simplest possible tool unusable.
			assertThat(validator.validate(call("clock_now", ""), available, false).isValid()).isTrue();
			assertThat(validator.validate(call("clock_now", "{}"), available, false).isValid()).isTrue();
			assertThat(validator.validate(call("clock_now", null), available, false).isValid()).isTrue();
		}
	}

	@Nested
	@DisplayName("unknown tool names")
	class UnknownTools {

		@Test
		@DisplayName("a hallucinated name is rejected and the real names are listed")
		void rejectsUnknownToolAndListsAvailable() {
			ValidationOutcome outcome = validator.validate(
					call("web_search", "{\"q\":\"x\"}"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.UNKNOWN_TOOL);

			JsonNode feedback = outcome.feedback();
			assertThat(feedback.get("error").asString()).isEqualTo("unknown_tool");
			// Listing what does exist is what lets the model recover on the next turn.
			assertThat(feedback.get("available").toString()).contains("get_weather").contains("clock_now");
		}

		@Test
		@DisplayName("a near-miss gets a did-you-mean suggestion")
		void suggestsClosestName() {
			ValidationOutcome outcome = validator.validate(
					call("get_wether", "{\"city\":\"Kyiv\"}"), available, false);

			assertThat(outcome.feedback().get("did_you_mean").asString()).isEqualTo("get_weather");
			assertThat(outcome.feedback().get("message").asString()).contains("Did you mean");
		}

		@Test
		@DisplayName("a name nothing like the real ones gets no suggestion")
		void offersNoSuggestionWhenNothingIsClose() {
			// A wrong suggestion is worse than none: it sends the model confidently in a new wrong
			// direction rather than back to the list.
			ValidationOutcome outcome = validator.validate(
					call("send_email_to_everyone", "{}"), available, false);

			assertThat(outcome.feedback().has("did_you_mean")).isFalse();
			assertThat(outcome.feedback().get("message").asString()).contains("listed in 'available'");
		}

		@Test
		@DisplayName("a tool the model was not shown counts as unknown even if it exists elsewhere")
		void scopesToWhatWasOffered() {
			// Availability is per-step. A tool in the registry but absent from this step's toolset was
			// never shown to the model, so accepting it would mean honouring a call the model could
			// only have guessed at.
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\"}"), List.of(clock), false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.UNKNOWN_TOOL);
		}
	}

	@Nested
	@DisplayName("schema violations")
	class SchemaViolations {

		@Test
		@DisplayName("a missing required field is named with its path")
		void reportsMissingRequiredField() {
			ValidationOutcome outcome = validator.validate(call("get_weather", "{}"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.SCHEMA_VIOLATION);
			assertThat(outcome.violations()).isNotEmpty();
			assertThat(outcome.feedback().get("error").asString()).isEqualTo("invalid_arguments");
			// Restating the schema costs tokens but consistently pays for itself with a small model.
			assertThat(outcome.feedback().has("expected_schema")).isTrue();
			assertThat(outcome.feedback().toString()).contains("city");
		}

		@Test
		@DisplayName("violation messages are English regardless of the machine's locale")
		void violationMessagesAreAlwaysEnglish() {
			// The library localises its messages to the JVM default. These messages are not for a human
			// reading a log -- they go back to the model as the correction it must act on -- so on a
			// non-English machine the model would be handed text it is far less able to recover from,
			// and the journal would differ depending on where the harness ran.
			Locale original = Locale.getDefault();
			try {
				Locale.setDefault(Locale.of("uk", "UA"));
				ValidationOutcome outcome = validator.validate(call("get_weather", "{}"), available, false);

				String message = outcome.violations().getFirst().message();
				assertThat(message).containsIgnoringCase("required");
				// No Cyrillic: the message must not have been localised.
				assertThat(message).matches("\\A\\p{ASCII}*\\z");
			}
			finally {
				Locale.setDefault(original);
			}
		}

		@Test
		@DisplayName("a wrong type is rejected in strict mode")
		void rejectsWrongTypeWhenStrict() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\",\"days\":\"3\"}"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.SCHEMA_VIOLATION);
			assertThat(outcome.violations()).anySatisfy(
					violation -> assertThat(violation.path()).contains("days"));
		}

		@Test
		@DisplayName("an unexpected extra field is rejected when the schema forbids it")
		void rejectsAdditionalProperties() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\",\"units\":\"metric\"}"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.SCHEMA_VIOLATION);
		}
	}

	@Nested
	@DisplayName("malformed JSON")
	class MalformedJson {

		@Test
		@DisplayName("unparseable arguments are rejected and quoted back")
		void rejectsMalformedJson() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{city: Kyiv"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.MALFORMED_JSON);
			// Showing the model its own broken text is what makes the correction actionable.
			assertThat(outcome.feedback().get("received").asString()).isEqualTo("{city: Kyiv");
			assertThat(outcome.feedback().has("expected_schema")).isTrue();
		}
	}

	@Nested
	@DisplayName("strict versus lenient")
	class StrictVersusLenient {

		@Test
		@DisplayName("strict mode does not repair a quoted number")
		void strictRefusesToCoerce() {
			// The point of strict mode: this is a real failure of the model to follow the schema, and
			// silently fixing it would make the harness unable to report how often that happens.
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\",\"days\":\"3\"}"), available, false);

			assertThat(outcome.isValid()).isFalse();
		}

		@Test
		@DisplayName("lenient mode coerces a quoted number to the declared type")
		void lenientCoercesQuotedNumber() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "{\"city\":\"Kyiv\",\"days\":\"3\"}"), available, true);

			assertThat(outcome.isValid()).isTrue();
			assertThat(outcome.arguments().get("days").asInt()).isEqualTo(3);
		}

		@Test
		@DisplayName("lenient mode repairs a markdown-fenced argument blob")
		void lenientStripsMarkdownFence() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "```json\n{\"city\":\"Kyiv\"}\n```"), available, true);

			assertThat(outcome.isValid()).isTrue();
			assertThat(outcome.arguments().get("city").asString()).isEqualTo("Kyiv");
		}

		@Test
		@DisplayName("strict mode rejects that same fenced blob")
		void strictRejectsMarkdownFence() {
			ValidationOutcome outcome = validator.validate(
					call("get_weather", "```json\n{\"city\":\"Kyiv\"}\n```"), available, false);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.MALFORMED_JSON);
		}

		@Test
		@DisplayName("lenient mode still rejects what it cannot honestly repair")
		void lenientDoesNotInventValues() {
			// Leniency fixes syntax, never semantics. A missing required field has no correct value
			// for the harness to supply, so it must go back to the model.
			ValidationOutcome outcome = validator.validate(call("get_weather", "{}"), available, true);

			assertThat(outcome.status()).isEqualTo(ValidationOutcome.Status.SCHEMA_VIOLATION);
		}
	}
}
