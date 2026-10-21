package com.example.ai_controll.tool.exec.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.tool.exec.FileAccessPolicy;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in tools, and the properties that make them useful inside a measuring instrument.
 *
 * <p>Two things are asserted throughout. First, the answers are <em>exactly</em> right, because a
 * scenario asserting on a tool's output is only meaningful if the tool is not the thing that is
 * wrong. Second, bad arguments produce a <em>failure the model can act on</em> rather than an
 * exception: a tool that throws ends a run, while a tool that explains itself gets corrected on the
 * next turn.
 */
class BuiltinToolsTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	private JsonNode args(String json) {
		return mapper.readTree(json);
	}

	private JsonNode outputOf(ToolOutcome outcome) {
		return mapper.readTree(outcome.output());
	}

	private ToolInvocation invocation() {
		return new ToolInvocation(UUID.randomUUID(), 1, "call-1", null, null);
	}

	@Nested
	@DisplayName("text_stats")
	class TextStats {

		private final TextStatsTool tool = new TextStatsTool(mapper);

		@Test
		@DisplayName("counts what it says it counts")
		void counts() {
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"text\":\"One two three.\\nFour five!\"}")));

			assertThat(out.get("words").asInt()).isEqualTo(5);
			assertThat(out.get("lines").asInt()).isEqualTo(2);
			assertThat(out.get("sentences").asInt()).isEqualTo(2);
			// "One two three." is 14, the newline is 1, "Four five!" is 10.
			assertThat(out.get("characters").asInt()).isEqualTo(25);
		}

		@Test
		@DisplayName("an empty string is zero of everything")
		void handlesEmptyText() {
			// The easiest case to check by hand, so it is the one a wrong answer is most visible in.
			JsonNode out = outputOf(tool.run(invocation(), args("{\"text\":\"\"}")));

			assertThat(out.get("words").asInt()).isZero();
			assertThat(out.get("lines").asInt()).isZero();
			assertThat(out.get("sentences").asInt()).isZero();
		}

		@Test
		@DisplayName("text with no final full stop still ends a sentence")
		void countsUnterminatedSentence() {
			JsonNode out = outputOf(tool.run(invocation(), args("{\"text\":\"no full stop here\"}")));

			assertThat(out.get("sentences").asInt()).isEqualTo(1);
		}

		@Test
		@DisplayName("a missing argument is a failure the model can read")
		void rejectsMissingText() {
			ToolOutcome outcome = tool.run(invocation(), args("{}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.output()).contains("'text' is required");
		}
	}

	@Nested
	@DisplayName("stats_summary")
	class StatsSummary {

		private final StatsSummaryTool tool = new StatsSummaryTool(mapper);

		@Test
		@DisplayName("computes the summary exactly")
		void summarises() {
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"numbers\":[4, 8, 15, 16, 23, 42]}")));

			assertThat(out.get("count").asInt()).isEqualTo(6);
			assertThat(out.get("sum").asDouble()).isEqualTo(108d);
			assertThat(out.get("mean").asDouble()).isEqualTo(18d);
			// Even count: the median is the mean of the two middle values, not either of them.
			assertThat(out.get("median").asDouble()).isEqualTo(15.5d);
			assertThat(out.get("min").asDouble()).isEqualTo(4d);
			assertThat(out.get("max").asDouble()).isEqualTo(42d);
		}

		@Test
		@DisplayName("both standard deviations are named, because the word means two things")
		void reportsBothDeviations() {
			JsonNode out = outputOf(tool.run(invocation(), args("{\"numbers\":[2, 4, 4, 4, 5, 5, 7, 9]}")));

			assertThat(out.get("stdDevPopulation").asDouble()).isEqualTo(2d);
			assertThat(out.get("stdDevSample").asDouble()).isCloseTo(2.13809d,
					org.assertj.core.data.Offset.offset(0.0001d));
		}

		@Test
		@DisplayName("a single number is accepted where a list was asked for")
		void acceptsABareNumber() {
			// Asked for a list of one, a small model very often sends the bare value. Failing that
			// would measure its JSON habits rather than its reasoning.
			ToolOutcome outcome = tool.run(invocation(), args("{\"numbers\": 7}"));

			assertThat(outcome.succeeded()).isTrue();
			assertThat(outputOf(outcome).get("mean").asDouble()).isEqualTo(7d);
		}

		@Test
		@DisplayName("quoted numbers are read rather than refused")
		void acceptsQuotedNumbers() {
			ToolOutcome outcome = tool.run(invocation(), args("{\"numbers\":[\"1\",\"2\",\"3\"]}"));

			assertThat(outputOf(outcome).get("sum").asDouble()).isEqualTo(6d);
		}

		@Test
		@DisplayName("something that is not a number at all is refused with an example")
		void rejectsNonNumbers() {
			ToolOutcome outcome = tool.run(invocation(), args("{\"numbers\":[\"apples\"]}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.output()).contains("[1, 2, 3]");
		}
	}

	@Nested
	@DisplayName("unit_convert")
	class UnitConvert {

		private final UnitConvertTool tool = new UnitConvertTool(mapper);

		@Test
		@DisplayName("converts within a kind")
		void convertsLength() {
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"value\":3,\"from\":\"m\",\"to\":\"cm\"}")));

			assertThat(out.get("result").asDouble()).isEqualTo(300d);
			assertThat(out.get("kind").asString()).isEqualTo("length");
		}

		@Test
		@DisplayName("temperature is affine, not a scale factor")
		void convertsTemperature() {
			// The case a multiplicative table gets wrong: 0 degrees is not zero of anything.
			assertThat(outputOf(tool.run(invocation(),
					args("{\"value\":0,\"from\":\"c\",\"to\":\"f\"}"))).get("result").asDouble())
					.isEqualTo(32d);
			assertThat(outputOf(tool.run(invocation(),
					args("{\"value\":98.6,\"from\":\"f\",\"to\":\"c\"}"))).get("result").asDouble())
					.isCloseTo(37d, org.assertj.core.data.Offset.offset(0.0001d));
		}

		@Test
		@DisplayName("converting between kinds is refused in plain words")
		void refusesMismatchedKinds() {
			ToolOutcome outcome = tool.run(invocation(),
					args("{\"value\":5,\"from\":\"kg\",\"to\":\"m\"}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.output()).contains("measure different things");
		}

		@Test
		@DisplayName("an invented unit is refused with the real ones listed")
		void refusesUnknownUnits() {
			ToolOutcome outcome = tool.run(invocation(),
					args("{\"value\":5,\"from\":\"parsecs\",\"to\":\"m\"}"));

			assertThat(outcome.succeeded()).isFalse();
			// Naming what exists is what lets the model fix the call rather than repeat it.
			assertThat(outcome.output()).contains("Known units");
		}
	}

	@Nested
	@DisplayName("date_diff and date_shift")
	class Dates {

		private final DateDiffTool diff = new DateDiffTool(mapper);

		private final DateShiftTool shift = new DateShiftTool(mapper);

		@Test
		@DisplayName("counts days across a leap year correctly")
		void countsAcrossLeapYear() {
			// 2028 is a leap year: February has 29 days, which is exactly the sum a model gets wrong.
			JsonNode out = outputOf(diff.run(invocation(),
					args("{\"from\":\"2028-01-01\",\"to\":\"2028-03-01\",\"unit\":\"days\"}")));

			assertThat(out.get("difference").asDouble()).isEqualTo(60d);
		}

		@Test
		@DisplayName("a backwards difference is negative rather than silently reordered")
		void reportsDirection() {
			JsonNode out = outputOf(diff.run(invocation(),
					args("{\"from\":\"2026-03-01\",\"to\":\"2026-02-01\",\"unit\":\"days\"}")));

			// Swapping the arguments would hide a mistake the model made, which is the opposite of
			// what this harness is for.
			assertThat(out.get("difference").asDouble()).isEqualTo(-28d);
		}

		@Test
		@DisplayName("the date formats a model actually emits are all accepted")
		void acceptsCommonFormats() {
			for (String date : List.of("2026-03-01", "2026-03-01T09:30", "2026-03-01 09:30:00",
					"2026-03-01T09:30:00Z")) {
				assertThat(diff.run(invocation(),
						args("{\"from\":\"%s\",\"to\":\"2026-03-02\"}".formatted(date))).succeeded())
						.as(date)
						.isTrue();
			}
		}

		@Test
		@DisplayName("an unreadable date names the formats that work")
		void rejectsBadDates() {
			ToolOutcome outcome = diff.run(invocation(),
					args("{\"from\":\"last Tuesday\",\"to\":\"2026-03-01\"}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.output()).contains("Accepted formats");
		}

		@Test
		@DisplayName("shifting a plain date answers with a plain date")
		void shiftsForwards() {
			JsonNode out = outputOf(shift.run(invocation(),
					args("{\"date\":\"2026-03-01\",\"amount\":90,\"unit\":\"days\"}")));

			assertThat(out.get("resultDate").asString()).isEqualTo("2026-05-30");
			assertThat(out.get("dayOfWeek").asString()).isEqualTo("SATURDAY");
		}

		@Test
		@DisplayName("a negative amount goes backwards")
		void shiftsBackwards() {
			JsonNode out = outputOf(shift.run(invocation(),
					args("{\"date\":\"2026-03-01\",\"amount\":-1,\"unit\":\"days\"}")));

			assertThat(out.get("resultDate").asString()).isEqualTo("2026-02-28");
		}
	}

	@Nested
	@DisplayName("json_extract")
	class JsonExtract {

		private final JsonExtractTool tool = new JsonExtractTool(mapper);

		@Test
		@DisplayName("pulls a value out of another tool's answer")
		void extractsFromAString() {
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"document\":\"{\\\"result\\\":16537907}\",\"pointer\":\"/result\"}")));

			assertThat(out.get("found").asBoolean()).isTrue();
			assertThat(out.get("value").asLong()).isEqualTo(16537907L);
		}

		@Test
		@DisplayName("a nested object argument works as well as a string")
		void extractsFromAnObject() {
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"document\":{\"items\":[{\"name\":\"first\"}]},\"pointer\":\"/items/0/name\"}")));

			assertThat(out.get("value").asString()).isEqualTo("first");
		}

		@Test
		@DisplayName("the pointer spellings a model produces all work")
		void normalisesPointers() {
			for (String pointer : List.of("/result", "result", "$.result")) {
				JsonNode out = outputOf(tool.run(invocation(),
						args("{\"document\":{\"result\":42},\"pointer\":\"%s\"}".formatted(pointer))));
				assertThat(out.get("value").asInt()).as(pointer).isEqualTo(42);
			}
		}

		@Test
		@DisplayName("a pointer to nothing succeeds and says what is there instead")
		void reportsMissingPaths() {
			// Not a failure: the question was reasonable and the answer is "nothing". Calling it an
			// error invites the model to retry the identical call.
			ToolOutcome outcome = tool.run(invocation(),
					args("{\"document\":{\"result\":42},\"pointer\":\"/answer\"}"));

			assertThat(outcome.succeeded()).isTrue();
			JsonNode out = outputOf(outcome);
			assertThat(out.get("found").asBoolean()).isFalse();
			assertThat(out.get("availableKeys").toString()).contains("result");
		}

		@Test
		@DisplayName("a document that is not JSON is refused with the parser's reason")
		void rejectsNonJson() {
			ToolOutcome outcome = tool.run(invocation(),
					args("{\"document\":\"not json\",\"pointer\":\"/a\"}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.errorKind()).isEqualTo("invalid_json");
		}
	}

	@Nested
	@DisplayName("encode_text")
	class EncodeText {

		private final EncodeTextTool tool = new EncodeTextTool(mapper);

		@Test
		@DisplayName("hashes and encodings match the published values")
		void encodes() {
			assertThat(outputOf(tool.run(invocation(),
					args("{\"text\":\"abc\",\"algorithm\":\"sha256\"}"))).get("result").asString())
					.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
			assertThat(outputOf(tool.run(invocation(),
					args("{\"text\":\"hello\",\"algorithm\":\"base64\"}"))).get("result").asString())
					.isEqualTo("aGVsbG8=");
		}

		@Test
		@DisplayName("a round trip returns the original")
		void roundTrips() {
			String encoded = outputOf(tool.run(invocation(),
					args("{\"text\":\"hello world\",\"algorithm\":\"base64\"}"))).get("result").asString();
			JsonNode decoded = outputOf(tool.run(invocation(),
					args("{\"text\":\"%s\",\"algorithm\":\"base64_decode\"}".formatted(encoded))));

			assertThat(decoded.get("result").asString()).isEqualTo("hello world");
		}

		@Test
		@DisplayName("the input length is reported, so a trimmed string can be caught")
		void reportsInputLength() {
			// The failure this tool exists to expose: a model that quietly normalises the string on
			// the way into the call produces a different hash and no other sign of the damage.
			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"text\":\" padded \",\"algorithm\":\"sha256\"}")));

			assertThat(out.get("inputLength").asInt()).isEqualTo(8);
		}

		@Test
		@DisplayName("undecodable input fails without ending the run")
		void rejectsBadInput() {
			ToolOutcome outcome = tool.run(invocation(),
					args("{\"text\":\"!!!not base64!!!\",\"algorithm\":\"base64_decode\"}"));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.errorKind()).isEqualTo("decode_failed");
		}
	}

	@Nested
	@DisplayName("file_read")
	class FileRead {

		@Test
		@DisplayName("refuses everything until a directory is allowed")
		void deniesByDefault(@TempDir Path directory) throws Exception {
			Path file = Files.writeString(directory.resolve("notes.txt"), "hello");
			FileReadTool tool = new FileReadTool(
					new FileAccessPolicy(TestProperties.defaults()), mapper);

			ToolOutcome outcome = tool.run(invocation(),
					args("{\"path\":\"%s\"}".formatted(escape(file))));

			// Ships inert. The model chooses the path, so the default has to be "nothing".
			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.output()).contains("No directories are readable");
		}

		@Test
		@DisplayName("reads a file inside an allowed directory")
		void readsAllowedFile(@TempDir Path directory) throws Exception {
			Path file = Files.writeString(directory.resolve("notes.txt"), "hello from disk");
			FileReadTool tool = allowing(directory);

			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"path\":\"%s\"}".formatted(escape(file)))));

			assertThat(out.get("content").asString()).isEqualTo("hello from disk");
			assertThat(out.get("truncated").asBoolean()).isFalse();
		}

		@Test
		@DisplayName("a path climbing out of the allowed directory is refused")
		void refusesTraversal(@TempDir Path directory) throws Exception {
			Path outside = Files.writeString(directory.resolve("secret.txt"), "private");
			Path allowed = Files.createDirectory(directory.resolve("public"));
			FileReadTool tool = allowing(allowed);

			// The check is on the resolved real path, so '..' cannot walk out of the allowlist.
			ToolOutcome outcome = tool.run(invocation(), args("{\"path\":\"%s\"}"
					.formatted(escape(allowed.resolve("..").resolve("secret.txt")))));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.errorKind()).isEqualTo("access_denied");
			assertThat(outcome.output()).doesNotContain("private");
			assertThat(outside).exists();
		}

		@Test
		@DisplayName("truncation is reported rather than silent")
		void truncatesExplicitly(@TempDir Path directory) throws Exception {
			Path file = Files.writeString(directory.resolve("long.txt"), "x".repeat(500));
			FileReadTool tool = allowing(directory);

			JsonNode out = outputOf(tool.run(invocation(),
					args("{\"path\":\"%s\",\"maxChars\":100}".formatted(escape(file)))));

			// A model that cannot tell it received part of a file will answer from the part it got.
			assertThat(out.get("content").asString()).hasSize(100);
			assertThat(out.get("truncated").asBoolean()).isTrue();
			assertThat(out.get("characters").asInt()).isEqualTo(500);
		}

		@Test
		@DisplayName("a directory is not a file")
		void refusesDirectories(@TempDir Path directory) {
			ToolOutcome outcome = allowing(directory).run(invocation(),
					args("{\"path\":\"%s\"}".formatted(escape(directory))));

			assertThat(outcome.succeeded()).isFalse();
			assertThat(outcome.errorKind()).isEqualTo("not_a_file");
		}

		private FileReadTool allowing(Path directory) {
			return new FileReadTool(new FileAccessPolicy(TestProperties.builder()
					.allowedDirectories(List.of(directory.toString())).build()), mapper);
		}

		/** Windows paths are full of backslashes, and these go into a JSON string. */
		private String escape(Path path) {
			return path.toString().replace("\\", "\\\\");
		}
	}
}
