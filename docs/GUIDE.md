# Using the harness

Everything this thing does, in the order you would actually meet it. The README explains *why* the
design is the way it is; this is *how* to drive it.

---

## 1. Start it

```bash
lms server start
```

```bash
./mvnw spring-boot:run
```

Console at <http://127.0.0.1:8080>. It binds to localhost only — this process makes outbound
requests with arguments the model chose and can launch stdio MCP servers, and it authenticates
nobody.

Check it found the model:

```bash
curl localhost:8080/actuator/health
```

`lmStudio: UP` with your model in `loadedInstances` means you are ready. If it says `unreachable`,
LM Studio is not running.

**Set the model** in `application.yaml` under `harness.lm-studio.default-model`. Discover the keys
with `GET /api/models` or the **Models** page.

---

## 2. The console, one line each

| Page | What it is for |
|---|---|
| `/` | Dashboard: server reachability, loaded models, recent runs |
| `/models` | List, load and unload models — and set `context_length`, which only exists here |
| `/tools` | The tool registry: create, edit, disable, version history, test without a model |
| `/mcp` | MCP servers: add, connect, sync their tools into the registry |
| `/policies` | Which tool calls run automatically, which need you, which are forbidden |
| `/runs` | Every run, and the live trace of one |
| `/eval` | Scenarios, batches, pass rates, comparisons |

---

## 3. Your first run

From the **Runs** page, or:

```bash
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' \
  -d '{"input":"What is 4877 multiplied by 3391?","tools":["calc_eval"]}'
```

It returns immediately with a run id. Open `/runs/{id}` to watch it happen, or follow
`GET /api/runs/{id}/stream` (Server-Sent Events).

**Every setting is per-run.** The ones you will reach for:

| Field | Default | What it does |
|---|---|---|
| `input` | — | the question. Required |
| `systemPrompt` | none | the system message |
| `tools` | `[]` | which tools the model is shown. Empty means a plain completion |
| `temperature` | `0.0` | sampling temperature |
| `seed` | none | fixes the sampler, so two runs differ only where you made them differ |
| `maxOutputTokens` | `2048` | output allowance — **this covers reasoning**, see §12 |
| `maxSteps` | `12` | loop ceiling |
| `tokenBudget` / `wallClockBudgetMs` | none | total spend, clamped into each request so it binds |
| `maxRepairsPerStep` | `2` | invalid tool calls tolerated before giving up |
| `loopThreshold` | `3` | identical calls before the run is stopped |
| `toolOutputMaxChars` | `4000` | how much of a tool's answer goes back to the model |
| `lenientArguments` | `false` | repair malformed arguments instead of rejecting them |
| `contextLength` | asks the server | the model's window |
| `responseSchema` / `constrainOutput` | none | a contract on the answer, see §8 |
| `toolVersions` | current | pin tools to stored versions, see §5 |
| `stream` | `true` | token deltas on the live view |

---

## 4. Reading a run

The trace is the point of the whole project. Each row is a journal entry, and the raw request and
response bodies are there verbatim — not a reconstruction of what was probably sent.

| Event | Means |
|---|---|
| `MODEL_REQUEST` | the exact bytes sent, plus the token estimate and any time spent queued |
| `MODEL_RESPONSE` | the exact bytes back, with usage and latency |
| `TOOL_CALL_PARSED` | a call the harness extracted and validated |
| `TOOL_CALL_INVALID` | a call that failed validation, **with the correction sent back** |
| `TOOL_EXEC_*` | the tool starting, succeeding or failing |
| `CONTEXT_COMPACTED` | history was reduced to fit the window, and by how much |
| `OUTPUT_INVALID` | the final answer did not match the required schema |
| `LOOP_DETECTED` / `BUDGET_EXCEEDED` / `OUTPUT_TRUNCATED` | a guardrail firing |
| `MODEL_RETRY` | a transient failure was retried |
| `TOOL_APPROVAL_REQUIRED` / `APPROVAL_GRANTED` / `APPROVAL_DENIED` | a person in the loop |
| `RUN_FINISHED` | exactly one per run, carrying its terminal status |

**Every run ends in a named state**, never a bare success or failure:

`FINISHED` · `AWAITING_APPROVAL` · `ABORTED_MAX_STEPS` · `ABORTED_UNREPAIRABLE` · `ABORTED_LOOP` ·
`ABORTED_BUDGET` · `ABORTED_CONTEXT` · `CANCELLED` · `FAILED`

Stop a run at any time — the transcript and journal are kept:

```bash
curl -X POST localhost:8080/api/runs/<id>/cancel
```

---

## 5. Tools

