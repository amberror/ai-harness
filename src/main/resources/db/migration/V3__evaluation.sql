-- =====================================================================================
-- Phase 5: evaluation and experiments.
--
-- This is the phase that earns the word "predictable". Everything before it makes the model's
-- behaviour bounded and legible; none of it says how *often* the model does the right thing, and
-- with a 4B model at temperature 0 the answer is still not 100%. A single successful run proves
-- almost nothing, which is why the unit of work here is a batch of repetitions rather than a run.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- A scenario: one thing to ask, and what "right" means for it.
--
-- Stored rather than kept in files so it is editable in the console like everything else, with
-- import and export to YAML for committing next to the code it measures.
-- -------------------------------------------------------------------------------------
CREATE TABLE eval_scenario (
    id              UUID            PRIMARY KEY,
    name            VARCHAR(120)    NOT NULL,
    description     VARCHAR(1000),
    input           CLOB            NOT NULL,
    system_prompt   CLOB,
    tools_json      CLOB,                       -- JSON array of tool names to expose
    config_json     CLOB,                       -- guardrail and sampling overrides
    assertions_json CLOB            NOT NULL,   -- JSON array of assertion specifications
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_eval_scenario_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- A batch: one scenario, one variant, run N times.
--
-- The variant is what makes this an experiment rather than a test. Holding the scenario fixed and
-- changing exactly one thing -- a system prompt, a tool description, the repair policy, the
-- temperature -- is the only way a change in pass rate can be attributed to anything.
-- -------------------------------------------------------------------------------------
CREATE TABLE eval_batch (
    id                  UUID            PRIMARY KEY,
    scenario_id         UUID            NOT NULL,
    label               VARCHAR(200)    NOT NULL,   -- names the variant, e.g. "terse description"
    variant_json        CLOB,                       -- the overrides applied to the scenario
    repetitions         INT             NOT NULL,
    status              VARCHAR(32)     NOT NULL,   -- RUNNING | COMPLETED | FAILED | CANCELLED
    completed_count     INT             NOT NULL DEFAULT 0,
    passed_count        INT             NOT NULL DEFAULT 0,
    total_input_tokens  INT             NOT NULL DEFAULT 0,
    total_output_tokens INT             NOT NULL DEFAULT 0,
    error               CLOB,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_eval_batch_scenario FOREIGN KEY (scenario_id) REFERENCES eval_scenario (id) ON DELETE CASCADE
);

CREATE INDEX ix_eval_batch_scenario ON eval_batch (scenario_id);
CREATE INDEX ix_eval_batch_started ON eval_batch (started_at);

-- -------------------------------------------------------------------------------------
-- One repetition's outcome, linked to the run that produced it.
--
-- The link matters: a failing repetition is only useful if you can open the run behind it and read
-- the journal that explains why. An aggregate pass rate with no way back to the evidence would tell
-- you that something is wrong without ever telling you what.
-- -------------------------------------------------------------------------------------
CREATE TABLE eval_result (
    id              UUID            PRIMARY KEY,
    batch_id        UUID            NOT NULL,
    run_id          UUID,
    repetition      INT             NOT NULL,
    passed          BOOLEAN         NOT NULL,
    run_status      VARCHAR(32),
    assertions_json CLOB,                       -- per-assertion verdicts, with the reason for each
    duration_ms     BIGINT,
    input_tokens    INT             NOT NULL DEFAULT 0,
    output_tokens   INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_eval_result_batch FOREIGN KEY (batch_id) REFERENCES eval_batch (id) ON DELETE CASCADE
);

CREATE INDEX ix_eval_result_batch ON eval_result (batch_id);

-- -------------------------------------------------------------------------------------
-- Forking: a run started from a position in another run's journal.
--
-- The columns already exist on `run` from the baseline (parent_run_id, forked_from_seq); this only
-- indexes the lookup so a run's descendants can be found.
-- -------------------------------------------------------------------------------------
CREATE INDEX ix_run_parent ON run (parent_run_id);
