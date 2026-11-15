# plans

A Java library and AWS Lambda functions that save and read `PlanInstructions` in Postgres.

## Folders

```
pom.xml                         parent: versions, Maven Central release profile
plans-core/                     the library (goes to Maven Central)
  model/                        PlanInstructions, DdaAccInfo, InvAccInfo, ThresholdsInfo,
                                RestInfo, RuntimeInfo, PlanType, PlanStatus, PlanSearchCriteria
  persistence/PlanJdbcClient    load, save, lookup, search (Spring JdbcClient, no JPA)
  persistence/UuidV7Generator   time-ordered plan_id
  function/PlanFunctions        Lambda functions: loadPlan, savePlan, lookupPlan, searchPlans
plans-exec/                     plan execution: Postgres poller, reactive saga stages, retrievers
plans-lambda/                   the deployable Lambda (shaded jar)
  RuntimeInfoConfig             host name, start time, build number
  MigrationFunctions            migrateDatabase (runs Flyway)
  resources/db/migration/       SQL schema
infra/template.yaml             AWS: network, Postgres, Lambdas, HTTP API
infra/github-oidc.yaml          AWS one-time: lets GitHub deploy without keys
.github/workflows/release.yml   release workflow
DEPLOY.md                       step by step guide
```

## Table `plan_instructions`

| column | type | meaning |
|---|---|---|
| plan_id | UUID, primary key | UUIDv7, sorts by creation time |
| payload | JSONB | the whole PlanInstructions |
| created_at, updated_at | TIMESTAMP(3) | UTC |
| version | BIGINT | optimistic locking |

## Example payload

```json
{
  "planId": "019a8c3e-6f2a-7b1c-9d4e-2a7f3c1b8e55",
  "planType": "RECURRING_INVESTMENT",
  "planStatus": "ACTIVE",
  "userId": "u1",
  "ddaAccInfo": { "ddaAccNum": "123", "ddaBankId": "B1" },
  "invAccInfo": { "invAccNum": "456", "occdCode": "X" },
  "currency": "USD",
  "thresholdInfo": { "thresholdMin": 10, "thresholdMax": 500, "nominalMin": 5, "nominalMax": 100 },
  "restInfo": {
    "userAgent": "my-first-test/1.0",
    "timestamp": "2026-11-14T14:30:12.481Z",
    "runtimeInfo": {
      "fullHostName": "169.254.76.1",
      "startedAt": "2026-11-14T14:29:58.902Z",
      "buildNumber": "42-1a2b3c4"
    }
  },
  "createdAt": "2026-11-14T14:30:12.481",
  "updatedAt": "2026-11-14T14:30:12.481",
  "version": 0
}
```

`restInfo` is set by the server on every save. The values above are only an example.
