# plans-exec

Runs plan executions: polls due `PlanExecution` rows from Postgres, runs them through saga
stages of retrievers, and stores the result. Retrievers and their `AuditEvent` records will
live here; `PlanExecution`, `AuditEvent`, `UnknownAuditEvent` and `AuditStatus` live in
`plans-core`.

**Status:** design notes only. Not yet a Maven module (not listed in the parent `pom.xml`).

## Notes

| File | What |
|---|---|
| [docs/poller.md](docs/poller.md) | PGMQ-style long poller: `SweepJdbcClient`, claim with `FOR UPDATE SKIP LOCKED`, schema with `uuidv7()`, multi-threaded Zonky Postgres test |
| [docs/saga-stage.md](docs/saga-stage.md) | `SweepSagaStage` orchestrator, `SweepReactiveProperties`, stages that run retrievers through a `Flux` and reduce with `auditEvent.applyTo(...)`, tests |

Each note ends with **Fitting the current model**: what changes with the `plans-core` types
and the gaps to close when it becomes code.

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
