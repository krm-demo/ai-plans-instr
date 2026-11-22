package org.krmdemo.plansinstr.lambda.manual;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The report of {@link CrudBenchmarkTest}: {@code target/manual-tests/summary.md}. It explains
 * itself (what was measured and how to read the numbers), so the workflow only shows the file.
 */
final class BenchReport {

    /** What one target measured. */
    record Result(BenchTarget.Kind kind, String name, String about, Duration firstConnect,
                  int removedLeftovers, List<OpStats> stats) {

        Optional<OpStats> op(String op) {
            return stats.stream().filter(s -> s.op.equals(op)).findFirst();
        }
    }

    private final List<Result> results = new ArrayList<>();
    private final int plans;
    private final int plansPerUser;

    BenchReport(int plans, int plansPerUser) {
        this.plans = plans;
        this.plansPerUser = plansPerUser;
    }

    synchronized void add(Result r) {
        results.add(r);
    }

    synchronized void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), markdown());
    }

    synchronized String markdown() {
        List<Result> ordered = new ArrayList<>();
        results.stream().filter(r -> r.kind == BenchTarget.Kind.LOCAL).forEach(ordered::add);
        results.stream().filter(r -> r.kind == BenchTarget.Kind.CLOUD).forEach(ordered::add);
        String region = Optional.ofNullable(System.getenv("AWS_REGION")).orElse("-");

        StringBuilder md = new StringBuilder();
        md.append("## Что тебе снится, крейсер Аврора?\n\n");
        md.append(String.format(Locale.ROOT,
                "CRUD on `PlanInstructions`: %,d plans, %d per user. Cloud region: %s. Written by `CrudBenchmarkTest` at %s.%n%n",
                plans, plansPerUser, region, Instant.now().truncatedTo(ChronoUnit.SECONDS)));

        // ---- the comparison: p50 of every operation on every target
        md.append("### Median (p50) of one operation, ms\n\n");
        md.append("Lower is better. *local*: in the JVM of the test, for tests and games only. ")
          .append("*cloud*: the managed service in AWS, over the network from the GitHub runner.\n\n");
        md.append("| operation |");
        ordered.forEach(r -> md.append(" ").append(r.kind == BenchTarget.Kind.LOCAL ? "local" : "cloud")
                .append(": ").append(r.name).append(" |"));
        md.append("\n|---|");
        ordered.forEach(r -> md.append("--:|"));
        md.append("\n");
        Set<String> ops = new LinkedHashSet<>();
        ordered.forEach(r -> r.stats.forEach(s -> ops.add(s.op)));
        for (String op : ops) {
            md.append("| ").append(op).append(" |");
            for (Result r : ordered) {
                md.append(" ").append(r.op(op).filter(s -> s.count() > 0)
                        .map(s -> String.format(Locale.ROOT, "%.3f", s.p(50))).orElse("—")).append(" |");
            }
            md.append("\n");
        }
        md.append("\n— : the target has no such query (DynamoDB has no `DISTINCT ON`; it would be a scan or a separate index).\n\n");

        md.append(HOW_TO_READ);

        // ---- the details, local and cloud apart
        section(md, "Local databases (in the JVM of the test)", BenchTarget.Kind.LOCAL);
        section(md, "Cloud databases (AWS)", BenchTarget.Kind.CLOUD);
        return md.toString();
    }

    private void section(StringBuilder md, String title, BenchTarget.Kind kind) {
        List<Result> rs = results.stream().filter(r -> r.kind == kind).toList();
        if (rs.isEmpty()) {
            return;
        }
        md.append("### ").append(title).append("\n\n");
        for (Result r : rs) {
            md.append("#### ").append(r.name).append("\n\n");
            md.append("- ").append(r.about).append("\n");
            if (r.firstConnect != null) {
                md.append(String.format(Locale.ROOT, "- start / first connection: %.1f s%n", r.firstConnect.toMillis() / 1000.0));
            }
            if (r.removedLeftovers > 0) {
                md.append("- removed ").append(r.removedLeftovers).append(" plans left by an earlier run\n");
            }
            md.append("\n").append(OpStats.MARKDOWN_HEADER).append("\n");
            r.stats.forEach(s -> md.append(s.markdownRow()).append("\n"));
            md.append("\n");
        }
    }

    private static final String HOW_TO_READ = """
            <details><summary>What was measured and how to read it</summary>

            **The scenario**, the same on every target:
            1. *create*: N new plans, ten per user, of random types;
            2. *load by id*: each of them;
            3. *update (version check)*: each of them, with optimistic locking (the stored version must match);
            4. per user: *lookup(user, type)* (the newest plan of a type that is not cancelled),
               *plans of a user*, *active plans of a user*, *plans of a DDA account*;
            5. *newest plan per user*: one query over all users (`DISTINCT ON` in Postgres and H2), ten times;
            6. *delete*: each of them.

            **One operation at a time**, by one thread, over one open connection (JDBC) or the SDK's
            pool of kept-alive HTTPS connections (DynamoDB). These are latencies of single requests,
            not throughput under load: with many clients in parallel all of these databases do much more
            per second.

            **The columns**
            - *count*: how many operations; *total, s*: their time added up;
            - *ops/s*: count / total, that is one over the average;
            - *p50* (median): half of the operations were faster than this;
            - *p95*: 95 of 100 were faster, 1 in 20 was slower; *p99*: 1 in 100 was slower;
            - *max*: the slowest one, usually among the first (JIT warm-up, caches, a database waking up).

            The cloud numbers include the round trip from the GitHub runner (Azure) to AWS; from a
            Lambda in the same region they would be lower. The local targets cost nothing and need no
            network, but only the embedded Postgres runs our real code (JSONB, the Flyway migrations);
            H2 and DynamoDB need their own table and queries.

            </details>

            """;
}
