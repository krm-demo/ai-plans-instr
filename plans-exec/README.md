# plans-exec

Runs plan executions: polls due `PlanExecution` rows from Postgres, runs them through saga
stages of retrievers, and stores the result. Retrievers and the `AuditEvent` records live
here (for now the examples `PropOneEvent` and `PropTwoEvent`); `PlanInstructions`,
`PlanExecution`, `AuditEvent`, `UnknownAuditEvent` and `AuditStatus` live in `plans-core`.

## Code

| Class | Role |
|---|---|
| `SweepSagaPostgresPoller` | endless `Flux<PlanExecution>`: claims due rows with `FOR UPDATE SKIP LOCKED` and a lease, waits `pollInterval` only when a round is empty, claims only when there is demand |
| `SweepSagaStage` | runs its `Retriever`s on a dedicated scheduler (`maxConcurrency` 1 = sequential), folds events in declared order with `auditEvent.applyTo(...)`; exception, timeout or no result → the retriever's own failure event |
| `SweepSagaPipeline` | poller → stages → checkpoint after each stage (version check, extends the lease) → complete + insert the next execution in one transaction, with a copy of the current plan instructions as `planInstr`; skips stages after the first failure; leaves executions with unknown events for a newer worker |
| `Retriever<E>` | `Mono<E> retrieve(PlanExecution)` and `E failed(PlanExecution, Throwable)`: one event type per retriever |
| `PlanExecutionStore`, `JdbcPlanExecutionStore` | the table as a queue: `insert`, `claimDue`, `checkpoint`, `complete` |
| `plans/exec/db/plan_execution.sql` | the table; the whole execution is the JSONB payload, no foreign key to `plan_instructions` |

Tests: stage timing, order and failures; the pipeline end to end with an in-memory store; the
store and poller on an embedded Postgres (Zonky), including 10 threads claiming 100 rows.

Not decided yet (placeholders): real retrievers and events, when the next run is
(`NextRunPolicy`), Spring wiring and configuration, the Flyway migration for the table.

## Notes

| File | What |
|---|---|
| [docs/poller.md](docs/poller.md) | PGMQ-style long poller: `SweepJdbcClient`, claim with `FOR UPDATE SKIP LOCKED`, schema with `uuidv7()`, multi-threaded Zonky Postgres test |
| [docs/saga-stage.md](docs/saga-stage.md) | `SweepSagaStage` orchestrator, `SweepReactiveProperties`, stages that run retrievers through a `Flux` and reduce with `auditEvent.applyTo(...)`, tests |

The notes are the original designs (Nov 12–13); the code above implements them with the
`plans-core` model. Each note ends with **Fitting the current model**, the list of gaps that the
code closes (lease, version check, declared-order fold, no `block()`, per-retriever failures).

## Flow

```
schedule ──► plan_execution row (SCHEDULED, scheduled_at)
                │
poller ─────────┤ claim due rows: FOR UPDATE SKIP LOCKED → STARTED (+ lease)
                ▼
Flux<PlanExecution> ──► orchestrator (flatMap, up to N executions at once)
                            │
                            ├─ stage 1: retrievers in parallel on the same input,
                            │           events folded in declared order: pe = event.applyTo(pe)
                            ├─ checkpoint (version check, extend lease)
                            ├─ stage 2 … (needs stage 1's derived values)
                            ├─ checkpoint
                            └─ COMPLETED / first failure ──► schedule the next execution
                                                             (same transaction)
```

## Sources

Design notes from Nov 12–13, 2026: *SweepJdbcClient & SweepSagaStage Architecture (PGMQ Long
Poller + Reactor Flux SAGA)*, *Reactive Pipeline Configuration, Updated Saga Stages & JUnit 5
Tests*, *Zonky Embedded Postgres Multi-Threaded JUnit 5 Test (SKIP LOCKED + UUIDv7)*.

## Manual Postgres scripts

`src/test/resources/manual-pg/` holds SQL that is run by hand against a database, together
with the test that proves it on example rows.

| File | What it is |
|---|---|
| `fix_attempts_scalar.sql` | removes the legacy counter `"attempts"` from `CHECK_ONE` events in `cash_sweep_audit.audit_events` (JSON stored as text); array-valued `"attempts"` and all other events stay as they are |
| `data.sql` | example rows for that script; each row carries the JSON the script must leave, or `NULL` when the row must not be touched |
| `FixAttemptsScalarSqlTest` | loads `data.sql` into Zonky Postgres, runs the script as one batch and checks the preview, the update count, the verification, the event order, a second run, and a row with malformed JSON |

The table and the `CHECK_ONE` event belong to another service; nothing else in this module uses them.
