package org.krmdemo.plansinstr.lambda.manual;

import java.util.Arrays;
import java.util.Locale;

/** The latencies of one kind of operation: how many, how long in total, percentiles. */
final class OpStats {

    final String op;
    private long[] nanos = new long[1024];
    private int count;

    OpStats(String op) {
        this.op = op;
    }

    /** Records one operation that started at {@code startNanos} ({@link System#nanoTime()}). */
    void since(long startNanos) {
        if (count == nanos.length) {
            nanos = Arrays.copyOf(nanos, count * 2);
        }
        nanos[count++] = System.nanoTime() - startNanos;
    }

    int count() {
        return count;
    }

    double totalSeconds() {
        long sum = 0;
        for (int i = 0; i < count; i++) sum += nanos[i];
        return sum / 1e9;
    }

    double opsPerSecond() {
        double t = totalSeconds();
        return t == 0 ? 0 : count / t;
    }

    /** The percentile in milliseconds, for example {@code p(95)}. */
    double p(double percent) {
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(nanos, count);
        Arrays.sort(sorted);
        int i = (int) Math.ceil(percent / 100.0 * count) - 1;
        return sorted[Math.max(0, Math.min(i, count - 1))] / 1e6;
    }

    /** One row of the Markdown table of {@link CrudBenchmarkTest}. */
    String markdownRow(String target) {
        return String.format(Locale.ROOT, "| %s | %s | %,d | %.2f | %,.0f | %.3f | %.3f | %.3f | %.3f |",
                target, op, count, totalSeconds(), opsPerSecond(), p(50), p(95), p(99), p(100));
    }

    static final String MARKDOWN_HEADER = """
            | target | operation | count | total, s | ops/s | p50, ms | p95, ms | p99, ms | max, ms |
            |---|---|--:|--:|--:|--:|--:|--:|--:|""";
}
