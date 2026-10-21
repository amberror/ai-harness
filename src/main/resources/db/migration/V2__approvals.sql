-- =====================================================================================
-- Phase 4: policy and approvals.
--
-- A run that stops for approval has to be resumable, and resuming means rebuilding the loop's
-- state from what was recorded. Most of that already exists -- the transcript in run_message, the
-- journal in run_event -- but two things were only ever held in memory and are added here.
-- =====================================================================================

-- The settings a run was started with.
--
-- Without this a parked run could not be resumed: the model key, the toolset, the guardrail limits
-- and the repair policy all lived only in the request that started it. Storing them also makes a run
-- reproducible later, which is what phase 5 needs to compare one configuration against another.
ALTER TABLE run ADD COLUMN config_json CLOB;

-- Reasoning tokens are tracked separately from output tokens everywhere else, but the run row
-- collapsed them. On this model reasoning routinely dwarfs the answer, so a resumed run that
-- rebuilt its budget from input/output alone would believe it had spent far less than it had.
ALTER TABLE run ADD COLUMN reasoning_tokens INT NOT NULL DEFAULT 0;

-- -------------------------------------------------------------------------------------
-- A tool call waiting on a human.
--
-- The remaining calls of the same step are stored alongside it: a model may emit several calls at
-- once, and parking on the second must not silently drop the third.
-- -------------------------------------------------------------------------------------
CREATE TABLE pending_approval (
    id                    UUID            PRIMARY KEY,
    run_id                UUID            NOT NULL,
    step_no               INT             NOT NULL,
    call_id               VARCHAR(120),
    tool_name             VARCHAR(120)    NOT NULL,
    arguments_json        CLOB,
    remaining_calls_json  CLOB,
    policy_name           VARCHAR(120),
    reason                VARCHAR(500),
    requested_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    decision              VARCHAR(16),    -- APPROVED | DENIED; null while still waiting
    scope                 VARCHAR(16),    -- CALL | RUN | ALWAYS
    decided_at            TIMESTAMP WITH TIME ZONE,
    note                  VARCHAR(500),
    CONSTRAINT fk_pending_approval_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE
);

CREATE INDEX ix_pending_approval_run ON pending_approval (run_id);
CREATE INDEX ix_pending_approval_undecided ON pending_approval (decision);

-- -------------------------------------------------------------------------------------
-- Default policy: nothing requires approval.
--
-- Policies opt in rather than opt out. A harness whose main use is unattended repeated runs would be
-- unusable if every call stopped for a human, and the boundaries that actually matter are enforced
-- elsewhere and unconditionally: the HTTP egress allowlist and the stdio command allowlist. This row
-- exists so the precedence rules have something to demonstrate against, and so the policy page is
-- not empty on first use.
-- -------------------------------------------------------------------------------------
INSERT INTO tool_policy (id, name, tool_name_pattern, approval_mode, priority, enabled, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', '*', 'AUTO', 1000, TRUE, CURRENT_TIMESTAMP);