### What ships

| Tool | Argument shape | Notes |
|---|---|---|
| `clock_now` | none | the "is anything working at all" tool |
| `calc_eval` | string | arithmetic, hand-written parser — not a script engine |
| `text_stats` | string | characters, words, lines, sentences |
| `encode_text` | string + enum | base64/hex/url, sha256/sha512/md5 |
| `stats_summary` | **array** of numbers | count, sum, mean, median, min, max, both std devs |
| `unit_convert` | number + two **enums** | length, mass, time, data, temperature |
| `date_diff` | two dates + enum | how far apart, in the unit you ask for |
| `date_shift` | date + integer + enum | move a date; negative goes backwards |
| `json_extract` | **nested object** + string | JSON Pointer into another tool's answer |
| `http_fetch` | string + paging | HTML stripped to text; **gated**, see below |
| `file_read` | string | **gated** by a directory allowlist |

The shapes are deliberate. Whether a 4B model can emit a well-formed JSON *array* is a different
question from whether it can emit a string, and having one tool of each kind lets a failure be
attributed to the shape rather than to the task.

`json_extract` is the one that makes the others compose: it pulls `/result` out of one tool's answer
so the model does not have to restate the number from memory — which is exactly where a small model
quietly corrupts a value it had right a moment ago.

### The two gated ones

The model chooses the argument, so both are gated — but the HTTP gate has three settings now, because
one allowlist was doing two jobs worth very different amounts.

```yaml
harness:
  tools:
    http:
      mode: PUBLIC              # ALLOWLIST | PUBLIC | OPEN
      allowed-hosts: []         # consulted only in ALLOWLIST mode
      blocked-hosts: []         # never reachable, in any mode but OPEN
      allow-private-addresses: false
    files:
      allowed-directories: []   # empty = no file readable
```

| mode | public hosts | private addresses |
|---|---|---|
| `ALLOWLIST` | only those named | refused unless `allow-private-addresses` |
| `PUBLIC` | any | refused unless `allow-private-addresses` |
| `OPEN` | any | any |

**The protection worth having is the address row, not the host row.** `PUBLIC` still refuses
`localhost`, `10.x`, `192.168.x` and `169.254.169.254`, and that costs no configuration at all.
Naming a host does not exempt it — an allowlist entry can be a wildcard, and `*.example.com` is not
consent to reach whatever `internal.example.com` resolves to.

What `PUBLIC` gives up is narrower than it looks but is real: a page the model fetches is untrusted
text, and a page that tells the model to fetch `somewhere-else/?data=…` now has somewhere to send it.
Choose it when the prompts and the pages are your own; keep `ALLOWLIST` when they are not.

For files, paths are resolved to their real location with symlinks followed before being compared, so
neither `..` nor a link inside an allowed directory escapes it.

### Reading a page without spending the window on markup

`http_fetch` strips HTML to prose — on github.com, 575,809 characters down to 7,654. It returns a
small object rather than a wall of text:

```json
{"url":"https://example.com/","status":200,"contentType":"text/html; charset=utf-8",
 "format":"text","title":"Example Domain","totalChars":129,"offset":0,
 "returnedChars":129,"truncated":false,"content":"Example Domain ..."}
```

- `title` and `description` are separate fields, and are often the whole answer for twenty tokens.
- Long pages truncate **recoverably**: the reply carries `totalChars` and `continueWithOffset`, so
  reading further down is a second call rather than a dead end.
- JSON passes through untouched, so `json_extract` can read a field straight out of it.
- `raw: true` turns extraction off for when the markup *is* the question.
- Redirects are followed, and every hop is re-checked against the egress policy.

### Learning from how the model uses a tool

The journal has recorded every rejected call since phase 2 — the failing field path, and the exact
correction sent back. The tool page now reads that back the other way round: not "what happened in
this run" but "how does the model cope with this tool".

```bash
curl localhost:8080/api/tools/unit_convert/learning
```

- **Which argument fails**, ranked. `$/to (required) — 6 times` names the fix; a rejection count only
  says there is one.
- **What the model actually wrote**, verbatim, both refused and accepted.
- Below five calls it tells you the sample is too small to act on, for the same reason a
  five-repetition batch cannot separate 100% from 80%.

Two things you can do with it, neither applied until you say so, and both saving a **new version**:

```bash
curl -X POST localhost:8080/api/tools/unit_convert/learning/examples      # harvest examples
curl -X POST localhost:8080/api/tools/unit_convert/learning/description   # draft, do not apply
```

**Examples** are real calls the model made and the validator accepted, written into the schema's
`examples` keyword — standard JSON Schema, so it reaches the model inside `parameters` unchanged.
Nothing is invented.

