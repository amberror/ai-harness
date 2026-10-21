# AI Control

An experimental harness for driving a raw local model (Qwen3.5 4B Q6 via LM Studio) into
structured, predictable, observable behaviour.

Spring Boot 4.1.1 · Java 25 · H2 + Flyway · Thymeleaf console · no AI framework.

---

## The decision that shapes everything

LM Studio exposes four inference surfaces, and they are **not** interchangeable for this purpose:

| | custom tools | assistant msgs in request | stateful | load/prompt events |
|---|---|---|---|---|
| `/api/v1/chat` (native) | ❌ | ❌ | ✅ | ✅ |
| `/v1/responses` | ✅ | ✅ | ✅ | ❌ |
| `/v1/chat/completions` | ✅ | ✅ | ❌ | ❌ |
| `/v1/messages` | ✅ | ✅ | ❌ | ❌ |

The native `/api/v1/chat` endpoint **accepts neither custom tools nor assistant messages**. A harness
built on it would hand the agentic loop to LM Studio and be left only watching — unable to intercept
a tool call, repair bad arguments, require approval, compact context, or fork a conversation.

So the harness **owns the loop** over `/v1/chat/completions`. Statelessness is the feature: owning the
history is what makes replay, compaction, forking and A/B experiments possible. The native
`/api/v1/models*` endpoints are still used for the **admin plane**, where they are strictly better —
notably `context_length`, which can only be set at load time.

---

## What is built

### Phase 0 — control plane ✅
- `LmStudioAdminClient` over native `/api/v1/models*`: list, load, unload, download, poll.
- `GET/POST /api/models*`, a dashboard and a models page.
- Health indicator reporting reachability **and** what is resident — "the harness is fine but nothing
  is loaded" and "LM Studio is unreachable" look identical from a failing run otherwise.

### Phase 1 — chat core + event journal ✅
- `OpenAiCompatChatClient`, blocking and streaming (SSE), capturing the **exact bytes** both ways.
- `ReasoningExtractor` handling the `reasoning_content` field, inline `<think>` tags, unclosed
  openers (ran out of tokens mid-thought) and orphan closers.
- `Transcript` / `Message` — the harness owns the conversation.
- **`EventJournal`** — append-only, the single source of truth for monitoring, replay and (later)
  evaluation assertions. Built before anything depends on it, because retrofitting a journal means
  reconstructing what happened instead of recording it.
- `POST /api/runs`, `GET /api/runs/{id}`, `/events`, `/stream` (SSE), plus a live trace console.

### Phase 2 — tools + the agentic loop ✅
- **Versioned tool registry.** Editing a tool never overwrites it: each change is a new version and
  the current pointer moves. When the model's behaviour changes, the question is always *against
  which version of the schema*, and a mutable row cannot answer it. Enforced in the database, not
  in application code.
- **`ToolArgumentValidator`** — the gate that turns a raw model into a predictable one. Rejections
  carry a machine-readable correction aimed at the model: the tools that *do* exist, a
  "did you mean" for a near-miss, the exact failing field path, and the schema restated.
- **Strict by default, lenient opt-in.** Lenient mode repairs fenced blobs, quoted numbers, trailing
  commas, unquoted keys, Python literals and double-encoded JSON — and journals every fix by name,
  so strict and lenient runs of the same scenario can be compared.
- **`ToolCallExtractor`** — a parser chain that recovers calls the model wrote as text instead of
  emitting properly (`<tool_call>` tags, fenced JSON, bare objects). Conservative: example JSON in a
  real answer is not mistaken for a call. Which channel a call arrived through is recorded.
- **Executors** — eleven built-ins and declarative HTTP tools. The built-in set deliberately spans
  argument *shapes* as well as jobs, so an experiment can attribute a failure to the shape rather
  than to the task: scalars (`calc_eval`, `text_stats`, `encode_text`), none at all (`clock_now`),
  an **array** (`stats_summary`), **enums** (`unit_convert`, `date_diff`, `date_shift`), and a
  **nested object** (`json_extract`). `http_fetch` and `file_read` are capabilities rather than
  calculations and both ship gated by an allowlist
  defined entirely as data, so a working tool can be created through the API with no redeploy.
- **`HttpEgressPolicy`** — deny-by-default host allowlist. The model chooses the arguments, so a
  URL-templated argument is an arbitrary-request primitive; private and loopback addresses are
  refused even for an allowlisted host unless explicitly permitted.
- **`AgentRunner`** — the loop, with every guardrail below.
- `GET/POST/PUT/DELETE /api/tools`, `POST /api/tools/{name}/test`, plus a tools console.

