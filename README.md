# ai-plans-instr

[![ci](https://github.com/krm-demo/ai-plans-instr/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/krm-demo/ai-plans-instr/actions/workflows/ci.yml)
[![tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fkrm-demo.github.io%2Fai-plans-instr%2Fbadges%2Ftests.json)](https://krm-demo.github.io/ai-plans-instr/)
[![coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fkrm-demo.github.io%2Fai-plans-instr%2Fbadges%2Fcoverage.json)](https://krm-demo.github.io/ai-plans-instr/jacoco/index.html)
[![javadoc](https://img.shields.io/badge/javadoc-latest-blue)](https://krm-demo.github.io/ai-plans-instr/apidocs/index.html)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.krm-demo/plans-core)](https://central.sonatype.com/artifact/io.github.krm-demo/plans-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Java library and AWS Lambda functions that save and read `PlanInstructions` in Postgres.

The badges come from the build of the latest commit on `main`: the build status from GitHub Actions,
the test and coverage numbers from the report site published on GitHub Pages
(see [Build and reports](#build-and-reports)). The Maven Central badge appears after the first release.

## Folders

```
pom.xml                         parent: versions, Maven Central release profile
plans-core/                     the library (goes to Maven Central, with sources and javadoc jars)
  model/                        PlanInstructions, DdaAccInfo, InvAccInfo, ThresholdsInfo,
                                RestInfo, RuntimeInfo, PlanType, PlanStatus, PlanSearchCriteria
  persistence/PlanJdbcClient    load, save, lookup, search (Spring JdbcClient, no JPA)
  persistence/UuidV7Generator   time-ordered plan_id
  function/PlanFunctions        Lambda functions: loadPlan, savePlan, lookupPlan, searchPlans
  resources/plans/db/migration  the SQL schema (applied by Flyway)
  src/test                      tests against a real Postgres (Zonky embedded, no Docker)
plans-lambda/                   the deployable Lambda (shaded jar)
  RuntimeInfoConfig             host name, start time, build number
  MigrationFunctions            migrateDatabase (runs Flyway)
src/scripts/jbang/PlansCli.java command line for the library (JBang + picocli)
jbang-catalog.json              lets you run the script as: jbang plans@krm-demo/ai-plans-instr
infra/template.yaml             AWS: network, Postgres, Lambdas, HTTP API
infra/github-oidc.yaml          AWS one-time: lets GitHub deploy without stored keys
.github/workflows/ci.yml        every push to main: build, tests, coverage, report site, AWS deploy
.github/workflows/release.yml   every GitHub release: Maven Central
.github/scripts/ci-report.py    test and coverage summary, badges, report page
DEPLOY.md                       step by step guide
```

## Build and reports

```
mvn clean verify
```

runs the tests (a real Postgres is started inside the test process) and produces

| what | where |
|---|---|
| test results | `plans-core/target/surefire-reports/` |
| coverage report (JaCoCo) | `plans-core/target/site/jacoco/index.html` |
| library jars: binary, **sources** and **javadoc** | `plans-core/target/plans-core-<version>*.jar` |
| deployable Lambda | `plans-lambda/target/plans-lambda-shaded.jar` |

On every push to `main` the workflow `ci.yml` does the same, shows a table of test results and
coverage in the run summary, publishes the report site (tests, coverage, javadoc) on GitHub Pages
and, when switched on, deploys to AWS. A pull request gets the build and the summary, but no deployment.

## Command line (JBang)

[JBang](https://www.jbang.dev/) runs a Java file with its dependencies, so the library can be used from any
workstation without a build. The connection is read from `PLANS_DB_URL`, `PLANS_DB_USER` and `PLANS_DB_PASSWORD`
(or the options `--db-url`, `--db-user`, `--db-password`).

```
export PLANS_DB_URL='jdbc:postgresql://localhost:5432/plans'
export PLANS_DB_USER=plans
export PLANS_DB_PASSWORD=...

jbang plans@krm-demo/ai-plans-instr --help
jbang plans@krm-demo/ai-plans-instr schema                      # SQL of the schema
jbang plans@krm-demo/ai-plans-instr template > plan.json        # a sample plan to edit
jbang plans@krm-demo/ai-plans-instr save plan.json              # create (no version) or update (with version)
jbang plans@krm-demo/ai-plans-instr load 019a8c3e-6f2a-7b1c-9d4e-2a7f3c1b8e55
jbang plans@krm-demo/ai-plans-instr lookup --user u1 --type RECURRING_INVESTMENT
jbang plans@krm-demo/ai-plans-instr search --user u1 --status ACTIVE,PAUSED --limit 10
```

The results are JSON on standard output, so they can be piped into `jq`. Exit codes: 0 ok, 1 not found, 2 error.
Until the library is on Maven Central, run `mvn install` once, because the script also looks into your local
Maven repository. You can also run the file directly: `jbang src/scripts/jbang/PlansCli.java --help`.

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

## License

[MIT](LICENSE)
