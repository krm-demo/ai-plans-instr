# plans-lambda

The deployable Lambda (shaded jar): `PlanFunctions` (load, save, lookup, search), Flyway
migrations, and the **CRUD benchmark** of the manual tests.

## The CRUD benchmark — «Что тебе снится, крейсер Аврора?»

`src/test/java/.../lambda/manual/CrudBenchmarkTest` runs one scenario on every database that it
can reach and writes a report that explains itself: `target/manual-tests/summary.md`.

| kind | target | what it is |
|---|---|---|
| local | `h2` | H2 in memory, in the JVM. Its own table (columns + JSON): H2 cannot run our Postgres schema (JSONB, `->>`) |
| local | `embedded-postgres` | a real PostgreSQL 17 started by [Zonky](https://github.com/zonkyio/embedded-postgres) in the test, our real code (`PlanJdbcClient`, Flyway) |
| local | `embedded-dynamodb` | AWS's DynamoDB Local in-process (`software.amazon.dynamodb:DynamoDBLocal`), in memory, no Docker |
| cloud | `aurora-postgres` | Aurora Serverless v2 PostgreSQL 17 (pauses itself at 0 ACU), our real code |
| cloud | `dynamodb` | a DynamoDB table on demand, the plan as a native map |

*Local* targets are for unit tests and local games: free, no network. *Cloud* targets are the
managed services that the Lambdas would use.

### The scenario

0. ping: `SELECT 1` (DynamoDB: a read of a missing key) a thousand times — the round trip alone;
1. create N plans (`-Dmanual.plans`, 10 000 by default), ten per user, of random types;
2. load each by id; update each with a version check;
3. per user: lookup(user, type), plans of the user, active plans of the user, plans of the DDA account;
4. the newest plan of every user in one query, ten times: `DISTINCT ON` in Postgres, `ROW_NUMBER()` in H2 (it has no `DISTINCT ON`), none in DynamoDB;
5. delete each.

One operation at a time, one thread, one open connection: these are latencies, not throughput.
For every operation the report gives *count*, *total*, *ops/s*, and the percentiles:
*p50* (median: half were faster), *p95* (1 in 20 was slower), *p99* (1 in 100 was slower), *max*.

### Running it

- **GitHub**: Actions → **manual-tests** → *Run workflow* (by hand only). It creates the AWS
  databases when they are missing, starts a stopped cluster, opens Postgres for the runner's IP
  only, runs the tests and puts `summary.md` on the summary page of the run. Setup: the
  repository variable `MANUAL_TESTS_ROLE_ARN`, see the [README of the repository](../README.md).
- **Locally**, only the local targets:
  ```
  mvn -pl plans-lambda -am test -Dgroups=manual -Dtests.excluded=none
  ```
  The cloud targets take part when `PLANS_PG_URL`, `PLANS_PG_USER`, `PLANS_PG_PASSWORD` (Aurora)
  and `PLANS_DDB_TABLE` with AWS credentials (DynamoDB) are set.
- **The databases** without the tests: Actions → **aurora**: `status`, `up`, `start`, `stop`,
  `down` (deletes everything with the data; type `down` into *confirm*).

### Cost

Aurora pauses itself after 15 idle minutes; paused it costs only its storage, plus about
$3.6/month for its public IPv4 and $0.40 for the password in Secrets Manager. DynamoDB on demand
costs nothing idle; a run of 10 000 plans costs well under a cent.
