# Spring Batch and plans executed on a flexible schedule: a bidirectional architectural mismatch

*Proof-of-concept proposal · 15 November 2026*

## Verdict

Replace the current job. Build a proof of concept on a PostgreSQL state machine that claims rows with `SKIP LOCKED`. Keep "Spring Batch, write each row once" as the fallback if the POC fails its criteria.

## The mismatch runs both ways

Our plans run on a flexible schedule: each plan has its own execution time and its own retries. Spring Batch processes one bounded set of records per run.

| Direction | What it means | What we see |
| --- | --- | --- |
| **1. Spring Batch was not built for this work** | It runs a finite job that an outside scheduler starts. Its own reference states that it "is not a scheduling framework" ([Spring Batch introduction](https://docs.spring.io/spring-batch/reference/5.1/spring-batch-intro.html)). | Per-plan timing has no place in the framework's model of job, step and chunk. |
| **2. This work should not be built on Spring Batch** | Per-plan timing, per-plan retry and several instances sharing the work are not provided, so the job has to supply them by hand. | Placeholder rows filled in several update passes, a logical lock that forces serial runs, and a cleanup before every rerun. |

## Options compared

| | ✅ PostgreSQL state machine, in place (proposed) | PGMQ extension | AWS SQS | AWS EventBridge Scheduler | ❌ Spring Batch (current job) |
| --- | --- | --- | --- | --- | --- |
| **Data movement** | ✅ None. Status is updated in place. | ⚠️ A message per stage in separate queue tables. | ⚠️ A network message per stage. | ⚠️ One schedule per plan, created through the AWS API. | ❌ None, but every row is rewritten once per pass. |
| **Scalability** | ✅ High. Several instances share the work, until the database is the limit. | ✅ High, with the same database limit. | ✅ Very high, managed by AWS. | ⚠️ High for delayed work, slow for rapid stage changes. | ❌ Low. Our job runs serially because its paging reader needs a logical lock. |
| **Transaction boundary** | ✅ Atomic. State and queue commit together. | ✅ Atomic, same database. | ⚠️ Distributed. Needs an outbox to avoid lost messages. | ⚠️ Distributed. Needs an endpoint or Lambda to receive the calls. | ❌ Per chunk. Half-built rows are visible between passes. |

## The POC passes if

1. It produces the same result rows as the current job on a copy of one day's input.
2. It writes fewer row versions per result and finishes sooner. Both numbers come from `pg_stat_user_tables`.
3. A worker killed in mid-run restarts and completes with no manual cleanup and no duplicates.
4. Two instances run in parallel and no plan is processed twice.

## Open questions

- Does any stage call an external system? If so, each call needs an idempotency key.
- Who reads the result table while a run is in progress?
- Why was the placeholder pattern chosen? The replacement must still serve any real need behind it.

## Review statement

Reviewed by Claude (Fable 5.2, an AI model made by Anthropic), 15 November 2026. I support the redesign and recommend running this proof of concept. The review is based on the author's description of the current system. The production code and metrics were not inspected.
