package org.krmdemo.plansinstr.core.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the state-chart diagrams in {@code doc-files/} in step with {@link AuditStatus}: every
 * constant is drawn and links to its own line. On failure, regenerate the diagrams with
 * {@code python3 plans-core/docs/audit-status/generate.py}.
 */
class AuditStatusDiagramTest {

    private static final Path ENUMS = Path.of("src/main/java/org/krmdemo/plansinstr/core/model/enums");
    private static final Path SOURCE = ENUMS.resolve("AuditStatus.java");
    private static final String REGENERATE = " (run: python3 plans-core/docs/audit-status/generate.py)";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"audit-status-horizontal.svg", "audit-status-vertical.svg"})
    @DisplayName("every status in the diagram links to its line in AuditStatus.java")
    void linksMatchSource(String svgName) throws IOException {
        List<String> source = Files.readAllLines(SOURCE);
        String svg = Files.readString(ENUMS.resolve("doc-files").resolve(svgName));
        String javadoc = String.join("\n", source);

        assertTrue(javadoc.contains("@see <a href=\"doc-files/" + svgName + "\">"),
                "AuditStatus javadoc has no @see link to " + svgName);
        assertTrue(svg.contains("AuditStatus.java#L" + lineOf(source, "public enum AuditStatus ") + "\""),
                svgName + ": the title does not link to the enum declaration" + REGENERATE);

        for (AuditStatus status : AuditStatus.values()) {
            int line = lineOf(source, "    " + status.name() + ",", "    " + status.name() + ";");
            int first = javadocStart(source, line);
            Matcher links = Pattern.compile(
                    "AuditStatus\\.java#L(\\d+)-L(\\d+)\" target=\"_top\" data-status=\"" + status.name() + "\"").matcher(svg);
            assertTrue(links.find(), svgName + ": " + status + " is not drawn" + REGENERATE);
            assertEquals(first + "-" + line, links.group(1) + "-" + links.group(2),
                    svgName + ": " + status + " does not select its javadoc and constant" + REGENERATE);
        }
    }

    /** The 1-based line of the {@code /**} that opens the javadoc right above {@code line}. */
    private static int javadocStart(List<String> source, int line) {
        int i = line - 2;   // 0-based index of the line above
        if (i < 0 || !source.get(i).strip().equals("*/")) {
            throw new AssertionError("AuditStatus.java line " + line + ": the constant has no javadoc");
        }
        while (i >= 0 && !source.get(i).strip().startsWith("/**")) {
            i--;
        }
        return i + 1;
    }

    /** The 1-based line of the first line that starts with any of the prefixes. */
    private static int lineOf(List<String> source, String... prefixes) {
        for (int i = 0; i < source.size(); i++) {
            for (String prefix : prefixes) {
                if (source.get(i).startsWith(prefix)) {
                    return i + 1;
                }
            }
        }
        throw new AssertionError("not found in AuditStatus.java: " + List.of(prefixes));
    }
}
