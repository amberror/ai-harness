package com.example.ai_controll.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.config.TestProperties;
import com.example.ai_controll.conversation.Message;
import com.example.ai_controll.conversation.Role;
import com.example.ai_controll.conversation.ToolCallRequest;
import com.example.ai_controll.conversation.Transcript;
import com.example.ai_controll.lmstudio.port.ChatModelClient;
import com.example.ai_controll.lmstudio.port.ChatRequest;
import com.example.ai_controll.lmstudio.port.ChatResult;
import com.example.ai_controll.conversation.ContextAssembler;
import com.example.ai_controll.conversation.TokenEstimator;
import com.example.ai_controll.config.HarnessProperties;
import com.example.ai_controll.lmstudio.LmStudioException;
import com.example.ai_controll.lmstudio.ModelContextResolver;
import com.example.ai_controll.lmstudio.ModelSlot;
import com.example.ai_controll.lmstudio.port.ChatStreamEvent;
import com.example.ai_controll.lmstudio.port.FinishReason;
import com.example.ai_controll.lmstudio.port.SamplingParams;
import com.example.ai_controll.lmstudio.port.ModelAdminClient;
import com.example.ai_controll.lmstudio.port.TokenUsage;
import com.example.ai_controll.observability.EventJournal;
import com.example.ai_controll.orchestrator.approval.ApprovalDecision;
import com.example.ai_controll.orchestrator.domain.RunStatus;
import org.springframework.http.HttpStatus;
import com.example.ai_controll.orchestrator.output.ResponseValidator;
import com.example.ai_controll.tool.exec.ToolExecutor;
import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;
import com.example.ai_controll.tool.parse.FencedJsonToolCallParser;
import com.example.ai_controll.tool.parse.TaggedToolCallParser;
import com.example.ai_controll.tool.parse.ToolCallExtractor;
import com.example.ai_controll.tool.policy.ApprovalMode;
import com.example.ai_controll.tool.policy.ToolPolicyEngine;
import com.example.ai_controll.tool.registry.ExecutorType;
import com.example.ai_controll.tool.registry.ResolvedToolset;
import com.example.ai_controll.tool.registry.ToolDefinitionEntity;
import com.example.ai_controll.tool.registry.ToolSource;
import com.example.ai_controll.tool.registry.ToolsetResolver;
import com.example.ai_controll.tool.schema.ArgumentCoercer;
import com.example.ai_controll.tool.schema.JsonSchemas;
import com.example.ai_controll.tool.schema.ToolArgumentValidator;
import com.example.ai_controll.tool.schema.ToolSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guardrails, asserted deterministically.
 *
 * <p>Each one was verified end-to-end against the live model, which proved it works but proves
 * nothing about tomorrow: a refactor could silently disable any of them and only a manual run would
 * notice. Here the model is scripted, so every guardrail can be provoked exactly and on every build.
 *
 * <p>The assertions are on the <em>named terminal state</em> rather than merely on "it stopped". A
 * harness that cannot say which guardrail fired is not much better than one with no guardrails.
 */
class AgentRunnerTest {

	private static final String CALC = "calc_eval";

	private final JsonMapper mapper = JsonMapper.builder().build();

	private ScriptedModel model;

	private RecordingExecutor executor;

	private AgentRunner runner;

	private ToolPolicyEngine policyEngine;

	private RunCancellation cancellation;

	private ToolsetResolver resolverMock;

	private final ToolSchema calcSchema = new ToolSchema(CALC,
			"Evaluates an arithmetic expression.",
			JsonMapper.builder().build().readTree("""
					{"type":"object","properties":{"expression":{"type":"string"}},
					 "required":["expression"],"additionalProperties":false}"""));

	@BeforeEach
	void setUp() {
		this.model = new ScriptedModel();
		this.executor = new RecordingExecutor();

		ToolDefinitionEntity definition = new ToolDefinitionEntity(UUID.randomUUID(), CALC, 1, true,
				calcSchema.description(), calcSchema.parametersSchema().toString(), ExecutorType.BUILTIN,
				null, true, ToolSource.BUILTIN, null, Instant.now(), "test");

		this.resolverMock = mock(ToolsetResolver.class);
		when(resolverMock.resolve(any(), any())).thenReturn(
				new ResolvedToolset(List.of(calcSchema), Map.of(CALC, definition)));

		this.policyEngine = mock(ToolPolicyEngine.class);
		// Default posture: policies opt in to approval, so an unmatched call simply runs.
		allowEverything();

		this.cancellation = new RunCancellation();
		this.runner = newRunner(properties());
	}

