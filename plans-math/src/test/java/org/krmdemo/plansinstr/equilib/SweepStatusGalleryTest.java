package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One test per essentially different set of parameters: each checks the sweep status at the balances that matter
 * for it and publishes its picture into the Open Test Reporting report (GitHub Pages), so the report shows every
 * layout the library draws.
 */
class SweepStatusGalleryTest {

    /** A balance to check: the expected status and amount (null: ∄). */
    record Check(long balance, Zone zone, Long amount) {
    }

    /** One set of parameters, what it is about, the tier ("adaptive": all), the threshold as written, and the checks. */
    record Variant(String title, String about, Sweep sweep, String size, String threshold, List<Check> checks) {
        @Override
        public String toString() {
            return title;
        }
    }

    private static Check c(long x, Zone z, Long y) {
        return new Check(x, z, y);
    }

    static Stream<Variant> variants() {
        return Stream.of(
            new Variant("Thresholds with nominals", "The full layout: every status, both jumps.",
                new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000), "adaptive", "−$8k [$40k ; $60k] +$10k", List.of(
                    c(10_000, Zone.SWEPT_IN_PARTIAL, 25_000L), c(15_000, Zone.SWEPT_IN_FULL, 25_000L),
                    c(32_000, Zone.SWEPT_IN_FULL, 8_000L), c(32_001, Zone.IN_NOMINAL_MIN, 0L), c(50_000, Zone.IN_RANGE, 0L),
                    c(69_999, Zone.IN_NOMINAL_MAX, 0L), c(70_000, Zone.SWEPT_OUT, 10_000L), c(90_000, Zone.SWEPT_OUT, 30_000L),
                    c(90_001, Zone.SWEPT_OUT_OVERFLOW, null))),
            new Variant("No nominals", "τ = 0: no jumps, no In Nominal zones; T_min and T_max themselves are In Range.",
                new Sweep(40_000, 60_000, 0, 0, 25_000, 30_000), "adaptive", "[$40k ; $60k]", List.of(
                    c(39_999, Zone.SWEPT_IN_FULL, 1L), c(40_000, Zone.IN_RANGE, 0L), c(60_000, Zone.IN_RANGE, 0L),
                    c(60_001, Zone.SWEPT_OUT, 1L))),
            new Variant("Target balance", "T_min = T_max = T: the threshold is [T], In Range is the single point x = T.",
                new Sweep(50_000, 50_000, 5_000, 5_000, 20_000, 25_000), "adaptive", "−$5k [$50k] +$5k", List.of(
                    c(40_000, Zone.SWEPT_IN_FULL, 10_000L), c(45_001, Zone.IN_NOMINAL_MIN, 0L), c(50_000, Zone.IN_RANGE, 0L),
                    c(54_999, Zone.IN_NOMINAL_MAX, 0L), c(55_000, Zone.SWEPT_OUT, 5_000L))),
            new Variant("Target balance, no nominals", "The simplest layout: [T], every dollar off T is swept.",
                new Sweep(50_000, 50_000, 0, 0, 20_000, 25_000), "adaptive", "[$50k]", List.of(
                    c(49_999, Zone.SWEPT_IN_FULL, 1L), c(50_000, Zone.IN_RANGE, 0L), c(50_001, Zone.SWEPT_OUT, 1L))),
            new Variant("Empty investment account", "A = 0: the whole sweep In side is Swept In Zero, red like Swept Out Overflow; "
                + "T_min − τ_min is a filled red point.",
                new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000), "adaptive", "−$8k [$40k ; $60k] +$10k", List.of(
                    c(10_000, Zone.SWEPT_IN_ZERO, null), c(32_000, Zone.SWEPT_IN_ZERO, null), c(32_001, Zone.IN_NOMINAL_MIN, 0L))),
            new Variant("Empty investment account, no nominal min", "A = 0 and τ_min = 0: Swept In Zero ends just before T_min, "
                + "which is In Range: a filled blue point.",
                new Sweep(40_000, 60_000, 0, 10_000, 0, 30_000), "adaptive", "[$40k ; $60k] +$10k", List.of(
                    c(39_999, Zone.SWEPT_IN_ZERO, null), c(40_000, Zone.IN_RANGE, 0L))),
            new Variant("Target balance, empty account, no nominal min", "T_min = T_max, A = 0, τ_min = 0.",
                new Sweep(50_000, 50_000, 0, 5_000, 0, 25_000), "adaptive", "[$50k] +$5k", List.of(
                    c(49_999, Zone.SWEPT_IN_ZERO, null), c(50_000, Zone.IN_RANGE, 0L), c(55_000, Zone.SWEPT_OUT, 5_000L))),
            new Variant("Millions, broken axes", "L in millions: the axes are cut where Swept Out leaves the pan, "
                + "and the line goes on across the cut at 45°.",
                Sweep.DEMO, "adaptive", "−$8k [$40k ; $60k] +$10k", List.of(
                    c(1_500_000, Zone.SWEPT_OUT, 1_440_000L), c(2_060_001, Zone.SWEPT_OUT_OVERFLOW, null))),
            new Variant("Tier S", "The phone tier on its own: the graph and the status lines; only the thresholds and the limits are labelled.",
                new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000), "S", "−$8k [$40k ; $60k] +$10k", List.of(
                    c(25_000, Zone.SWEPT_IN_FULL, 15_000L))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void variant(Variant v, TestReporter reporter) {
        Sweep s = v.sweep();
        for (Check c : v.checks()) {
            Sweep.Point p = s.at(c.balance());
            assertEquals(c.zone(), p.zone(), "status at " + c.balance());
            assertEquals(c.amount(), p.amount(), "amount at " + c.balance());
        }
        assertEquals(v.threshold(), SvgRenderer.thresholdClosed(s), "threshold");

        String svg = "adaptive".equals(v.size()) ? SvgRenderer.render(s) : SvgRenderer.render(s, v.size(), SvgRenderer.DEFAULT_FRAMES);
        assertTrue(svg.contains("class=\"f d\""), "the default frame");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("about", v.about());
        entries.put("parameters", (s.singleThreshold() ? "T " + Money.full(s.thresholdMin())
                : "T_min " + Money.full(s.thresholdMin()) + ", T_max " + Money.full(s.thresholdMax()))
            + ", τ_min " + Money.full(s.nominalMin()) + ", τ_max " + Money.full(s.nominalMax())
            + ", A " + Money.full(s.sweptInMax()) + ", L " + Money.full(s.sweptOutMax()));
        entries.put(SvgRenderer.thresholdLabel(s).replace(":", ""), SvgRenderer.thresholdClosed(s) + "  " + SvgRenderer.thresholdOpen(s));
        entries.put("checked", v.checks().stream()
            .map(c -> Money.full(c.balance()) + " → " + c.zone().title() + " " + (c.amount() == null ? "∄" : Money.full(c.amount())))
            .collect(Collectors.joining("; ")));
        reporter.publishEntry(entries);
        reporter.publishFile(v.title().toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".svg", MediaType.parse("image/svg+xml"),
            file -> Files.writeString(file, svg, StandardCharsets.UTF_8));
    }

    /** The README lists the statuses under the picture: word for word Zone.description(), so there is only one text. */
    @Test
    void readmeListsTheStatusesAsZoneDescribesThem() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        String expected = Zone.statusListMarkdown();
        assertTrue(readme.contains(expected), "README.md must contain the status list:\n" + expected);
    }
}
