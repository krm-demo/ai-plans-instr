package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.MediaType;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * One set of parameters of the demo tests (a {@code @ParameterizedClass} in {@link SweepDemoTest}): one test per
 * balance, in increasing order. Each checks the status and the amount against the rules written once more
 * ({@link DemoCases#expected}) and publishes the picture at that balance: S, M and L side by side.
 */
abstract class DemoPictures {

    @Parameter
    DemoCases.Frame frame;

    @ParameterizedTest(name = "{0}")
    @ArgumentsSource(DemoProbes.class)
    void balance(DemoCases.Probe probe, TestReporter reporter) {
        Sweep s = frame.sweep();
        long x = probe.balance();
        assertEquals(DemoCases.expected(s, x), s.at(x), "status and amount at " + x);
        String name = "sweep-" + Integer.toHexString(frame.title().hashCode()) + "-x-" + x + ".svg";
        reporter.publishFile(name, MediaType.parse("image/svg+xml"),
            file -> Files.writeString(file, SvgRenderer.renderSheet(s, x), StandardCharsets.UTF_8));
    }
}
