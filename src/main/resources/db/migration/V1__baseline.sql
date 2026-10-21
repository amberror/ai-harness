-- =====================================================================================
-- AI Control harness -- baseline schema.
--
-- Written for H2 but deliberately kept to portable SQL so that moving to PostgreSQL is a
-- datasource URL change plus swapping CLOB for JSONB. All JSON-shaped columns are stored as
-- text and parsed in the application layer, which keeps the two databases interchangeable.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- MCP servers. Declared before tool_definition because imported tools point back at them.
-- -------------------------------------------------------------------------------------
CREATE TABLE mcp_server (
    id              UUID            PRIMARY KEY,
    label           VARCHAR(120)    NOT NULL,
    description     VARCHAR(1000),
    transport       VARCHAR(32)     NOT NULL,   -- STDIO | STREAMABLE_HTTP | SSE
    command         VARCHAR(500),               -- STDIO only
    args_json       CLOB,                       -- STDIO only, JSON array
    env_json        CLOB,                       -- STDIO only, JSON object
    url             VARCHAR(1000),              -- HTTP transports only
    headers_json    CLOB,                       -- HTTP transports only, JSON object
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    status          VARCHAR(32)     NOT NULL DEFAULT 'UNKNOWN',
    last_error      CLOB,
    last_synced_at  TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_mcp_server_label UNIQUE (label)
);

-- -------------------------------------------------------------------------------------
-- Tool definitions.
--
-- Versioned rather than mutated in place: editing a tool creates a new version. This is what
-- lets an experiment attribute a change in model behaviour to a specific change in schema
-- wording -- the central question this harness exists to answer for a small model.
--
-- current_of holds the tool name while a row is the current version and NULL otherwise. Both
-- H2 and PostgreSQL treat NULLs as distinct in a unique constraint, so this enforces "at most
-- one current version per name" at the database level, portably, without a partial index
-- (which H2 does not support).
-- -------------------------------------------------------------------------------------
CREATE TABLE tool_definition (
    id                  UUID            PRIMARY KEY,
    name                VARCHAR(120)    NOT NULL,
    version             INT             NOT NULL,
    current_of          VARCHAR(120),
    description         CLOB            NOT NULL,
    params_schema       CLOB            NOT NULL,   -- JSON Schema for the arguments object
    executor_type       VARCHAR(32)     NOT NULL,   -- BUILTIN | HTTP | MCP
    executor_config     CLOB,                       -- JSON, shape depends on executor_type
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    source              VARCHAR(32)     NOT NULL DEFAULT 'USER',  -- USER | BUILTIN | MCP_IMPORT
    mcp_server_id       UUID,
    timeout_ms          BIGINT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    change_note         VARCHAR(500),
    CONSTRAINT uq_tool_definition_name_version UNIQUE (name, version),
    CONSTRAINT uq_tool_definition_current UNIQUE (current_of),
    CONSTRAINT fk_tool_definition_mcp_server FOREIGN KEY (mcp_server_id) REFERENCES mcp_server (id)
);

CREATE INDEX ix_tool_definition_name ON tool_definition (name);
CREATE INDEX ix_tool_definition_mcp_server ON tool_definition (mcp_server_id);

-- -------------------------------------------------------------------------------------
-- Toolsets: named bundles of tools. Exposing few, small schemas at a time matters a great deal
-- for a 4B model, so which tools an agent can see is a first-class, per-agent decision.
-- -------------------------------------------------------------------------------------
CREATE TABLE toolset (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(120)    NOT NULL,
    description VARCHAR(1000),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_toolset_name UNIQUE (name)
);

