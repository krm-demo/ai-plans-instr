# plans

A Java library and AWS Lambda functions that save and read `PlanInstructions` in Postgres.

## Folders

```
pom.xml                         parent: versions, Maven Central release profile
plans-core/                     the library (goes to Maven Central), org.krmdemo.plansinstr.core
  model/                        PlanInstructions, DdaAccInfo, InvAccInfo, ThresholdsInfo, RestInfo,
                                RuntimeInfo, PlanSearchCriteria, PlanApi, PlanExecution
  model/enums/                  PlanType, PlanStatus, AuditStatus
  model/events/                 AuditEvent, UnknownAuditEvent
  persistence/UuidV7Generator   time-ordered ids
  persistence/jdbc/             Postgres (Spring JdbcClient, no JPA):
    VersionedEntity             the envelope of every stored row: id, version, lastUpdatedAt,
                                lastUpdatedReason, payload
    VersionedEntityJdbcClient   insert, update with version check, find, delete for one table
    PlanJdbcClient              plans: load, save, lookup, search
plans-exec/                     plan execution, org.krmdemo.plansinstr.exec:
                                Postgres poller, reactive saga stages, retrievers
  model/events/                 PropOneEvent, PropTwoEvent
plans-lambda/                   the deployable Lambda (shaded jar), org.krmdemo.plansinstr.lambda
  function/PlanFunctions        Lambda functions: loadPlan, savePlan, lookupPlan, searchPlans
  RuntimeInfoConfig             host name, start time, build number
  MigrationFunctions            migrateDatabase (runs Flyway)
  resources/db/migration/       SQL schema
plans-math/                     a Java library: Balances Equilibrium, the swept amount per current balance
                                as an SVG without JavaScript (Thymeleaf), see plans-math/README.md
infra/template.yaml             AWS: network, Postgres, Lambdas, HTTP API
infra/github-oidc.yaml          AWS one-time: lets GitHub deploy without keys
infra/manual-tests.yaml         AWS: the databases of the manual tests (Aurora Serverless v2, DynamoDB)
infra/manual-tests-role.yaml    AWS one-time: the role of the workflows manual-tests and aurora
.github/actions/aurora/         action: status, up, start, stop, down, open, close of those databases
.github/workflows/release.yml   release workflow
.github/workflows/manual-tests.yml  by hand: the CRUD benchmark, see plans-lambda/README.md
.github/workflows/aurora.yml    by hand: create, start, stop, delete the databases of the benchmark
DEPLOY.md                       step by step guide
drain-to.sh, drain-to-java      copy this repo into another folder, see drain-to.md
```

## Manual tests: the CRUD benchmark

The tests tagged `manual` (`CrudBenchmarkTest` in plans-lambda) run the same CRUD scenario on
local databases (H2, embedded Postgres, DynamoDB Local) and on AWS ones (Aurora Serverless v2
PostgreSQL, DynamoDB). The normal build skips them; the workflow **manual-tests** runs them by
hand and shows the report on the summary page of the run. Details: [plans-lambda/README.md](plans-lambda/README.md).

### One-time setup: the repository variable `MANUAL_TESTS_ROLE_ARN`

The workflows `manual-tests` and `aurora` sign in to AWS through OIDC (no stored keys) as the
role of `infra/manual-tests-role.yaml`. Its ARN is kept **once**, in a repository variable, and
both workflows read it from there, instead of the ARN (and the account id in it) being written
into each workflow file:

1. deploy `infra/manual-tests-role.yaml` (CloudFormation, `CAPABILITY_NAMED_IAM`; parameter
   `CreateOidcProvider=false` when the account already has the GitHub OIDC provider);
2. copy its output `RoleArn`, for example `arn:aws:iam::123456789012:role/plans-manual-tests`;
3. GitHub: *Settings → Secrets and variables → Actions → Variables → New repository variable*,
   name `MANUAL_TESTS_ROLE_ARN`, value the ARN. A *variable*, not a secret: an ARN is not secret,
   and a variable can be read in the logs.

Without the variable the workflow runs only the local databases. Optional: `MANUAL_TESTS_REGION`
(default `us-east-1`).

## Tools

- [drain-to](drain-to.md): copies the files tracked by git into another folder, for
  example a copy of this repo on a work laptop. `.git` is skipped on both sides.
  There are two versions: `drain-to.sh` (bash + git) and `drain-to-java` (JDK 17+).

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