**A drafted description** is written by a model from the evidence. Read it before applying: on the
first live attempt the draft asserted that "models often omit these required strings" while the
report it was given showed *zero* rejected calls. It wrote a plausible rationale rather than reading
the numbers. Applying one saves a version whose change note says *not yet measured* — and it means
it. Run a batch against the previous version before believing the new wording is better; that is what
the version history has been for since phase 2.

Drafting is slow and pays the reasoning tax heavily: on this model it took 5157 output tokens, 5069
of them reasoning, for 88 tokens of description. If it comes back empty it tells you exactly that,
and `harness.tools.learning.model-key` points it at a different model.

### When you improve a built-in in code

Seeding runs once and never overwrites — the registry is the authority on what the model is shown, so
a description you tuned is not reverted on the next restart. The cost is that a built-in improved in
code keeps offering its old schema, and a new argument comes back rejected as an unknown property.

```bash
curl -X POST localhost:8080/api/tools/http_fetch/reseed
```

Or the button on the tool's page. It saves the compiled definition as a **new version**, so the one
it replaced is still in the history and reverting is picking it back up.


### Making your own

Declarative HTTP tools need no code — method, URL template, headers, body template:

```bash
curl -X POST localhost:8080/api/tools -H 'Content-Type: application/json' -d '{
  "name": "get_repo",
  "description": "Looks up a GitHub repository.",
  "parametersSchema": {"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"}},"required":["owner","repo"]},
  "executorType": "HTTP",
  "executorConfig": {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}"}
}'
```

### Test a tool with no model in the way

```bash
curl -X POST localhost:8080/api/tools/get_repo/test \
  -H 'Content-Type: application/json' -d '{"owner":"anthropics","repo":"anthropic-sdk-python"}'
```

The body is the arguments object exactly as the model would send it, and it goes through the same
validator the loop uses — so a rejection here is the rejection the model would have received, verbatim.

Do this first, always. It separates "the tool is broken" from "the model will not call it".

### Versions

**Every edit creates a version.** That is not bookkeeping — it is what lets you attribute a change in
behaviour to a change in wording. Reword a description, run the same scenario, compare. Pin a run or
a batch to a stored version so the comparison stays honest:

```json
{"toolVersions": {"calc_eval": 1}}
```

A pinned version is offered even if it has since been disabled: the run asked for that exact text.

---

## 6. MCP servers

The harness runs the MCP client itself, so MCP tools go through the same validation, policy, journal
and truncation as everything else.

Add a server on `/mcp` (or `POST /api/mcp/servers`), connect, then **sync** to import its tools into
the registry, namespaced by server label (`myserver_search`). An allowlist imports only the tools you
name.

- **Streamable HTTP / SSE**: give it a URL.
- **stdio**: the executable must be named in `harness.mcp.stdio.allowed-commands`. Empty by default,
  because launching one runs a program — without that, an API for managing tools is also an API for
  running commands. Arguments are not constrained: an allowed interpreter runs whatever it is handed,
  so the allowlist is a statement about *which programs you trust*.

---

## 7. Policy and approvals

Rules match tool names by glob, in priority order, and say `AUTO`, `REQUIRE_APPROVAL` or `DENY`.
They can also set a per-tool timeout and a per-run call limit.

```bash
curl -X POST localhost:8080/api/policies -H 'Content-Type: application/json' \
  -d '{"name":"http needs a person","toolNamePattern":"http_*","approvalMode":"REQUIRE_APPROVAL","priority":100}'
```

Lower `priority` wins. `timeoutMs` and `rateLimitPerRun` are optional on the same rule.

A run that hits `REQUIRE_APPROVAL` **parks**: it ends its thread in `AWAITING_APPROVAL` carrying the
call, and survives a restart. Decide on the run page or:

```bash
curl -X POST localhost:8080/api/approvals/<approvalId>/approve \
  -H 'Content-Type: application/json' -d '{"scope":"RUN","note":"checked the URL"}'
```

`POST /api/approvals/{id}/deny` takes the same body. `scope` is:

- `CALL` — this invocation only; the next identical call asks again.
- `RUN` — this tool, for the rest of this run.
- `ALWAYS` — this tool from now on, which **writes an AUTO policy rule**, so the decision shows up on
  the policy page rather than living invisibly inside one run.

A denial is reported to the model as a tool failure, so it can take another route rather than dying.

---

## 8. A contract on the answer

Without this, predictability stops at the tool boundary: arguments are validated and repaired, while
the answer — the part anything downstream consumes — is whatever the model felt like writing.