### Phase 3 — MCP ✅
- **The harness runs the MCP client itself**, rather than delegating to LM Studio's `integrations`
  field. Same decision as owning the loop, same reason: a call made by someone else can be watched
  but not governed. Every MCP tool call passes through the same validation, policy and journal as a
  built-in one.
- **Tools are imported into the registry, not proxied.** Proxying would bypass everything the
  registry provides — no version history when a server changes a schema underneath you, no way to
  reword a description a small model keeps misreading, no way to disable one tool from a server
  offering twenty. Imported tools are namespaced by server label, since the model emits one flat
  name and two servers may both offer `search`.
- **Both transports**: stdio (child process) and streamable HTTP / SSE.
- **`McpStdioPolicy`** — deny-by-default executable allowlist. Launching a stdio server *runs a
  program*, and a server config is runtime-editable data, so without this the tool-management API
  would double as a "run any command" API. Matching is by program, however it was spelled: `npx`,
  `npx.cmd` and a full path all satisfy one entry. Arguments are deliberately *not* checked — an
  allowed interpreter runs whatever it is handed, so checking them would be false assurance.
- **Discovery is separate from import**, so you can see what a server offers before deciding what to
  expose. Re-syncing writes a new version only where the upstream definition actually changed.
- `GET/POST/DELETE /api/mcp/servers`, `/discover`, `/sync`, plus an MCP console.

### Phase 4 — policy and approvals ✅
- **`ToolPolicyEngine`** — glob rules over tool names, ordered by priority (lowest wins, ties broken
  by name so the outcome never depends on database ordering). A call matching nothing simply runs:
  this is a list of exceptions, not a list of permissions. The engine governs *workflow*; the
  boundaries that actually contain the model — the egress and stdio allowlists — are enforced
  separately and cannot be switched off by a rule here.
- **Modes**: `AUTO`, `REQUIRE_APPROVAL`, `DENY`, plus an optional per-run rate limit.
- **Parked runs are persisted, not blocked.** A run needing approval ends its thread as
  `AWAITING_APPROVAL`; approving it rebuilds the loop's state from what was recorded — transcript
  from `run_message`, budget from the run row, loop-detector counts from the journal — and carries
  on. Blocking a virtual thread would have been cheaper to write, but every parked run would vanish
  on restart, and this forces the replay path phase 5 needs to actually work.
- **Three approval scopes**: this call, this tool for the rest of the run, or always — where
  "always" writes a policy rule, so a standing exemption is visible on the policy page rather than
  hidden inside one run's history. A *denial* is never broadened.
- **A denied call is fed back to the model**, not fatal to the run — same treatment as any tool that
  could not do what was asked.
- `GET/POST/PUT/DELETE /api/policies`, `GET /api/approvals`, `/approve`, `/deny`, plus a policy
  console and an approval panel on the run page.

### Phase 5 — evaluation and experiments ✅
- **The unit of measurement is a batch, not a run.** For a 4B model a single passing run is an
  anecdote; the useful claim is a pass rate over repetitions. Repetitions run sequentially, because
  LM Studio serves one model instance and overlapping runs would corrupt the timing figures the
  batch exists to collect.
- **Scenarios** live in the database (editable like everything else) with **YAML import/export**, so
  an experiment's definition can be committed next to the code it measures. Import accepts a single
  scenario or a list.
- **Twelve deterministic assertion kinds** — status, output contains/not-contains/matches, tool
  called / not called / called-with, ceilings on tool calls, steps and tokens, no-invalid-calls, and
  "no such journal event" (which is how you say *and it did not loop*). All read from the run's own
  journal, which is the payoff for having built the journal first.
- **An optional model-graded assertion** for things substring matching cannot reach. Every verdict
  carries a `reproducible` flag so a judged result never blends into the deterministic ones.
- **Variants** apply overrides on top of a scenario. Holding the scenario fixed and changing exactly
  one thing is the only way a change in pass rate can be attributed to anything.
- **Forking** starts a new run from a position in another run's journal, with its own step numbering
  continuing from the parent so a fork of a fork still cuts where you asked.
- `GET/POST/DELETE /api/eval/scenarios`, `/import`, `/export`, `POST /api/eval/batches`,
  `POST /api/runs/{id}/fork`, plus an eval console.


### Phase 6 — the gaps that could produce a wrong finding ✅
Four things here could each make the harness report something about the model that was actually
about the harness. That is the worst failure mode an instrument has, so they came before features.