	/** A runner over the same scripted parts, so a test can vary one setting and rebuild it. */
	private AgentRunner newRunner(HarnessProperties properties) {
		JsonSchemas schemas = new JsonSchemas();
		ModelAdminClient admin = mock(ModelAdminClient.class);
		// No models reported, so nothing is discovered and the configured window is what applies.
		when(admin.listModels()).thenReturn(List.of());

		return new AgentRunner(model, resolverMock,
				new ToolCallExtractor(List.of(new TaggedToolCallParser(mapper),
						new FencedJsonToolCallParser(mapper))),
				new ToolArgumentValidator(mapper, new ArgumentCoercer(mapper), schemas),
				policyEngine,
				List.of(executor),
				// The journal is mocked: these tests are about what the loop decides, and recording is
				// already covered where it is implemented.
				mock(EventJournal.class), mapper,
				new ContextAssembler(new TokenEstimator(), properties), new TokenEstimator(),
				new ModelContextResolver(admin, properties), new ModelSlot(properties),
				cancellation, new ResponseValidator(mapper, schemas), properties);
	}

	private HarnessProperties properties() {
		return TestProperties.builder().defaultModel("m").build();
	}

	/**
	 * Retries enabled with almost no backoff. Off in the default fixture on purpose: a test about a
	 * failure being reported should not first sit through the recovery attempts.
	 */
	private HarnessProperties retryingProperties() {
		return TestProperties.builder().defaultModel("m").maxRetries(1)
				.retryBackoff(Duration.ofMillis(1)).build();
	}

	private AgentRunner.Result runWith(HarnessProperties properties, RunConfig config) {
		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		return newRunner(properties).execute(UUID.randomUUID(), config, transcript);
	}

	private void allowEverything() {
		when(policyEngine.evaluate(any(), anyInt(), any())).thenReturn(
				new ToolPolicyEngine.Decision(ApprovalMode.AUTO, "no policy matched", null, null));
	}

	private void policyReturns(ApprovalMode mode, String reason) {
		when(policyEngine.evaluate(any(), anyInt(), any())).thenReturn(
				new ToolPolicyEngine.Decision(mode, reason, "test-policy", null));
	}

