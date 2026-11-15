-- plan_execution: the persistent saga state, used as a PGMQ-style queue.
-- Not yet a Flyway migration (the tests apply it directly); it becomes one when plans-exec is deployed.
--
-- A row is a VersionedEntity (plans-core, persistence.jdbc) whose payload is the whole
-- PlanExecution, including the embedded planInstr and the audit events. There is no reference
-- to plan_instructions: no foreign key, no join.
-- The columns after payload are copies for polling and querying.

CREATE TABLE plan_execution (
    id                  UUID          PRIMARY KEY,   -- UUIDv7, the executionId
    version             INTEGER       NOT NULL CHECK (version >= 1),
    last_updated_at     TIMESTAMPTZ   NOT NULL,
    last_updated_reason TEXT,                        -- the audit status that was written, or CLAIMED
    payload             JSONB         NOT NULL,
    scheduled_at        TIMESTAMPTZ   NOT NULL,
    visible_at          TIMESTAMPTZ   NOT NULL,      -- lease: the row can be claimed when visible_at <= now
    finished            BOOLEAN       NOT NULL DEFAULT FALSE,
    audit_status        TEXT          NOT NULL,
    derived_prop_one    NUMERIC(18,2),
    derived_prop_two    NUMERIC(18,2)
);

-- the poller's query: due rows that are not finished
CREATE INDEX idx_plan_execution_due ON plan_execution (visible_at, id) WHERE NOT finished;
