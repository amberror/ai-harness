# AI Control — an experimental harness for local models (Qwen3 4B Q6 via LM Studio)

## Context

The project is currently a bare Spring Boot 4.1.1 skeleton (Java 25, only `spring-boot-starter`):
`AiControllApplication.java`, an almost-empty `application.yaml`, one generated test.

The goal is a **harness that turns a raw local model into structured, predictable, observable
behavior** — with runtime management of tools and MCP servers, full behavioral monitoring, and the
guardrails that production agent harnesses use.

### The decision that shapes everything

LM Studio exposes four inference surfaces, and they are **not** interchangeable for this purpose
(from the skill's endpoint comparison):

| | custom tools | assistant msgs in request | stateful | load/prompt events |
|---|---|---|---|---|
| `/api/v1/chat` (native) | ❌ | ❌ | ✅ | ✅ |
| `/v1/responses` | ✅ | ✅ | ✅ | ❌ |
| `/v1/chat/completions` | ✅ | ✅ | ❌ | ❌ |
| `/v1/messages` | ✅ | ✅ | ❌ | ❌ |

The native `/api/v1/chat` endpoint **cannot accept custom tools and cannot accept assistant messages
in the request**. If the harness delegates to it, LM Studio owns the agentic loop and we are reduced
to *watching* it — we could not intercept a tool call, repair bad arguments, require approval,
compact context, or fork a conversation. That is a monitor, not a harness.

So: **the harness owns the loop, driving `/v1/chat/completions`** — stateless, which is a feature
here, because owning the message history is what makes replay, compaction, forking and A/B
experiments possible. The native `/api/v1/models*` endpoints are still used, for the *admin plane*
(list / load / unload / download), where they are strictly better than the OpenAI-compatible
surface — notably `context_length` control at load time.

### Design pressure from the target model

Qwen3 4B at Q6 is small. The harness must assume, not hope against:
emitted reasoning (`<think>…</think>` inline **or** a separate `reasoning_content` field, depending
on LM Studio version/settings); malformed tool-call JSON; hallucinated tool and argument names; tool
calls emitted as prose/fenced JSON instead of in the `tool_calls` field; repetition loops; and a
small context window. Every one of these gets an explicit, tested countermeasure below. This is the
"structured and predictable" part of the goal — it is mostly *defensive parsing plus corrective
feedback*, not prompt wishful thinking.

### Confirmed decisions

- Hand-rolled LM Studio client (Spring `RestClient` + manual SSE) for exact control over bytes on
  the wire; reuse the **official MCP Java SDK** rather than reimplementing MCP transport.
- REST + SSE API first, plus a **Thymeleaf/HTMX** console (no Node toolchain).
- **H2 in file mode** + JPA + Flyway (one URL change to move to Postgres later).
- Tool executors: **declarative HTTP**, **built-in Java**, **MCP**. Sandboxed script tools (GraalJS)
  deliberately deferred — arbitrary code execution needs its own security design.

---

## Architecture

Package root stays `com.example.ai_controll`.

```
com.example.ai_controll
├── config/           AppProperties, RestClient beans, virtual-thread executor
├── lmstudio/         the raw model boundary
│   ├── port/         ChatModelClient, ModelAdminClient      (interfaces)
│   ├── openai/       OpenAiCompatChatClient  (+ SseChatStreamReader)
│   ├── admin/        LmStudioAdminClient     (native /api/v1/models*)
│   └── dto/          request/response records
├── conversation/     Message, Transcript, ContextAssembler, CompactionStrategy, TokenEstimator
├── tool/
│   ├── registry/     ToolDefinition, Toolset, ToolRegistryService (versioned CRUD)
│   ├── schema/       JsonSchemaValidator, SchemaRenderer, ArgumentCoercer
│   ├── parse/        ToolCallParser chain, RepairFeedbackFactory
│   ├── exec/         ToolExecutor SPI + Builtin/Http/Mcp implementations
│   └── policy/       ToolPolicyEngine, PolicyDecision
├── mcp/              McpServerEntity, McpClientManager, McpToolSyncService
├── orchestrator/     AgentRunner (the loop), Guardrails, LoopDetector, BudgetTracker, RunService
├── observability/    RunEvent, EventJournal, RunEventPublisher (SSE), metrics
├── eval/             scenarios + batch runner            (phase 5)
├── api/              REST controllers + SSE endpoints
└── web/              Thymeleaf/HTMX console controllers
```

### Core ports

```java
public interface ChatModelClient {
    ChatResult complete(ChatRequest request);
    void stream(ChatRequest request, Consumer<ChatStreamEvent> sink);
}

public interface ToolExecutor {                       // one per executor_type
    String type();
    ToolOutcome execute(ToolInvocation invocation, ExecutionContext ctx);
}

public interface ToolCallParser {                     // ordered chain, first hit wins
    List<ParsedToolCall> parse(RawAssistantMessage message);
}
```

### The loop — `AgentRunner`

This is the heart of the harness; everything else exists to serve it.

```
run(agentProfile, input):
  transcript = Transcript.of(profile.systemPrompt) + user(input)
  for step in 1..profile.maxSteps:
      budget.assertNotExceeded()                       -> BUDGET_EXCEEDED, abort
      tools   = toolsetService.resolveSchemas(profile.toolset)
      prompt  = contextAssembler.assemble(transcript, tools, budget)   // compaction happens here
      journal.emit(MODEL_REQUEST, exact JSON body)     // the literal bytes sent
      resp    = chatClient.complete(prompt)
      journal.emit(MODEL_RESPONSE, raw body, usage, timings)
      reasoning, content = reasoningExtractor.split(resp)
      calls   = toolCallParser.parse(resp)             // native -> fenced-json -> xml-ish
      if calls.isEmpty():
          transcript += assistant(content); return FINISHED
      for call in calls:
          v = validator.validate(call, tools)          // name known? args match JSON Schema?
          if !v.ok:
              transcript += toolError(call, repairFeedback(v))   // structured, machine-readable
              if ++repairs > profile.maxRepairs: return ABORTED_UNREPAIRABLE
              continue
          decision = policyEngine.evaluate(call, ctx)
          if DENY:             transcript += toolError(call, "denied by policy"); continue
          if REQUIRE_APPROVAL: park(run, call); return AWAITING_APPROVAL     // resumable
          outcome = executor.execute(call, timeout)
          transcript += toolResult(call, truncate(outcome))
      loopDetector.check(transcript)                   -> corrective nudge, then abort
  return ABORTED_MAX_STEPS
```

Every arrow into `journal.emit` is a persisted, replayable, SSE-broadcast event.

### Guardrails (the "predictable" contract)

| Failure mode | Countermeasure |
|---|---|
| Hallucinated tool name | Reject; feed back valid names + Levenshtein "did you mean" |
| Bad arguments | JSON Schema validate; feed back the exact failing field path |
| Tool call as prose/fenced JSON | Parser chain recovers it before treating it as a final answer |
| Repetition loop | Hash `(toolName, canonicalArgs)`; N repeats → corrective message → abort |
| Runaway run | `maxSteps`, wall-clock and token budgets from response `usage` |
| Context overflow | Budgeted assembly + compaction + tool-output truncation with `[truncated N chars]` |
| Hung tool | Per-tool timeout, per-tool rate limit |
| Unrepairable model | `maxRepairs` per step, then terminate with a typed status |

Repair feedback is delivered as a `tool`-role message with a machine-readable body — small models
follow a concrete correction far better than prose:

```json
{"error":"unknown_tool","message":"No tool named 'web_search'. Available: http_get, clock_now, calc_eval.","did_you_mean":"http_get"}
{"error":"invalid_arguments","message":"Required field 'url' is missing.","schema_path":"$.url"}
```

---

## Data model (Flyway `V1__baseline.sql`)

- `agent_profile` — model key, system prompt, sampling params, toolset, `max_steps`, budgets,
  `context_length`, reasoning effort
- `tool_definition` — **versioned**: unique `(name, version)` + a `current` pointer. Editing a tool
  creates a new version, so experiments can attribute a behavior change to a schema-wording change.
  Holds `params_schema` (JSON Schema), `executor_type`, `executor_config`, `enabled`, `source`
- `toolset` / `toolset_member` — named tool bundles with per-member overrides and optional version
  pinning (exposing few, small schemas at a time matters a lot for a 4B model)
- `mcp_server` — transport (stdio/streamable-HTTP), command/url, headers, status, `last_synced_at`
- `tool_policy` — name pattern → `AUTO | REQUIRE_APPROVAL | DENY`, timeout, rate limit
- `run` — status, input, final output, totals, error
- `run_message` — materialized transcript (role, content, reasoning, tool_call_id, args) for
  resume/fork
- `run_event` — the append-only journal: `(run_id, seq, step_no, type, ts, duration_ms, payload)`.
  Event types: `RUN_STARTED, MODEL_REQUEST, MODEL_RESPONSE, REASONING, TOOL_CALL_PARSED,
  TOOL_CALL_INVALID, TOOL_APPROVAL_REQUIRED, TOOL_EXEC_STARTED/SUCCEEDED/FAILED, CONTEXT_COMPACTED,
  LOOP_DETECTED, BUDGET_EXCEEDED, RUN_FINISHED/FAILED`

The journal is the single source of truth for monitoring, replay, and eval assertions — build it in
phase 1, before anything depends on it.

---

## Dependencies to add to `pom.xml`

```
spring-boot-starter-web, -thymeleaf, -data-jpa, -validation, -actuator
com.h2database:h2 (runtime)              org.flywaydb:flyway-core
io.modelcontextprotocol.sdk:mcp:2.0.1  + mcp-json-jackson3   (via mcp-bom)
com.networknt:json-schema-validator:3.0.7
org.webjars.npm:htmx.org                 (offline-friendly, no CDN)
test: spring-boot-starter-test, wiremock (stub LM Studio incl. SSE)
```

Two notes: use **`mcp-json-jackson3`**, since Spring Boot 4.1.1 defaults to Jackson 3.1.5.
`json-schema-validator` is Jackson-2 based — Boot 4 still manages Jackson 2 at 2.21.5, so both trees
coexist without a version conflict; just don't mix the ObjectMapper types across that boundary.
Set `spring.threads.virtual.enabled=true` (Java 25) so blocking model/tool calls stay cheap.

Skip `springdoc-openapi` for now unless a Boot 4-compatible release is confirmed at implementation
time; the HTMX console covers the exploratory need.

---

## Phases

**P0 — Foundation.** Dependencies, `AppProperties` (base URL, optional `LM_API_TOKEN`, timeouts,
default model), Flyway baseline, `LmStudioAdminClient` over native `/api/v1/models*`, `/api/models*`
REST, an LM Studio health indicator, dashboard page. *Done when:* the dashboard lists your models
and can load/unload Qwen with an explicit `context_length`.

**P1 — Chat core + observability.** Chat DTOs, `OpenAiCompatChatClient` (blocking + SSE reader),
`ReasoningExtractor` (handles both `<think>` inline and `reasoning_content`), `Transcript`,
`EventJournal`, `RunService`, `POST /api/runs`, `GET /api/runs/{id}/stream`, and the run-trace UI
showing every event with the exact request JSON expandable. *Done when:* a single-turn chat produces
a complete, inspectable trace.

**P2 — Tools + the loop.** Tool/Toolset entities, versioned CRUD API + editor UI, JSON Schema
validation, `ToolCallParser` chain, `BuiltinToolExecutor` (clock, calc, http_fetch) and
`HttpToolExecutor` (declarative: method, URL template, headers, body template, JSONPath extraction),
`POST /api/tools/{name}/test` to exercise a tool with no model in the way, then `AgentRunner` with
all guardrails. *Done when:* Qwen completes a multi-step tool run, and deliberately breaking it
(unknown tool, bad args) produces recovery rather than a crash.

**P3 — MCP.** `McpServer` entity, `McpClientManager` over the MCP SDK (stdio + streamable HTTP),
tool discovery/sync into the registry with per-tool allow-lists, `McpToolExecutor`, MCP admin UI.
The harness runs the MCP client itself, so MCP calls flow through the same validation, policy and
journal as every other tool.

**P4 — Policy + approvals.** `ToolPolicyEngine`, parked/resumable runs,
`POST /api/runs/{id}/approve`, audit trail, approval UI.

**P5 — Experiments.** YAML eval scenarios with assertions over the event journal, batch runs with N
repetitions (variance matters at temperature > 0), pass-rate comparison across prompt/tool-schema
variants, run fork/replay from any step. *This is what actually proves "predictable"* — the journal
from P1 makes it mostly assertion-writing.

---

## Verification

1. `./mvnw test` — unit tests for the parser chain (malformed JSON, fenced JSON, `<think>` tags),
   schema validation, loop detection, and compaction; WireMock-backed integration tests for the
   client, including an SSE stream, so CI never needs a live model.
2. `lms server start`, then load the Qwen model via the dashboard.
3. End-to-end by curl: list models → create an HTTP tool → `POST /api/tools/{name}/test` →
   create an agent profile → `POST /api/runs` → watch `GET /api/runs/{id}/stream`.
4. **Adversarial checks — these are the real acceptance criteria.** Point the agent at a toolset
   containing a deliberately misnamed tool, a tool with a required field the model tends to omit,
   and a scenario that invites repetition; assert the run ends with a typed status and a journal
   showing the repair attempts, not a hang or a stack trace.
5. `curl localhost:8080/actuator/health` reports LM Studio reachability.

## Open items to decide during implementation

- Exact LM Studio model key for Qwen3 4B Q6 (`GET /api/v1/models`) — keep configurable, never
  hardcoded, since you expect to swap models.
- Token estimation: start with a chars/4 heuristic for budgeting and correct it from the `usage`
  field on each response; a real tokenizer is only worth adding if compaction proves too coarse.