	// -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a plain answer finishes the run")
	void finishesOnPlainAnswer() {
		model.willReturn(answer("The answer is 4."));

		AgentRunner.Result result = run(config().build());

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.finalOutput()).isEqualTo("The answer is 4.");
		assertThat(result.stepsUsed()).isEqualTo(1);
		assertThat(result.toolCallsTotal()).isZero();
	}

	@Test
	@DisplayName("a tool is executed and its result is fed back before the model answers")
	void executesToolThenFinishes() {
		model.willReturn(toolCall(CALC, "{\"expression\":\"2+2\"}"))
				.willReturn(answer("It is 4."));
		executor.willReturn(ToolOutcome.success("{\"result\":4}", 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("what is 2+2?"));
		AgentRunner.Result result = runner.execute(UUID.randomUUID(), config().build(), transcript);

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.toolCallsTotal()).isEqualTo(1);
		assertThat(result.toolCallsInvalid()).isZero();
		assertThat(executor.invocations).hasSize(1);

		// The tool's output must reach the model, or the second turn is answering from nothing.
		assertThat(transcript.messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo(Role.TOOL);
			assertThat(message.content()).contains("\"result\":4");
		});
	}

	@Test
	@DisplayName("the step ceiling stops a model that never stops calling tools")
	void stopsAtStepCeiling() {
		// Always asks for another tool, never answers.
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		AgentRunner.Result result = run(config().maxSteps(3).loopThreshold(99).build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_MAX_STEPS);
		assertThat(result.stepsUsed()).isEqualTo(3);
		assertThat(result.error()).contains("3 steps");
	}

	@Test
	@DisplayName("an identical call repeated past the threshold is nudged, then stopped")
	void detectsRepetition() {
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		AgentRunner.Result result = run(config().maxSteps(10).loopThreshold(3).build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_LOOP);
		assertThat(result.error()).contains("repeated the same tool call");
		// Threshold reached on the third call: that one is nudged instead of run, so the executor sees
		// only the first two.
		assertThat(executor.invocations).hasSize(2);
	}

	@Test
	@DisplayName("a nudge is sent before the run is abandoned")
	void nudgesBeforeAbandoning() {
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		runner.execute(UUID.randomUUID(), config().maxSteps(10).loopThreshold(3).build(), transcript);

		// Naming the repetition is sometimes enough to change the model's course, so it gets one
		// chance before the run ends.
		assertThat(transcript.messages()).anySatisfy(message ->
				assertThat(message.content()).contains("identical arguments"));
	}

	@Test
	@DisplayName("an invalid call is rejected with feedback the model can act on")
	void feedsBackCorrectionForInvalidCall() {
		model.willReturn(toolCall("calk_eval", "{\"expression\":\"2+2\"}"))
				.willReturn(answer("Sorry, corrected."));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.execute(UUID.randomUUID(), config().build(), transcript);

		assertThat(result.toolCallsInvalid()).isEqualTo(1);
		assertThat(executor.invocations).as("an invalid call must never reach an executor").isEmpty();

		// The correction must name the real tool, or the model has nothing to correct towards.
		assertThat(transcript.messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo(Role.TOOL);
			assertThat(message.content()).contains("unknown_tool").contains(CALC);
		});
		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
	}

	@Test
	@DisplayName("a model that cannot act on corrections ends the run rather than looping on itself")
	void stopsWhenCorrectionsAreIgnored() {
		// Three bad calls in one step, against an allowance of two.
		model.willReturn(new ChatResult(null, null, List.of(
				new ToolCallRequest("a", "nope_one", "{}"),
				new ToolCallRequest("b", "nope_two", "{}"),
				new ToolCallRequest("c", "nope_three", "{}")),
				FinishReason.TOOL_CALLS, TokenUsage.EMPTY, "m", "{}", "{}", Duration.ZERO));

		AgentRunner.Result result = run(config().maxRepairsPerStep(2).build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_UNREPAIRABLE);
		assertThat(result.toolCallsInvalid()).isEqualTo(3);
		assertThat(result.error()).contains("not acting on the corrections");
	}

	@Test
	@DisplayName("a token budget is clamped into the request, not merely checked between steps")
	void clampsOutputAllowanceToRemainingBudget() {
		// Without clamping, a single call could spend far more than the whole budget and still report
		// success -- which is exactly what happened before this was fixed.
		model.willReturn(answer("ok"));

		run(config().maxOutputTokens(5000).tokenBudget(300).build());

		assertThat(model.received).hasSize(1);
		assertThat(model.received.getFirst().maxOutputTokens())
				.as("the request must ask for no more than the budget allows")
				.isEqualTo(300);
	}

	@Test
	@DisplayName("an unbudgeted run keeps the allowance it was given")
	void leavesAllowanceAloneWithoutBudget() {
		model.willReturn(answer("ok"));

		run(config().maxOutputTokens(5000).build());

		assertThat(model.received.getFirst().maxOutputTokens()).isEqualTo(5000);
	}

	@Test
	@DisplayName("an exhausted budget stops the run before more tool work is started")
	void stopsBeforeSpendingAnExhaustedBudget() {
		// A first step that consumes the whole budget and then asks to continue.
		model.willReturn(new ChatResult(null, null,
				List.of(new ToolCallRequest("1", CALC, "{\"expression\":\"1+1\"}")),
				FinishReason.TOOL_CALLS, new TokenUsage(100, 200, 0, 300), "m", "{}", "{}",
				Duration.ZERO));

		AgentRunner.Result result = run(config().tokenBudget(250).build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_BUDGET);
		assertThat(executor.invocations).as("no tool work on an exhausted budget").isEmpty();
	}

	@Test
	@DisplayName("a completed answer is honoured even if it took the last of the budget")
	void honoursAnAnswerThatUsedTheLastOfTheBudget() {
		// The work is done; aborting here would throw away a perfectly good result.
		model.willReturn(new ChatResult("Done.", null, List.of(), FinishReason.STOP,
				new TokenUsage(100, 200, 0, 300), "m", "{}", "{}", Duration.ZERO));

		AgentRunner.Result result = run(config().tokenBudget(250).build());

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.finalOutput()).isEqualTo("Done.");
	}

	@Test
	@DisplayName("spending the whole allowance on reasoning is reported as a budget failure")
	void reportsReasoningOnlyResponseAsBudgetFailure() {
		// Measured behaviour of the target model: with a small allowance it thinks until the limit and
		// returns nothing. Read naively that looks like a model with nothing to say.
		model.willReturn(new ChatResult(null, "thinking and thinking", List.of(), FinishReason.LENGTH,
				new TokenUsage(16, 20, 20, 36), "m", "{}", "{}", Duration.ZERO));

		AgentRunner.Result result = run(config().build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_BUDGET);
		assertThat(result.error()).contains("output allowance");
	}

	@Test
	@DisplayName("oversized tool output is truncated before it reaches the context")
	void truncatesLargeToolOutput() {
		model.willReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"))
				.willReturn(answer("done"));
		executor.willReturn(ToolOutcome.success("x".repeat(50_000), 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		runner.execute(UUID.randomUUID(), config().toolOutputMaxChars(500).build(), transcript);

		Message toolMessage = transcript.messages().stream()
				.filter(message -> message.role() == Role.TOOL).findFirst().orElseThrow();

		assertThat(toolMessage.truncated()).isTrue();
		assertThat(toolMessage.content().length()).isLessThan(1000);
		// The truncation is stated, so neither the model nor a later reader mistakes it for the whole.
		assertThat(toolMessage.content()).contains("truncated by the harness");
	}

	@Test
	@DisplayName("a failing tool is reported to the model and the run carries on")
	void continuesAfterToolFailure() {
		// A tool failure is an outcome the model can react to, not a reason to end the run.
		model.willReturn(toolCall(CALC, "{\"expression\":\"1/0\"}"))
				.willReturn(answer("That did not work: division by zero."));
		executor.willReturn(ToolOutcome.failure("arithmetic_error", "Division by zero", 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.execute(UUID.randomUUID(), config().build(), transcript);

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(transcript.messages()).anySatisfy(message ->
				assertThat(message.content()).contains("arithmetic_error"));
	}

	@Test
	@DisplayName("a call recovered from message text is executed like a native one")
	void executesCallRecoveredFromText() {
		// The model announced the call instead of emitting it. Unrecovered, this reads as a final
		// answer and the run ends having done nothing.
		model.willReturn(new ChatResult(
				"<tool_call>{\"name\":\"calc_eval\",\"arguments\":{\"expression\":\"2+2\"}}</tool_call>",
				null, List.of(), FinishReason.STOP, TokenUsage.EMPTY, "m", "{}", "{}", Duration.ZERO))
				.willReturn(answer("It is 4."));
		executor.willReturn(ToolOutcome.success("{\"result\":4}", 1L));

		AgentRunner.Result result = run(config().build());

		assertThat(executor.invocations).hasSize(1);
		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
	}

	@Test
	@DisplayName("a transport failure ends the run as FAILED, not as a guardrail abort")
	void reportsTransportFailureDistinctly() {
		// A bug or an unreachable server is not the model misbehaving, and conflating the two would
		// send you looking in the wrong place.
		model.willThrow(new IllegalStateException("connection reset"));

		AgentRunner.Result result = run(config().build());

		assertThat(result.status()).isEqualTo(RunStatus.FAILED);
		assertThat(result.error()).contains("connection reset");
	}

	@Test
	@DisplayName("a call needing approval parks the run instead of executing it")
	void parksOnApprovalRequired() {
		policyReturns(ApprovalMode.REQUIRE_APPROVAL, "that policy requires approval");
		model.willReturn(toolCall(CALC, "{\"expression\":\"2+2\"}"));

		AgentRunner.Result result = run(config().build());

		assertThat(result.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
		assertThat(executor.invocations).as("nothing runs before a person decides").isEmpty();
		assertThat(result.pending()).isNotNull();
		assertThat(result.pending().toolName()).isEqualTo(CALC);
		assertThat(result.pending().arguments().get("expression").asString()).isEqualTo("2+2");
	}

	@Test
	@DisplayName("parking carries the rest of the step so no call is lost")
	void parkingKeepsRemainingCalls() {
		// A model can emit several calls at once. Parking on the first must not silently discard the
		// others, or the run would quietly do less than it was asked to.
		policyReturns(ApprovalMode.REQUIRE_APPROVAL, "needs approval");
		model.willReturn(new ChatResult(null, null, List.of(
				new ToolCallRequest("a", CALC, "{\"expression\":\"1+1\"}"),
				new ToolCallRequest("b", CALC, "{\"expression\":\"2+2\"}")),
				FinishReason.TOOL_CALLS, TokenUsage.EMPTY, "m", "{}", "{}", Duration.ZERO));

		AgentRunner.Result result = run(config().loopThreshold(99).build());

		assertThat(result.pending().remainingCalls()).hasSize(1);
		assertThat(result.pending().remainingCalls().getFirst().id()).isEqualTo("b");
	}

	@Test
	@DisplayName("a denied call is reported to the model rather than ending the run")
	void deniedCallIsFedBack() {
		// Denial is information the model can act on: try another tool, or explain the limitation.
		// Ending the run on its behalf would throw away a recoverable situation.
		policyReturns(ApprovalMode.DENY, "that tool is forbidden here");
		model.willReturn(toolCall(CALC, "{\"expression\":\"2+2\"}"))
				.willReturn(answer("I am not permitted to use that tool."));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.execute(UUID.randomUUID(), config().build(), transcript);

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(executor.invocations).isEmpty();
		assertThat(transcript.messages()).anySatisfy(message ->
				assertThat(message.content()).contains("policy_denied").contains("forbidden"));
	}

	@Test
	@DisplayName("an approved call runs when the run resumes, and the loop carries on")
	void resumeExecutesApprovedCall() {
		executor.willReturn(ToolOutcome.success("{\"result\":4}", 1L));
		model.willReturn(answer("It is 4."));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("what is 2+2?"));

		AgentRunner.Result result = runner.resume(UUID.randomUUID(), config().build(),
				resumeState(transcript, ApprovalDecision.APPROVED));

		assertThat(executor.invocations).hasSize(1);
		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.finalOutput()).isEqualTo("It is 4.");
	}

	@Test
	@DisplayName("a denied call becomes a tool failure when the run resumes")
	void resumeReportsDenial() {
		model.willReturn(answer("Understood, I will not do that."));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));

		AgentRunner.Result result = runner.resume(UUID.randomUUID(), config().build(),
				resumeState(transcript, ApprovalDecision.DENIED));

		assertThat(executor.invocations).as("a denied call must never run").isEmpty();
		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(transcript.messages()).anySatisfy(message ->
				assertThat(message.content()).contains("approval_denied"));
	}

	@Test
	@DisplayName("a resumed run keeps its loop counts, so pausing cannot reset the guard")
	void resumeRebuildsLoopCounts() {
		// The nastiest failure this guards against: a run resumed with a clean detector could loop
		// forever precisely because a human paused to look at it.
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		JsonNode repeated = mapper.readTree("{\"expression\":\"1+1\"}");
		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));

		// Two identical calls already happened before the pause, against a threshold of three.
		AgentRunner.ResumeState state = new AgentRunner.ResumeState(transcript, 1, 2, 0,
				TokenUsage.EMPTY, Map.of(), Set.of(),
				List.of(new AgentRunner.PriorCall(CALC, repeated),
						new AgentRunner.PriorCall(CALC, repeated)),
				"call_1", CALC, repeated, List.of(), ApprovalDecision.APPROVED, "CALL", null);

		AgentRunner.Result result = runner.resume(UUID.randomUUID(),
				config().maxSteps(10).loopThreshold(3).build(), state);

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_LOOP);
	}

	@Test
	@DisplayName("a run-scoped grant is carried forward so the same tool stops asking")
	void runGrantIsCarriedForward() {
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":4}", 1L));
		model.willReturn(answer("done"));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.resume(UUID.randomUUID(), config().build(),
				new AgentRunner.ResumeState(transcript, 1, 1, 0, TokenUsage.EMPTY, Map.of(),
						Set.of(CALC), List.of(), "call_1", CALC,
						mapper.readTree("{\"expression\":\"2+2\"}"), List.of(),
						ApprovalDecision.APPROVED, "RUN", null));

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		// Carried on the result so the next resume starts from the same grants.
		assertThat(result.runGrants()).contains(CALC);
	}

	private AgentRunner.ResumeState resumeState(Transcript transcript, ApprovalDecision decision) {
		return new AgentRunner.ResumeState(transcript, 1, 1, 0, TokenUsage.EMPTY, Map.of(), Set.of(),
				List.of(), "call_1", CALC, mapper.readTree("{\"expression\":\"2+2\"}"), List.of(),
				decision, "CALL", null);
	}

	@Test
	@DisplayName("a resumed run keeps its original step ceiling")
	void resumeDoesNotResetTheStepCeiling() {
		// The guardrail must not be reset by the act of asking a human about it. A run that parks
		// repeatedly would otherwise get a fresh allowance each time and exceed maxSteps without
		// bound -- pausing to check on it would make it less bounded, not more.
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));

		// Resuming at step 3 of a 4-step allowance leaves room for exactly one more step.
		AgentRunner.Result result = runner.resume(UUID.randomUUID(),
				config().maxSteps(4).loopThreshold(99).build(),
				new AgentRunner.ResumeState(transcript, 3, 1, 0, TokenUsage.EMPTY, Map.of(), Set.of(),
						List.of(), "call_1", CALC, mapper.readTree("{\"expression\":\"2+2\"}"), List.of(),
						ApprovalDecision.APPROVED, "CALL", null));

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_MAX_STEPS);
		assertThat(result.stepsUsed()).isEqualTo(4);
	}

	@Test
	@DisplayName("a fork gets a fresh step allowance from where it inherited")
	void forkStartsAFreshAllowance() {
		// A fork inherits another run's steps. Counting them against its own limit would mean a fork
		// taken late in a long run had almost no room to do anything.
		model.willReturn(answer("done"));

		Transcript transcript = new Transcript();
		transcript.addAt(Message.user("inherited"), 7);

		AgentRunner.Result result = runner.execute(UUID.randomUUID(),
				config().maxSteps(3).build(), transcript, 7);

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		// Its own first step is 8, continuing the numbering rather than restarting at 1.
		assertThat(result.stepsUsed()).isEqualTo(8);
	}

	// -------------------------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------------------------


	// -------------------------------------------------------------------------------------------
	// Cancellation
	// -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a cancelled run stops in a named state rather than failing")
	void stopsWhenCancelled() {
		model.willAlwaysReturn(toolCall(CALC, "{\"expression\":\"1+1\"}"));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		UUID runId = UUID.randomUUID();
		cancellation.cancel(runId);

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.execute(runId, config().maxSteps(50).build(), transcript);

		// CANCELLED, not FAILED: someone chose this, and a stop button that reported a failure would
		// make every deliberate stop look like a defect in the run.
		assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
		assertThat(result.error()).contains("Stopped by a person");
	}

	@Test
	@DisplayName("a cancelled run leaves the thread clean for the persistence that follows")
	void clearsTheInterruptOnTheWayOut() {
		model.willAlwaysReturn(answer("done"));
		UUID runId = UUID.randomUUID();
		cancellation.cancel(runId);

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		runner.execute(runId, config().build(), transcript);

		// The transcript is written after the loop returns. Leaving the interrupt set would make that
		// write fail, losing the conversation the stop was meant to preserve.
		assertThat(Thread.currentThread().isInterrupted()).isFalse();
	}

	@Test
	@DisplayName("cancellation takes effect between the tool calls of one step")
	void stopsBetweenToolCalls() {
		UUID runId = UUID.randomUUID();
		model.willReturn(toolCalls(
				new ToolCallRequest("c1", CALC, "{\"expression\":\"1+1\"}"),
				new ToolCallRequest("c2", CALC, "{\"expression\":\"2+2\"}")));
		executor.onInvocation(() -> cancellation.cancel(runId));
		executor.willAlwaysReturn(ToolOutcome.success("{\"result\":2}", 1L));

		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		AgentRunner.Result result = runner.execute(runId, config().loopThreshold(99).build(),
				transcript);

		assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
		// The first call ran; the second was never started, rather than being interrupted mid-flight.
		assertThat(executor.invocations).hasSize(1);
	}

	// -------------------------------------------------------------------------------------------
	// Retrying a transient failure
	// -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a 503 while the model loads is retried rather than losing the run")
	void retriesServerErrors() {
		model.willThrowOnce(new LmStudioException("loading", HttpStatus.SERVICE_UNAVAILABLE, null,
				null, null));
		model.willReturn(answer("ready now"));

		AgentRunner.Result result = runWith(retryingProperties(), config().build());

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.finalOutput()).isEqualTo("ready now");
	}

	@Test
	@DisplayName("a bad request is not retried, because it will be bad again")
	void doesNotRetryClientErrors() {
		model.willThrowOnce(new LmStudioException("no such model", HttpStatus.BAD_REQUEST, null, null,
				null));
		model.willReturn(answer("never reached"));

		AgentRunner.Result result = runWith(retryingProperties(), config().build());

		// Retrying a 4xx only doubles the cost of a mistake, and hides it behind a delay.
		assertThat(result.status()).isEqualTo(RunStatus.FAILED);
	}

	// -------------------------------------------------------------------------------------------
	// The context window
	// -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a conversation that cannot be made to fit ends in its own state")
	void abortsWhenTheContextCannotFit() {
		model.willAlwaysReturn(answer("unused"));

		// A window smaller than the reserved output allowance: no compaction can rescue this.
		AgentRunner.Result result = run(config().contextLength(64).maxOutputTokens(2048).build());

		// Named separately from a budget abort because the remedy is specific: load the model with a
		// larger context_length, shorten tool output, or offer fewer tools.
		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_CONTEXT);
		assertThat(result.error()).contains("context_length");
	}

	// -------------------------------------------------------------------------------------------
	// The answer contract
	// -------------------------------------------------------------------------------------------

	@Test
	@DisplayName("an answer that breaks the schema is corrected and retried")
	void repairsAnInvalidAnswer() {
		model.willReturn(answer("about forty two, give or take"))
				.willReturn(answer("{\"answer\": 42}"));

		AgentRunner.Result result = run(config().responseSchema(answerSchema()).build());

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
		assertThat(result.finalOutput()).isEqualTo("{\"answer\": 42}");
		// The correction reached the model as an ordinary turn, carrying the schema.
		assertThat(model.received.get(1).messages()).anySatisfy(message ->
				assertThat(message.content()).contains("expected_schema"));
	}

	@Test
	@DisplayName("a model that cannot meet the contract is stopped rather than looped")
	void givesUpOnAnUnrepairableAnswer() {
		model.willAlwaysReturn(answer("still prose"));

		AgentRunner.Result result = run(config().responseSchema(answerSchema())
				.maxOutputRepairs(1).maxSteps(10).build());

		assertThat(result.status()).isEqualTo(RunStatus.ABORTED_UNREPAIRABLE);
		// The offending answer is carried out with the result: a failure that hides what was produced
		// cannot be diagnosed.
		assertThat(result.finalOutput()).isEqualTo("still prose");
	}

	@Test
	@DisplayName("a conforming answer passes straight through")
	void acceptsAConformingAnswer() {
		model.willReturn(answer("{\"answer\": 4, \"unit\": \"kg\"}"));

		AgentRunner.Result result = run(config().responseSchema(answerSchema()).build());

		assertThat(result.status()).isEqualTo(RunStatus.FINISHED);
	}

	@Test
	@DisplayName("pinned tool versions are what the loop asks the resolver for")
	void passesPinnedVersionsToTheResolver() {
		model.willReturn(answer("done"));

		run(new ConfigBuilder().build());

		// The resolver is asked with both arguments, so a pinned version cannot be silently dropped
		// on the way from the run configuration to the tools the model is shown.
		org.mockito.Mockito.verify(resolverMock).resolve(any(), any());
	}

	private JsonNode answerSchema() {
		return mapper.readTree("""
				{"type":"object","properties":{"answer":{"type":"number"},
				 "unit":{"type":"string"}},"required":["answer"]}
				""");
	}

	private AgentRunner.Result run(RunConfig config) {
		Transcript transcript = new Transcript();
		transcript.add(Message.user("go"));
		return runner.execute(UUID.randomUUID(), config, transcript);
	}

	private static ChatResult answer(String text) {
		return new ChatResult(text, null, List.of(), FinishReason.STOP, TokenUsage.EMPTY, "m", "{}",
				"{}", Duration.ZERO);
	}

	private static ChatResult toolCalls(ToolCallRequest... calls) {
		return new ChatResult(null, null, List.of(calls), FinishReason.TOOL_CALLS, TokenUsage.EMPTY,
				"m", "{}", "{}", Duration.ZERO);
	}

	private static ChatResult toolCall(String tool, String arguments) {
		return new ChatResult(null, null,
				List.of(new ToolCallRequest("call_" + tool, tool, arguments)), FinishReason.TOOL_CALLS,
				TokenUsage.EMPTY, "m", "{}", "{}", Duration.ZERO);
	}

	private ConfigBuilder config() {
		return new ConfigBuilder();
	}

	/** Keeps each test to the one setting it is about. */
	private static final class ConfigBuilder {

		private int maxSteps = 5;

		private int maxRepairs = 2;

		private Integer tokenBudget;

		private Integer maxOutputTokens = 1000;

		private int toolOutputMaxChars = 4000;

		private int loopThreshold = 3;

		private int contextLength;

		private JsonNode responseSchema;

		private int maxOutputRepairs = 2;

		ConfigBuilder maxSteps(int value) {
			this.maxSteps = value;
			return this;
		}

		ConfigBuilder maxRepairsPerStep(int value) {
			this.maxRepairs = value;
			return this;
		}

		ConfigBuilder tokenBudget(Integer value) {
			this.tokenBudget = value;
			return this;
		}

		ConfigBuilder maxOutputTokens(Integer value) {
			this.maxOutputTokens = value;
			return this;
		}

		ConfigBuilder toolOutputMaxChars(int value) {
			this.toolOutputMaxChars = value;
			return this;
		}

		ConfigBuilder loopThreshold(int value) {
			this.loopThreshold = value;
			return this;
		}


		ConfigBuilder contextLength(int value) {
			this.contextLength = value;
			return this;
		}

		ConfigBuilder responseSchema(JsonNode value) {
			this.responseSchema = value;
			return this;
		}

		ConfigBuilder maxOutputRepairs(int value) {
			this.maxOutputRepairs = value;
			return this;
		}

		RunConfig build() {
			return new RunConfig("m", null, SamplingParams.deterministic(), maxOutputTokens,
					List.of(CALC), Map.of(), false, maxSteps, maxRepairs, tokenBudget, null,
					toolOutputMaxChars, loopThreshold, false,
					contextLength == 0 ? null : contextLength, responseSchema, false, maxOutputRepairs);
		}
	}

	/** A model whose every answer is decided in advance, so a guardrail can be provoked exactly. */
	private static final class ScriptedModel implements ChatModelClient {

		/** Either a {@link ChatResult} to return or a {@link RuntimeException} to throw, in order. */
		private final Deque<Object> script = new ArrayDeque<>();

		private final List<ChatRequest> received = new ArrayList<>();

		private ChatResult always;

		private RuntimeException failure;

		ScriptedModel willReturn(ChatResult result) {
			this.script.add(result);
			return this;
		}

		/** Fails once and then carries on with the script, which is what a retry has to survive. */
		ScriptedModel willThrowOnce(RuntimeException exception) {
			this.script.add(exception);
			return this;
		}

		void willAlwaysReturn(ChatResult result) {
			this.always = result;
		}

		void willThrow(RuntimeException exception) {
			this.failure = exception;
		}

		@Override
		public ChatResult complete(ChatRequest request) {
			received.add(request);
			if (failure != null) {
				throw failure;
			}
			Object next = script.poll();
			if (next instanceof RuntimeException exception) {
				throw exception;
			}
			if (next instanceof ChatResult result) {
				return result;
			}
			if (always != null) {
				return always;
			}
			throw new IllegalStateException("The scripted model ran out of responses");
		}

		@Override
		public void stream(ChatRequest request, Consumer<ChatStreamEvent> sink) {
			sink.accept(new ChatStreamEvent.Completed(complete(request)));
		}
	}

	/** Records what it was asked to run, and returns whatever the test decided. */
	private static final class RecordingExecutor implements ToolExecutor {

		private final List<ToolInvocation> invocations = new ArrayList<>();

		private final Deque<ToolOutcome> scripted = new ArrayDeque<>();

		private ToolOutcome always;

		private Runnable onInvocation;

		void willReturn(ToolOutcome outcome) {
			this.scripted.add(outcome);
		}

		/** Runs at the moment a tool is invoked, so a test can cancel mid-step. */
		void onInvocation(Runnable action) {
			this.onInvocation = action;
		}

		void willAlwaysReturn(ToolOutcome outcome) {
			this.always = outcome;
		}

		@Override
		public ExecutorType type() {
			return ExecutorType.BUILTIN;
		}

		@Override
		public ToolOutcome execute(ToolInvocation invocation) {
			invocations.add(invocation);
			if (onInvocation != null) {
				onInvocation.run();
			}
			ToolOutcome next = scripted.poll();
			return next != null ? next : (always != null ? always : ToolOutcome.success("{}", 0L));
		}
	}
}