```bash
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{
  "input": "How many centimetres are in 3 metres?",
  "responseSchema": {"type":"object","properties":{"answer":{"type":"number"},"unit":{"type":"string"}},"required":["answer","unit"]},
  "constrainOutput": false
}'
```

Two independent mechanisms, and which is better is a measurement, not a settled question:

- **`responseSchema` alone** — the harness checks the answer and sends back a correction naming the
  failing field, up to `maxOutputRepairs` (default 2). The model gets to try again.
- **`constrainOutput: true`** — the schema also goes to the server as `response_format: json_schema`,
  so llama.cpp compiles it into a grammar and a malformed answer is *unreachable* — at the cost of
  pushing the model down a path it might not have chosen.

Run the same scenario with the flag on and off; the pass rates tell you which this model does better
under. A Markdown code fence around the JSON is unwrapped rather than failed.

---

## 9. Evaluation — the part that earns "predictable"

**The unit is a batch, not a run.** For a 4B model one passing run is an anecdote.

Define a scenario as YAML and import it on `/eval`:

```yaml
name: arithmetic-via-tool
description: The model must use the tool rather than compute it itself.
input: What is 4877 multiplied by 3391? Use the tool, then state just the number.
systemPrompt: Use the calc_eval tool for any arithmetic.
tools:
  - calc_eval
config:
  maxSteps: 6
  maxOutputTokens: 1200
assertions:
  - kind: STATUS
    value: FINISHED
  - kind: TOOL_CALLED
    value: calc_eval
  - kind: OUTPUT_CONTAINS
    value: '16537907'
  - kind: NO_INVALID_TOOL_CALLS
  - kind: NO_EVENT
    value: LOOP_DETECTED
```

**Assertion kinds.** Twelve deterministic ones, all read from the run's own journal:
`STATUS` · `OUTPUT_CONTAINS` · `OUTPUT_NOT_CONTAINS` · `OUTPUT_MATCHES` (regex) · `TOOL_CALLED` ·
`TOOL_NOT_CALLED` · `TOOL_CALLED_WITH` · `MAX_TOOL_CALLS` · `MAX_STEPS` · `MAX_TOKENS` ·
`NO_INVALID_TOOL_CALLS` · `NO_EVENT` (which is how you say *and it did not loop*). Plus `JUDGE`, a
model-graded check for things substring matching cannot reach — every verdict carries a
`reproducible` flag so a judged result never blends in with the deterministic ones.

**Run a batch**, holding the scenario fixed and changing exactly one thing:

```bash
curl -X POST localhost:8080/api/eval/batches -H 'Content-Type: application/json' -d '{
  "scenarioId": "...", "label": "temperature 0.9", "repetitions": 20,
  "variant": {"temperature": 0.9}
}'
```

A variant can override any run field, including `seed`, `toolVersions`, `responseSchema` and
`constrainOutput`.

**Read the result properly.** The batch page gives you:

- the pass rate **with a 95% confidence interval** — 4/5 is "80%", but five observations are
  consistent with anything from 38% to 96%;
- **per-assertion pass rates**, because a batch rate collapses every check into one bit and cannot
  say *which* check failed.

**Compare two batches** — `/eval/compare?a=…&b=…` or `GET /api/eval/batches/compare`. It says plainly
when two batches **cannot be told apart**, and computes what actually differed from the stored
variants rather than trusting the labels. A batch named "temperature 0.9" that also changed the tool
list is measuring two things at once, and the label would never say so.

Stop a long batch with `POST /api/eval/batches/{id}/cancel`; the repetition in flight stops too, and
what was already measured stands.

---

## 10. Forking

"What if it had been asked differently at step 3", without repeating everything before it:

```bash
curl -X POST 'localhost:8080/api/runs/<id>/fork?uptoStep=3' \
  -H 'Content-Type: application/json' -d '{"systemPrompt":"Be terse.","temperature":0.9}'
```

The transcript is rebuilt to that point and the new run continues under whatever settings you give,
linked to its parent. A fork of a fork still cuts where you asked, because step numbering continues
across the lineage.

---

## 11. The context window

The harness decides what the model sees, rather than letting the server truncate an overlong request
from the *front* — which would take the system prompt and the task first, and make a harness failure
look like the model losing the plot.

- The window comes from the **loaded instance**, not the model's maximum. Your model reports a
  262144 maximum and is loaded at 8192; only the second number is real. Set it at load time on
  `/models`, or override per run with `contextLength`.
- When history no longer fits: oldest **tool results are shortened** first, then whole old
  **exchanges are dropped**. Never the system prompt, the task, or the most recent exchanges — and
  never one half of a tool-call/tool-result pair.
