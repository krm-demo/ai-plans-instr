-- plan_execution: the persistent saga state, used as a PGMQ-style queue.
-- Not yet a Flyway migration (the tests apply it directly); it becomes one when plans-exec is deployed.
--
-- The whole PlanExecution, including the embedded planInstr and the audit events, is the JSONB
-- payload. There is no reference to plan_instructions: no foreign key, no join.
-- The other columns are copies for polling and querying; version is authoritative.

CREATE TABLE plan_execution (
    execution_id     UUID          PRIMARY KEY,   -- UUIDv7
    scheduled_at     TIMESTAMPTZ   NOT NULL,
    visible_at       TIMESTAMPTZ   NOT NULL,      -- lease: the row can be claimed when visible_at <= now
    finished         BOOLEAN       NOT NULL DEFAULT FALSE,
    audit_status     TEXT          NOT NULL,
    derived_prop_one NUMERIC(18,2),
    derived_prop_two NUMERIC(18,2),
    payload          JSONB         NOT NULL,
    version          BIGINT        NOT NULL
);

-- the poller's query: due rows that are not finished
CREATE INDEX idx_plan_execution_due ON plan_execution (visible_at, execution_id) WHERE NOT finished;