CREATE TABLE toolset_member (
    id              UUID            PRIMARY KEY,
    toolset_id      UUID            NOT NULL,
    tool_name       VARCHAR(120)    NOT NULL,
    pinned_version  INT,                        -- NULL means "always use the current version"
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    overrides_json  CLOB,                       -- optional per-set description/schema overrides
    sort_order      INT             NOT NULL DEFAULT 0,
    CONSTRAINT uq_toolset_member UNIQUE (toolset_id, tool_name),
    CONSTRAINT fk_toolset_member_toolset FOREIGN KEY (toolset_id) REFERENCES toolset (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------------------
-- Tool policies: what may run unattended, what needs a human, what is forbidden.
-- -------------------------------------------------------------------------------------
CREATE TABLE tool_policy (
    id                  UUID            PRIMARY KEY,
    name                VARCHAR(120)    NOT NULL,
    tool_name_pattern   VARCHAR(255)    NOT NULL,   -- glob, e.g. http_* or *
    approval_mode       VARCHAR(32)     NOT NULL,   -- AUTO | REQUIRE_APPROVAL | DENY
    priority            INT             NOT NULL DEFAULT 100,  -- lower number wins
    timeout_ms          BIGINT,
    rate_limit_per_run  INT,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tool_policy_name UNIQUE (name)
);

-- -------------------------------------------------------------------------------------
-- Agent profiles: a reusable description of how to drive the model.
-- The guardrail columns are per-profile so an experiment can vary one and hold the rest fixed.
-- -------------------------------------------------------------------------------------
CREATE TABLE agent_profile (
    id                          UUID            PRIMARY KEY,
    name                        VARCHAR(120)    NOT NULL,
    description                 VARCHAR(1000),
    model_key                   VARCHAR(255)    NOT NULL,
    system_prompt               CLOB,
    temperature                 DOUBLE PRECISION,
    top_p                       DOUBLE PRECISION,
    top_k                       INT,
    min_p                       DOUBLE PRECISION,
    repeat_penalty              DOUBLE PRECISION,
    max_output_tokens           INT,
    context_length              INT,
    reasoning_effort            VARCHAR(16),    -- off | low | medium | high | on
    toolset_id                  UUID,
    -- Guardrails.
    max_steps                   INT             NOT NULL DEFAULT 12,
    max_repairs_per_step        INT             NOT NULL DEFAULT 2,
    token_budget                INT,
    wall_clock_budget_ms        BIGINT,
    tool_output_max_chars       INT             NOT NULL DEFAULT 4000,
    loop_detection_threshold    INT             NOT NULL DEFAULT 3,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_agent_profile_name UNIQUE (name),
    CONSTRAINT fk_agent_profile_toolset FOREIGN KEY (toolset_id) REFERENCES toolset (id)
);

-- -------------------------------------------------------------------------------------
-- Runs: one execution of an agent profile against one input.
-- -------------------------------------------------------------------------------------
CREATE TABLE run (
    id                  UUID            PRIMARY KEY,
    agent_profile_id    UUID,
    parent_run_id       UUID,                       -- set when a run is forked from another
    forked_from_seq     BIGINT,                     -- the journal position forked from
    status              VARCHAR(32)     NOT NULL,
    model_key           VARCHAR(255)    NOT NULL,
    input               CLOB,
    final_output        CLOB,
    error               CLOB,
    steps_used          INT             NOT NULL DEFAULT 0,
    input_tokens        INT             NOT NULL DEFAULT 0,
    output_tokens       INT             NOT NULL DEFAULT 0,
    tool_calls_total    INT             NOT NULL DEFAULT 0,
    tool_calls_invalid  INT             NOT NULL DEFAULT 0,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_run_agent_profile FOREIGN KEY (agent_profile_id) REFERENCES agent_profile (id),
    CONSTRAINT fk_run_parent FOREIGN KEY (parent_run_id) REFERENCES run (id)
);

CREATE INDEX ix_run_started_at ON run (started_at);
CREATE INDEX ix_run_status ON run (status);

-- -------------------------------------------------------------------------------------
-- The materialised transcript: what the model was actually shown, message by message.
-- Kept separate from the event journal because resuming and forking a run need a compact,
-- queryable conversation, not a replay of every diagnostic event.
-- -------------------------------------------------------------------------------------
CREATE TABLE run_message (
    id              UUID            PRIMARY KEY,
    run_id          UUID            NOT NULL,
    seq             INT             NOT NULL,
    step_no         INT             NOT NULL DEFAULT 0,
    role            VARCHAR(16)     NOT NULL,   -- system | user | assistant | tool
    content         CLOB,
    reasoning       CLOB,                       -- split out of content, never sent back to the model
    tool_call_id    VARCHAR(120),
    tool_name       VARCHAR(120),
    arguments_json  CLOB,
    token_estimate  INT,
    truncated       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_run_message_seq UNIQUE (run_id, seq),
    CONSTRAINT fk_run_message_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------------------
-- The event journal: append-only, and the single source of truth for monitoring, replay and
-- evaluation assertions. Everything the harness does emits an event here.
-- -------------------------------------------------------------------------------------
CREATE TABLE run_event (
    id          BIGINT          GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    run_id      UUID            NOT NULL,
    seq         BIGINT          NOT NULL,
    step_no     INT             NOT NULL DEFAULT 0,
    type        VARCHAR(48)     NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT,
    payload     CLOB,                           -- JSON; includes exact request/response bodies
    CONSTRAINT uq_run_event_seq UNIQUE (run_id, seq),
    CONSTRAINT fk_run_event_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE
);

CREATE INDEX ix_run_event_run_seq ON run_event (run_id, seq);
CREATE INDEX ix_run_event_type ON run_event (type);