- Nothing here calls the model. A summarising compactor would fold the model's own variance into the
  instrument.
- The transcript stays whole; only the request is reduced, so replay and forking still see
  everything.
- If it still cannot fit, the run ends `ABORTED_CONTEXT` with the remedy named, rather than sending a
  request that will be silently mangled.

---

## 12. Things that will bite you

**The output allowance covers reasoning.** This is the single most common surprise. Ask this model a
trivial question with `maxOutputTokens: 700` and it can spend all 700 thinking and return nothing —
reported honestly as `ABORTED_BUDGET` with `OUTPUT_TRUNCATED` in the journal, not as an empty
success. If a run comes back empty, raise the allowance first.

**One generation at a time.** LM Studio serves a single model instance, so a second concurrent
request queues rather than running alongside. The harness gates generation
(`max-concurrent-requests`, default 1) and records queue time separately, so a queued run is delayed
but not charged for it. Three runs started together, measured:

```
run 1   queued     0 ms   generation 6952 ms
run 2   queued  6943 ms   generation 6769 ms
run 3   queued 13707 ms   generation 6732 ms
```

**Temperature 0 is not reproducible on its own.** Use `seed` when you need two runs to differ only
where you made them differ.

**A judge is not reproducible either.** Point it at a different model
(`harness.eval.judge.model-key`) if you want the instrument to be even partly independent of what it
measures.

---

## 13. Operating it

**Metrics** — `/actuator/metrics/harness.runs` (by terminal status), `harness.guardrail.fired` (by
which), `harness.model.latency`, `harness.model.tokens` (prompt/completion/**reasoning**),
`harness.tool.latency` and `harness.tool.calls` (per tool, per outcome). All derived from the journal,
so they cannot drift from the record they summarise.

**Retention** — the journal holds the exact bytes of every request and response, so it grows fast.

```bash
curl -X DELETE localhost:8080/api/runs/<id>
```

```bash
curl -X POST 'localhost:8080/api/runs/prune?olderThanDays=30'
```

Or set `harness.journal.retention-days` for a scheduled sweep (`0`, the default, keeps everything).
A run cited by an evaluation batch is never pruned automatically — a pass rate whose runs have been
deleted is a number with nothing underneath it.

**Retries** — an unreachable server, a 429 or a 5xx is retried once (`harness.lm-studio.max-retries`).
Never a 4xx, which will be wrong the second time too. Every retry is journalled.

---

## 14. Settings worth knowing

```yaml
harness:
  lm-studio:
    default-model: "qwen-4b-6q-unc"
    max-concurrent-requests: 1     # LM Studio serves one instance
    max-retries: 1
  journal:
    max-raw-body-chars: 32768      # a streamed response is thousands of SSE envelopes
    retention-days: 0              # 0 keeps everything
  context:
    default-length: 0              # 0 = ask the server what it loaded with
    keep-recent-exchanges: 2
    min-tool-result-chars: 240
  tools:
    http:
      mode: ALLOWLIST              # ALLOWLIST | PUBLIC | OPEN
      allowed-hosts: []            # the gate in ALLOWLIST mode
      blocked-hosts: []            # never reachable except in OPEN
      allow-private-addresses: false   # the check that matters, in every mode
    files:
      allowed-directories: []      # empty = no file readable
    learning:
      sample-events: 2000          # a window of recent journal entries, not all history
      max-output-tokens: 6000      # measured: at 4096 this model reasons and never writes
      model-key: ""                # empty = the model under test drafts its own descriptions
  mcp:
    stdio:
      allowed-commands: []         # empty = no server can be launched
  eval:
    judge:
      max-output-tokens: 4096      # the judge pays the reasoning tax too
      model-key: ""                # empty = graded by the model under test
```

---

## 15. A worked example, start to finish

```bash
# 1. Does the tool work at all, with no model involved?
curl -X POST localhost:8080/api/tools/stats_summary/test \
  -H 'Content-Type: application/json' -d '{"numbers":[4,8,15,16,23,42]}'

# 2. Does the model call it?
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' \
  -d '{"input":"What is the median of 4, 8, 15, 16, 23 and 42?","tools":["stats_summary"]}'

# 3. Read the trace at /runs/<id> — which tool it called, with what arguments.

# 4. Make it a scenario, import it on /eval, and run 20 repetitions.

# 5. Run a second batch changing exactly one thing.

# 6. Compare them, and believe the answer only if the intervals do not overlap.
```

That last step is the whole point. Everything before it makes the model's behaviour bounded and
legible; only the comparison tells you whether a change did anything.
