package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.MediaType;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The documentation of the sweep statuses, as tests. Each case is one set of parameters and one current balance;
 * it checks the status and the amount, and publishes what it found (entries) and the picture it makes (an SVG file,
 * the default frame at that balance). The Open Test Reporting HTML report on GitHub Pages shows them all:
 * every status, every jump and every special form of the picture.
 */
class SweepStatusGalleryTest {

    // T_min $40k, T_max $60k, τ_min $8k, τ_max $10k, A $25k, L $30k
    private static final Sweep THOUSANDS = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000);

    /** One documented case. */
    record Case(String title, String story, Sweep sweep, long balance, Zone zone, Long amount, String size, String threshold) {
        @Override
        public String toString() {
            return title;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
            new Case("Swept in partial", "The balance is so far below T_min that all of A comes back and is still not enough: "
                + "the last transfer, it empties the investment account.",
                THOUSANDS, 10_000, Zone.SWEPT_IN_PARTIAL, 25_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept in full, to the cent", "Exactly what is missing is all of A: the investment account is emptied "
                + "to the cent, and the next shortfall will meet an empty account.",
                THOUSANDS, 15_000, Zone.SWEPT_IN_FULL, 25_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept in full", "Exactly what is missing comes back from the investment account: T_min − x.",
                THOUSANDS, 25_000, Zone.SWEPT_IN_FULL, 15_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept in full, at the jump", "At T_min − τ_min the missing amount is exactly the nominal τ_min: "
                + "still worth a transfer. The point belongs to the sweep (filled), the zero line starts open.",
                THOUSANDS, 32_000, Zone.SWEPT_IN_FULL, 8_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("In nominal min", "Below T_min, but by less than the nominal τ_min: not worth a transfer.",
                THOUSANDS, 36_000, Zone.IN_NOMINAL_MIN, 0L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("In range", "Between the thresholds: all is well, nothing to transfer.",
                THOUSANDS, 50_000, Zone.IN_RANGE, 0L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("In nominal max", "Above T_max, but by less than the nominal τ_max.",
                THOUSANDS, 65_000, Zone.IN_NOMINAL_MAX, 0L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept out, at the jump", "At T_max + τ_max the excess is exactly the nominal τ_max: it goes out.",
                THOUSANDS, 70_000, Zone.SWEPT_OUT, 10_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept out", "The excess x − T_max goes from the DDA to the investment account.",
                THOUSANDS, 80_000, Zone.SWEPT_OUT, 20_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept out of exactly L", "A payment of exactly L, the largest the investment account accepts, is "
                + "still allowed: filled on the line, open on the axis.",
                THOUSANDS, 90_000, Zone.SWEPT_OUT, 30_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Overflow", "One dollar above T_max + L: one sweep cannot bring the balance back. Terminal failure (⏚).",
                THOUSANDS, 90_001, Zone.OVERFLOW, null, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Swept in zero", "A = 0: the investment account is empty, nothing can come back, and the whole left side "
                + "is a terminal failure (⏚), red like Overflow. A yellow card: on the next round the plan is either "
                + "cancelled (sent off till the end of the match, as in football) or paused (minutes in the penalty box, as in hockey).",
                new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000), 25_000, Zone.SWEPT_IN_ZERO, null, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Target balance", "T_min = T_max: a single target balance T, written [T]; the formula says just T.",
                new Sweep(50_000, 50_000, 5_000, 5_000, 20_000, 25_000), 40_000, Zone.SWEPT_IN_FULL, 10_000L, "M", "−$5k [$50k] +$5k"),
            new Case("No nominals", "τ = 0: every dollar off the thresholds is swept, there are no jumps, and the threshold "
                + "is written without −$0 and +$0.",
                new Sweep(40_000, 60_000, 0, 0, 25_000, 30_000), 70_000, Zone.SWEPT_OUT, 10_000L, "M", "[$40k ; $60k]"),
            new Case("Millions, broken axes", "L in millions: the axes are cut where Swept out leaves the pan, and the line "
                + "goes on across the cut as its own continuation, at 45°.",
                Sweep.DEMO, 1_500_000, Zone.SWEPT_OUT, 1_440_000L, "L", "−$8k [$40k ; $60k] +$10k"),
            new Case("Size S", "The smallest picture shows only the thresholds and the limits; the direction gets a line of its own.",
                THOUSANDS, 25_000, Zone.SWEPT_IN_FULL, 15_000L, "S", "−$8k [$40k ; $60k] +$10k"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void sweepStatus(Case c, TestReporter reporter) {
        Sweep.Point p = c.sweep().at(c.balance());
        assertEquals(c.zone(), p.zone(), "sweep status");
        assertEquals(c.amount(), p.amount(), "swept amount");
        assertEquals(c.threshold(), SvgRenderer.thresholdClosed(c.sweep()), "threshold");

        String svg = SvgRenderer.render(c.sweep(), c.size(), SvgRenderer.DEFAULT_FRAMES, c.balance());
        assertTrue(svg.contains("class=\"f d\""), "the default frame");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("story", c.story());
        entries.put("parameters", "T_min " + Money.full(c.sweep().thresholdMin()) + ", T_max " + Money.full(c.sweep().thresholdMax())
            + ", τ_min " + Money.full(c.sweep().nominalMin()) + ", τ_max " + Money.full(c.sweep().nominalMax())
            + ", A " + Money.full(c.sweep().sweptInMax()) + ", L " + Money.full(c.sweep().sweptOutMax()));
        entries.put(SvgRenderer.thresholdLabel(c.sweep()).replace(":", ""), SvgRenderer.thresholdClosed(c.sweep())
            + "  " + SvgRenderer.thresholdOpen(c.sweep()));
        entries.put("current balance", Money.full(c.balance()));
        entries.put("sweep status", p.zone().title() + (p.zone().terminal() ? " ⏚" : ""));
        entries.put("swept amount", p.amount() == null ? "∄ (terminal failure)" : Money.full(p.amount()));
        String dir = SvgRenderer.direction(p.zone());
        if (!dir.isEmpty()) entries.put("direction", dir);
        reporter.publishEntry(entries);
        reporter.publishFile(fileName(c), MediaType.parse("image/svg+xml"),
            file -> Files.writeString(file, svg, StandardCharsets.UTF_8));
    }

    private static String fileName(Case c) {
        return c.title().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "") + ".svg";
    }
}