- **Context assembly.** The transcript grows every step, and past the loaded `context_length` the
  *server* decides what to do about it — llama.cpp-backed servers discard from the front, taking the
  system prompt and the task first. The model then appears to forget what it was doing and stops
  using its tools, which reads as a model failure. The harness now decides instead: oldest tool
  results are shortened first, then whole old exchanges are dropped, never the system prompt, the
  task, or the last two exchanges, and never one half of a tool-call/tool-result pair. It is
  deterministic (no model call — a summarising compactor would fold the model's own variance into
  the instrument) and non-destructive (the transcript stays whole; only the request is reduced).
  A conversation that still cannot fit ends the run as `ABORTED_CONTEXT` rather than being sent.
- **Token estimation that corrects itself.** No tokenizer is shipped — that would weld the harness to
  one model. It estimates from a characters-per-token ratio and recalibrates against
  `usage.prompt_tokens` on every response, so within a couple of steps the ratio is the loaded
  model's, measured rather than assumed.
- **A single model slot.** LM Studio serves one model instance: a second concurrent request queues
  inside the server and inherits the first one's latency. Generation is now gated
  (`max-concurrent-requests`, default 1) and time spent queued is deducted from the wall-clock
  budget — otherwise a run gets aborted for time another run spent working.
- **Cancellation.** `POST /api/runs/{id}/cancel` and a Stop button. Cooperative: the run stops at the
  next point it would have committed to more work and ends `CANCELLED` with its transcript and
  journal intact, because looking at what it did is usually why it is being stopped. Batches stop
  too, cancelling the repetition in flight rather than waiting it out.

### Phase 6 — making the numbers mean what they appear to ✅
- **Confidence intervals on every pass rate.** Four passes out of five reads as 80%, but five
  observations are consistent with a true rate from about 38% to 96%. Wilson rather than the normal
  approximation, which returns `[1.0, 1.0]` at 5/5 — asserting certainty from five observations.
- **Per-assertion pass rates.** A batch rate collapses every check into one bit, so "60%" could mean
  the model got the arithmetic wrong or that it got it right and exceeded the step limit. Those call
  for opposite responses. The verdicts were already stored; they were simply never read back.
- **A comparison view** (`/eval/compare?a=…&b=…`) that says plainly when two batches *cannot be told
  apart*, and computes what actually differed between them from the stored variants rather than
  trusting the labels. A batch named "temperature 0.9" that also changed the tool list is measuring
  two things at once.
- **Tool version pinning in variants.** The registry has versioned every tool edit since phase 2 so
  that a behaviour change could be attributed to a wording change — but nothing consumed it, so a
  batch silently used whichever description was current that day and the attribution could never be
  made. `toolVersions: {calc_eval: 1}` in a variant closes that.
- **A `seed`.** Temperature zero is not by itself reproducible. Pinning the seed separates variance
  the model contributes from variance the harness does.

### Phase 6 — a contract on the answer ✅
Predictability used to stop at the tool boundary: arguments were validated and repaired, while the
answer — the part anything downstream consumes — was whatever the model felt like writing, and
evaluation could do no better than search it for a substring.

- `responseSchema` on a run holds the final answer to a JSON Schema, with the same machine-readable
  correction a bad tool call gets: the failing field named, the schema repeated, the answer quoted
  back. A Markdown code fence is unwrapped rather than failed — the model did produce the object.
- `constrainOutput` additionally sends `response_format: json_schema`, so llama.cpp compiles the
  schema into a grammar and the output *cannot* be malformed — at the cost of pushing the model down
  a path it might not have chosen. The two are separate flags on purpose: run the same scenario with
  the constraint on and off and the pass rates say which this model does better under.

