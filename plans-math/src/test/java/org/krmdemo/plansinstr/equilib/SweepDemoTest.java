package org.krmdemo.plansinstr.equilib;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MediaType;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The demo tests: everything the demo page can show, as a report to scroll through (functional coverage, see
 * {@link DemoCases}). Six groups; in each, the sets of parameters, titled as over the graph of the demo page; in
 * each set, the balances in increasing order, each with its status, amount and the picture at S, M and L.
 * Run them alone with {@code mvn -pl plans-math verify -Dgroups=demo}; the technical tests are untagged.
 */
@Tag("demo")
@DisplayName("Balances Equilibrium: what the demo page shows")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class SweepDemoTest {

    static Stream<Named<DemoCases.Frame>> group(int i) {
        return DemoCases.groups().get(i).frames().stream().map(f -> Named.of(f.title(), f));
    }

    static Stream<Named<DemoCases.Frame>> group0() { return group(0); }
    static Stream<Named<DemoCases.Frame>> group1() { return group(1); }
    static Stream<Named<DemoCases.Frame>> group2() { return group(2); }
    static Stream<Named<DemoCases.Frame>> group3() { return group(3); }
    static Stream<Named<DemoCases.Frame>> group4() { return group(4); }
    static Stream<Named<DemoCases.Frame>> group5() { return group(5); }

    private static final String SOURCE = "org.krmdemo.plansinstr.equilib.SweepDemoTest#group";

    /** The two icons do not depend on the parameters: the smallest tiers of every picture. */
    @Test
    @DisplayName("Icons: below 80 px and below 200 px, the same for any parameters")
    void icons(TestReporter reporter) {
        reporter.publishFile(Path.of("docs/icon-small.svg"), MediaType.parse("image/svg+xml"));
        reporter.publishFile(Path.of("docs/icon.svg"), MediaType.parse("image/svg+xml"));
    }

    @Nested
    @Order(1)
    @DisplayName("1 · Two thresholds with nominals: every status, both jumps")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "0")
    class TwoThresholds extends DemoPictures {
    }

    @Nested
    @Order(2)
    @DisplayName("2 · No nominals on one side or both: no jump there, the threshold itself is In Range")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "1")
    class NoNominals extends DemoPictures {
    }

    @Nested
    @Order(3)
    @DisplayName("3 · Target balance [T]: In Range is the single point x = T")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "2")
    class TargetBalance extends DemoPictures {
    }

    @Nested
    @Order(4)
    @DisplayName("4 · Swept In Zero, A = 0: the whole left side is a terminal failure ⏚")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "3")
    class SweptInZero extends DemoPictures {
    }

    @Nested
    @Order(5)
    @DisplayName("5 · Coinciding ends, A = τₘᵢₙ or L = τₘₐₓ: Swept In Full or Swept Out is a single point")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "4")
    class CoincidingEnds extends DemoPictures {
    }

    @Nested
    @Order(6)
    @DisplayName("6 · Huge L, broken axes: one scale, the axes cut along the Swept Out line")
    @ParameterizedClass(name = "{0}")
    @MethodSource(SOURCE + "5")
    class BrokenAxes extends DemoPictures {
    }
}