### Phase 6 — hygiene ✅
- **Metrics** (`/actuator/metrics/harness.*`): run outcomes by terminal status, guardrail firings by
  which one, model latency by finish reason, prompt/completion/**reasoning** tokens, tool latency and
  outcome per tool. Derived from the journal rather than instrumented separately, so the aggregates
  cannot drift out of agreement with the record they summarise.
- **Bounded retry** on an unreachable server, a 429 or a 5xx — a model still loading answers 503 for
  a few seconds, and losing a batch variant to that has nothing to do with the model. Never on a 4xx,
  which will be wrong the second time too. Every retry is journalled as `MODEL_RETRY`, because a
  retry nobody can see hides instability rather than surviving it.
- **Retention.** `DELETE /api/runs/{id}`, `POST /api/runs/prune?olderThanDays=N`, and an optional
  scheduled sweep (`harness.journal.retention-days`, default 0 = keep everything). A run cited by an
  evaluation batch is never pruned: a pass rate whose runs have been deleted is a number with nothing
  underneath it.
- **The stdio MCP path is now tested against a real child process** — see below.
- **Binds to 127.0.0.1.** This process holds an outbound HTTP primitive and can launch stdio MCP
  servers, and has no authentication of its own.

---

## What a comparison actually looks like

One scenario (use `calc_eval` to compute 4877 × 3391), five variants, measured live:

```
variant                    pass    reps
baseline (temp 0)          100%    4/4
temperature 0.9            100%    4/4
no system prompt           100%    4/4
maxOutputTokens 200        100%    3/3
no tools offered             0%    0/3
```

The model is more robust on this task than expected — temperature, the system prompt and a tight
allowance changed nothing. Removing the tool did, and the failure explains itself:

```
FAIL status is FINISHED          status was ABORTED_BUDGET
FAIL called 'calc_eval'          tools called: []
FAIL output contains '16537907'  output was empty
ok   no invalid tool calls       0 invalid tool calls
ok   no LOOP_DETECTED event      no LOOP_DETECTED
```

Without the tool the model tried to multiply six-digit numbers by reasoning and spent its entire
output allowance doing it.

---


## The phase-6 live pass

Everything below was measured against `qwen-4b-6q-unc` loaded in LM Studio, not inferred from tests.
Four things were wrong, and only running it found them.

**The loaded window is not the model's window.** The model reports `max_context_length: 262144` and
was loaded at `context_length: 8192`. A harness compacting against the maximum would be working with
a window thirty-two times larger than the model actually has — which is the silent front-truncation
this was all built to prevent. The resolver reads the loaded instance:

```
The conversation needs about 12082 tokens but only 6016 are available in a 8192-token window,
even after compaction. Load the model with a larger context_length, lower toolOutputMaxChars,
or offer fewer tools.
```

**The estimator was systematically low, and low is the dangerous direction.** A first request
carrying one tool was estimated at 199 tokens and charged 401. Most of that gap is not tokenizer
density — it is the chat template's fixed scaffolding around a tool definition, which no
characters-per-token ratio can predict. So the correction became a single multiplier over the whole
estimate, fitted from `usage.prompt_tokens`, which absorbs template overhead, tool-block boilerplate
and tokenizer density together without having to attribute the gap to any of them. It is now biased
high until it has seen a response, because overestimating compacts a little early while
underestimating loses the front of the conversation.

**Compaction gave up while a droppable exchange sat right there.** With two exchanges, both inside
the "keep the most recent two" guarantee, nothing was evictable and the run ended `ABORTED_CONTEXT`.
The preference is now given up one exchange at a time rather than the run.

**Compaction could return a request larger than the one it was given.** The note explaining the gap
was added after the fitting loop and never counted against the budget: 563 tokens in, 616 out. The
note is now part of the arithmetic, and is dropped entirely when it cannot be afforded.

**And then the note killed every run it appeared in.** It was inserted as its own `system` message
after the task, which Qwen's chat template refuses outright:

```
Jinja Exception: System message must be at the beginning.
```

So compaction -- the guardrail whose entire job is rescuing a long run -- reliably ended one with a
400 instead. The note now folds into the system message at position zero, which also costs less than
a new turn does. Nothing in the unit tests could have caught this: the request was well-formed by
every rule the harness knows, and only the model's own template disagreed.

With that fixed, compaction does what it was built to do. A page fetch into a 2000-token window:

```
step 1  response  prompt_tokens=349
step 2  COMPACTED before=2436 after=755 budget=1172 shortened=1 fits=true
step 2  response  prompt_tokens=532   -> FINISHED, answered from what remained
```

### What the rest of it does

Three runs started at once, against a server that serves one model instance:

```
run        status     queued      generation    total
cf214515   FINISHED        0 ms       6952 ms    6963 ms
f66441eb   FINISHED     6943 ms       6769 ms   13727 ms
c39decdf   FINISHED    13707 ms       6732 ms   20450 ms
```

Generation is ~6.8s in all three — comparable, because queueing is measured separately and deducted
from the wall-clock budget. Without the gate the third run would have recorded twenty seconds of
"latency" that was really waiting.

A seed, at temperature 0.9:

```
seed 42  ->  The vast ocean stretches endlessly to the horizon.
seed 42  ->  The vast ocean stretches endlessly to the horizon.
seed 99  ->  The deep blue waters of the sea stretch endlessly toward the horizon.
```

The answer contract, both ways. Asked for `{answer, unit}` with harness-side checking, the model
answered in prose first and was corrected:

```
MODEL_RESPONSE
OUTPUT_INVALID   reason=malformed_json  got=There are 300 centimetres in 3 metres.
MODEL_REQUEST                            <- correction carrying the schema
MODEL_RESPONSE
RUN_FINISHED     {"answer": 300, "unit": "centimetres"}
```

With `constrainOutput: true` the same question took one step and no correction, because the grammar
made the malformed answer unreachable. Both got 300. That is the comparison the two flags exist to
make, and it is now one field apart.

Cancelling mid-generation returned in 5 ms, ended the run `CANCELLED`, and kept its transcript.

---

## The guardrails

Every run ends in a *named* state that says which guardrail produced it. "It failed" is not a useful
answer; "it looped", "it exhausted its budget" and "it never produced a repairable tool call" call
for completely different fixes.

| Failure mode | Countermeasure | Terminal state |
|---|---|---|
| Never stops calling tools | Step ceiling | `ABORTED_MAX_STEPS` |
| Burns tokens or time | Budgets, clamped into the request so they bind | `ABORTED_BUDGET` |
| Spends its whole allowance reasoning | Truncation detection | `ABORTED_BUDGET` |
| Invents a tool name | Rejected; real names + did-you-mean fed back | recovers, or `ABORTED_UNREPAIRABLE` |
| Malformed or invalid arguments | Schema validation; failing path fed back | recovers, or `ABORTED_UNREPAIRABLE` |
| Cannot act on the correction | Repair ceiling | `ABORTED_UNREPAIRABLE` |
| Repeats the same call forever | Loop detection: nudged once, then stopped | `ABORTED_LOOP` |
| Tool output too large | Truncation with an explicit marker | — |
| Tool fails or is refused | Reported to the model as an outcome, never thrown | recovers |
| Call needs a person | Run parks, resumable from the journal | `AWAITING_APPROVAL` |
| Call is forbidden | Refusal fed back to the model | recovers |
| Conversation outgrew the window | Compaction; oldest tool results first, then old exchanges | `ABORTED_CONTEXT` |
| Answer does not match its schema | Rejected; failing field + schema fed back | recovers, or `ABORTED_UNREPAIRABLE` |
| A person wants it to stop | Cooperative cancellation, transcript kept | `CANCELLED` |

Verified against the live model, not just unit-tested. A representative run list:

```
ABORTED_LOOP        4 steps  4 tool calls   repeated temp_convert({value:100}) 4x, threshold 3
FINISHED            2 steps  1 tool call    egress denied -> model explained the restriction
ABORTED_BUDGET      1 step   0 tool calls   60-token budget, clamped the request, truncated
ABORTED_MAX_STEPS   1 step   1 tool call    ceiling of 1
FINISHED            2 steps  1 tool call    4877 x 3391 = 16537907, via calc_eval
```

---

## What the target model actually does

Measured against `qwen-4b-6q-unc` (Qwen3.5 4B, Q6_K, arch `qwen35`), not assumed from the spec.
These findings drove real design decisions.

**Tool calling is native and clean.** `finish_reason: tool_calls`, a proper `tool_calls[]` array,
arguments as a JSON string. `trained_for_tool_use: true`. The parser chain planned for phase 2 is a
fallback for degradation under context pressure, not the primary path.

**Reasoning arrives in a separate `reasoning_content` field**, not as inline `<think>` tags. Both are
handled, because which one applies depends on the server version and settings.

**The output allowance covers reasoning.** Asked to "say OK" with `max_tokens: 20`, the model spent
all 20 tokens thinking and returned *empty content* with `finish_reason: length`. Read naively this
looks like a model with nothing to say. The harness names it (`truncatedBeforeAnswering`), records it
as `OUTPUT_TRUNCATED`, and reports the run as `ABORTED_BUDGET` rather than as success.

**The reasoning tax is large.** "Name three primes between 10 and 30" → 780 output tokens, 767 of
them reasoning (98%) for a 10-character answer. A 400-word explanation cost 2,758 tokens, 84%
reasoning. Any token budget that ignores reasoning is measuring the wrong thing.

**Streaming reports no usage unless asked.** `stream_options: {include_usage: true}` is required, or
token accounting is silently zero — disabling the budget on exactly the calls most likely to need it.

**A streamed response is not a document.** It is one event envelope per token: 2,758 tokens produced
**820 KB** of raw stream, almost all repeated framing. Raw bodies are capped
(`harness.journal.max-raw-body-chars`, default 32768) with the omission stated explicitly — silently
shortening evidence would be worse than not keeping it.

**The judge inherits the reasoning tax.** A model-graded assertion given a 1024-token allowance
reliably spent all of it reasoning about the rubric and returned no verdict — failing every judged
assertion for reasons that had nothing to do with the answer being graded. The default is now 4096
(`harness.eval.judge.max-output-tokens`). Concrete evidence for why deterministic assertions are the
default and the judge is the opt-in.

**Tool calling is reliable, and the model prefers the schema over the description.** Given a tool
whose description invited a field its schema forbade, it sent only what the schema allowed. Argument
quality has been good enough that the parser chain and lenient mode have not yet been needed in
anger — which is itself worth knowing, and worth re-checking at higher temperature.

**It will invent a plausible value rather than admit it lacks one.** Asked to call a tool requiring
a `caller_api_key` it had no way to know, it supplied `"test-api-key"` and proceeded. Schema
validation cannot catch this — the value is a well-typed string — which is precisely why the
execution-time egress policy is a separate layer rather than an extension of validation.

---

## Running it

```bash
lms server start
```

```bash
./mvnw spring-boot:run
```

Console at <http://localhost:8080> · health at `/actuator/health` · metrics at
`/actuator/metrics/harness.runs` (and `harness.guardrail.fired`, `harness.model.latency`,
`harness.model.tokens`, `harness.tool.latency`) · H2 console at `/h2-console`.

It binds to `127.0.0.1`. This process can make outbound HTTP requests with arguments the model chose
and can launch stdio MCP servers, and it authenticates nobody; put something in front of it before
changing that.

Set the model in `application.yaml` (`harness.lm-studio.default-model`); discover keys with
`GET /api/models`. It is configuration, never a constant, because swapping the model under test is
the point.

```bash
./mvnw test
```

Tests never need a running model server: the client is exercised against payloads recorded from the
real model via `MockRestServiceServer`, and the loop against a scripted `ChatModelClient` so every
guardrail is provoked deterministically.

They do not need an MCP server either, or a Node/uv/Docker toolchain to run one.
`McpIntegrationTest` stands up a real MCP server *inside the test JVM* using the SDK's server side,
over the same streamable HTTP transport a remote server would use. `McpStdioIntegrationTest` covers
the stdio path properly by launching a **real child process** — `java` running a small server
fixture from the test classpath — because everything stdio does differently happens outside this
JVM: spawning the executable, framing JSON-RPC over pipes, draining stderr, and reaping the child.
`java` is allowlisted for that test alone; the production default allows nothing.

A batch, a comparison and a stop, from the command line:

```bash
curl -X POST localhost:8080/api/eval/batches -H 'Content-Type: application/json' \
  -d '{"scenarioId":"...","label":"pinned tool v1","repetitions":20,"variant":{"toolVersions":{"calc_eval":1},"seed":7}}'
```

```bash
curl "localhost:8080/api/eval/batches/compare?a=<batchA>&b=<batchB>"
```

```bash
curl -X POST localhost:8080/api/runs/<runId>/cancel
```

---

## Notes for whoever picks this up

- **Spring Boot 4 modularised autoconfiguration.** `flyway-core` alone leaves Flyway on the classpath
  and never wired up — the app starts, `ddl-auto: validate` passes while there are no entities, and
  nothing looks wrong until a query hits a table that was never created. Use
  `spring-boot-starter-flyway`. `SchemaMigrationTest` guards this.
- **Jackson 3** (`tools.jackson.databind`) is the default; annotations stay at
  `com.fasterxml.jackson.annotation`. Use the `mcp-json-jackson3` MCP module in phase 3.
  `networknt:json-schema-validator` 3.x is also Jackson 3, so there is one tree throughout.
- **`@Transactional` on a self-invoked method does nothing** — Spring's advice lives in a proxy.
  `JournalWriter` exists as a separate bean for exactly this reason.
- **Live-only events carry `seq = -1`.** The journal sequence is the cursor clients resume from, so
  letting unstored events consume numbers would leave permanent holes in it.
- **Page scripts must be external.** The Thymeleaf layout passes only `~{::section}`, so a `<script>`
  sibling in the body is silently discarded.
- **Anything a model reads must be locale-pinned.** `json-schema-validator` localises its messages to
  the JVM default, so on this machine a rejection came back as
  `необхідна властивість 'expression' не знайдена` — text fed straight to the model as the correction
  it is meant to act on. Pinned to `Locale.ENGLISH`; the same applies to `String.format("%,d")`,
  which is why `RawBody` uses `Locale.ROOT`.
- **A budget checked only between steps is advisory.** One call could spend far more than the whole
  allowance and still report success. `AgentRunner` clamps the request's output allowance to what the
  budget has left, which is what makes it binding.
- **Comparing "has this changed" must include the executor config.** MCP re-sync originally compared
  only description and schema, so a server deleted and recreated under the same label left its
  imported tools pointing at a dead server id — identical to the model, broken at call time. Caught
  by `McpIntegrationTest`, which is the argument for writing it.
- **Construct `HarnessProperties` via `TestProperties`** (test sources). The record gained a new
  section three times and broke every direct construction each time; the fixture localises that.
- **A rule that ties with the rule it overrides does nothing.** An "always allow" approval originally
  wrote its policy at the *same* priority as the rule demanding approval, so the winner came down to
  tie-breaking and the next run parked again — the grant appeared to work and silently did not. The
  standing rule's priority is now derived from the rule it overrides, and repository ordering breaks
  ties by name so behaviour cannot vary between executions.
- **Resuming must restore the guardrails, not just the conversation.** A run resumed with a fresh
  loop detector could loop forever precisely because a human paused to look at it. Counts are
  replayed from the journal's `TOOL_CALL_PARSED` events.
- **`run` usage is assigned, not accumulated.** A resumed run's budget tracker is seeded with what
  was already spent, so adding its total again would double-count everything before the pause.
- **Spring Data does not scan nested repository interfaces.** Grouping them inside a holder class
  looked tidy, produced no beans at all, and failed at context startup. Keep them top-level.
- **Two parallel lists that must stay in sync will not.** `Transcript` briefly kept messages and
  their step numbers separately, and the system-prompt constructor appended to only one — so every
  transcript silently failed to persist, caught and logged rather than thrown, until a fork came
  back empty. One list of pairs cannot desynchronise from itself. `TranscriptTest` guards it.
- **Swallowing an exception to protect a run must still be loud.** The above was invisible precisely
  because the failure was handled gracefully.
- **A resume must not reset the step ceiling.** Forking correctly gets a fresh allowance from the
  position it inherited; a resumed run must not, or a run that parks repeatedly would exceed
  `maxSteps` without bound — pausing to ask a human about it would make it *less* bounded. The
  ceiling is set by the entry point rather than derived, because the two need different answers.
- **Tools must never follow HTTP redirects.** `HttpEgressPolicy` checks the host the model asked
  for; following a 302 would let an allowlisted host hand the harness to one that is not, inside a
  request the check has already passed. `ToolHttpClientConfig` is separate from the LM Studio client
  for exactly this reason — that client *does* follow redirects, because its address is
  configuration rather than a model-supplied argument.

## Known limits

- **Batch token totals exclude the judge.** They are the runs' tokens. A model-graded assertion
  spends tokens of its own, so a judged scenario costs more than its batch reports.
- **Batches assume they have the model to themselves.** Repetitions run sequentially for that
  reason; starting a manual run alongside a batch will distort its latency figures.
- **The judge's own allowance is a harness-wide setting, not a per-batch variant.** So two batches
  that differ only in `harness.eval.judge.max-output-tokens` record *no* difference at all, and the
  comparison view correctly reports them as having identical variants. The comparison found this in
  the phase-5 batches, which is what it is for. Until the setting moves into the variant, a judged
  comparison has to be read with that in mind.
- **Some early batches were run at two repetitions.** The intervals on those are enormous — 2/2 is
  consistent with a true rate from 34% to 100% — so those pairs are not separable, whatever their
  labels suggest. The batch pages now say so.
- **The retry has not been triggered against a real 503.** Inducing one means restarting LM Studio
  underneath a run. Unit-tested against a scripted failure.
- **`file_read` ships inert and has only been exercised against temporary directories.** The policy
  is covered -- deny-by-default, traversal out via `..`, symlinks, truncation -- but no real corpus
  has been read through it.

### Phase 7 — the HTTP tool, and the allowlist that was doing two jobs ✅

**One allowlist was conflating two protections worth very different amounts.** Keeping the harness
off the *private* network — cloud metadata, loopback services, the LAN, this application's own H2
console — is the one that matters, and it never needed a list of domains: it is an address check.
Keeping it off the *public* internet is the part that costs a line of configuration per site, and it
buys something much narrower. `harness.tools.http.mode` now separates them:

| mode | public hosts | private addresses |
|---|---|---|
| `ALLOWLIST` | only those named | refused unless `allow-private-addresses` |
| `PUBLIC` | any | refused unless `allow-private-addresses` |
| `OPEN` | any | any |

`PUBLIC` is the answer to "I do not want to whitelist every domain" without giving up the check that
does the work. What it gives up is real but narrow: a fetched page is untrusted text, and a page that
instructs the model to fetch `somewhere-else/?data=…` now has somewhere to send it. Naming a host
does *not* exempt it from the address check in any mode — an allowlist entry can be a wildcard, and
`*.example.com` is not consent to reach whatever `internal.example.com` resolves to.

**`http_fetch` was honest and nearly useless.** It returned the raw body, and a page is mostly markup.

- **HTML is reduced to text.** Measured on github.com: **575,809 characters became 7,654** —
  seventy-five times smaller, and the smaller one is the one containing the answer. The title and
  meta description come back as their own fields. JSON passes through untouched so `json_extract`
  can read it; `raw: true` turns extraction off.
- **Truncation is recoverable.** It reports `totalChars` and takes an `offset`, and when it truncates
  it says `continueWithOffset`. Before, a model that needed something further down was simply stuck.
- **Redirects are followed**, which they were not — so a bare domain or an `http://` link failed
  outright. Every hop is re-checked against the egress policy, which is a *stronger* guarantee than
  refusing them: the old rule checked the URL the model asked for, this checks every URL contacted.

Live, on the loaded model, with no allowlist entry for either host:

```
"What does https://example.com/ say?"     -> quoted its first sentence, 2 steps
"Fetch http://github.com, give the title" -> followed the redirect, read the title
```

**The tool detail page had been returning 500 the whole time.** A Thymeleaf expression built a curl
example with a shell line continuation, and a backslash before a quote inside a string literal escapes
the quote — so the expression never terminated and the template failed to parse. Every page sweep in
this project had checked the tool *list* and never a tool, so the page where you read version history,
edit a description and now reseed a built-in was broken and unnoticed. The sweep now covers a detail
page for every registered tool.

**Seeding never overwrites, so an improved built-in could not reach a registry that already had one.**
The old schema stayed, and the new `maxChars`/`offset`/`raw` arguments came back rejected as unknown
properties. `POST /api/tools/{name}/reseed`, and a button on the tool page, takes up the compiled
definition as a new version — explicit, and reversible by picking the older version back up.

### Phase 7 — learning from how the model uses a tool ✅

Not learned weights — a 4B local model is not going to acquire any. What the harness *can* do is read
its own journal the other way round. It has been recording every rejected call since phase 2, with
the failing field path and the exact correction sent back; that evidence was written, shown to the
model, and then never looked at again, because the journal is organised per run and this is the one
question that is not.

**The friction report** (`GET /api/tools/{name}/learning`, and a section on the tool page) turns "the
model is bad at `unit_convert`" into "it omits `to` in six calls out of seven, and here are the six
argument objects it actually wrote". Which argument fails is the line that names the fix; a rejection
count only says there is one. Below five calls it says so rather than letting you act on noise —
33% off three calls is the same error the confidence intervals prevent one page over.

**Examples harvested from calls that worked** go into the schema's `examples` keyword, which is
standard JSON Schema and travels to the model inside `parameters` with no extra plumbing. Nothing is
invented: every example is a call the model itself made and the validator accepted. Live, it lifted
three distinct calls straight out of the journal:

```json
[{"value":3,"from":"m","to":"cm"},{"value":12,"from":"km","to":"m"},{"value":500,"from":"g","to":"kg"}]
```

**A description drafted by a model** from the current wording, the schema and the observed failures.
This is the part that can mislead you, and it did on the first live attempt — see below.

**Nothing is applied automatically.** Every route returns a *proposal*; applying it is a separate call
that goes through the ordinary versioned save, so the previous definition stays in the history. The
reason tools have been versioned since phase 2 was to be able to say "this wording, that pass rate",
and a coach that edited tools directly would spend that in a week.

#### Two things measured while building it

**The drafting model is the reasoning tax at its most extreme.** At a 4096-token allowance it spent
4096 of 4096 on reasoning and finished on `LENGTH` without writing a word. At 6000 it succeeded — with
**5157 output tokens, 5069 of them reasoning**: 98% deliberation for 88 tokens of description. An
empty draft now reports exactly that rather than "it returned nothing", because a failure that does
not name its cause is the thing this project keeps removing.

**The draft asserted a failure the evidence did not contain.** Its proposed wording claimed "models
often omit these required strings" — while the report it was given showed *zero* rejected calls. The
model wrote a plausible-sounding rationale rather than reading the numbers. That is precisely why the
draft is shown and not applied, and why the note saved with an applied description says *not yet
measured*: the only thing that settles whether a description is better is a batch against the version
it replaced.

### Fixed since the live pass

- **Compaction reclaiming a large amount is now measured**, once `http_fetch` had an allowlisted
  host: a page fetch into a 2000-token window compacted 2436 tokens to 755 and the run finished
  correctly. This also uncovered the system-message bug above.

### Fixed since phase 5
- **The stdio MCP path is now covered against a real child process.** Rather than requiring Node, uv
  or Docker on every machine that builds this, the test launches `java` running a small MCP server
  fixture from the test classpath, and exercises the handshake, a tool call, an error result and the
  reaping of the child. It immediately reproduced the classic stdio failure: anything a library logs
  to `System.out` lands in the middle of the JSON-RPC stream and the handshake dies with a parse
  error naming a character rather than a cause. The fixture hands the transport the real stdout and
  points `System.out` at stderr — which is the same thing any real stdio MCP server has to do.
