package org.krmdemo.plansinstr.equilib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * Writes the demo page of the GitHub Pages (after the tests and the coverage report, {@code verify} phase):
 * {@code src/site/index.html} is a Thymeleaf template (HTML mode) filled with the status list ({@link Zone}), the
 * numbers of the tests and the coverage; the other files of {@code src/site} are copied as they are; the badge
 * numbers go to {@code badges/*.json} (shields.io endpoint). The workflow only adds the reports and commits.
 *
 * <pre>  java … DemoPage &lt;pages dir&gt;     (run from plans-math; exec:java@demo-page)</pre>
 */
public final class DemoPage {

    private DemoPage() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "target/pages/plans-math");
        Files.createDirectories(out.resolve("badges"));
        Path site = Path.of("src/site");
        try (Stream<Path> files = Files.list(site)) {
            for (Path f : files.filter(f -> !f.getFileName().toString().equals("index.html")).toList()) {
                Files.copy(f, out.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        int[] tests = tests(Path.of("target/surefire-reports"));       // {tests, failed, skipped}
        double coverage = coverage(Path.of("target/site/jacoco/jacoco.csv"));
        int passed = tests[0] - tests[1] - tests[2];
        String sha = System.getenv().getOrDefault("GITHUB_SHA", "local");

        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        Context ctx = new Context();
        ctx.setVariable("statuses", Zone.statusListHtml());
        ctx.setVariable("tests", passed + " JUnit tests passed" + (tests[1] > 0 ? ", " + tests[1] + " failed" : ""));
        ctx.setVariable("coverage", String.format(java.util.Locale.ROOT, "%.1f%%", coverage));
        ctx.setVariable("commit", sha);
        ctx.setVariable("commit7", sha.length() > 7 ? sha.substring(0, 7) : sha);
        ctx.setVariable("date", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")));
        Files.writeString(out.resolve("index.html"), engine.process(site.resolve("index.html").toString(), ctx), StandardCharsets.UTF_8);

        Files.writeString(out.resolve("badges/tests.json"), badge("JUnit tests",
            passed + " passed" + (tests[1] > 0 ? ", " + tests[1] + " failed" : ""), tests[1] > 0 ? "red" : "brightgreen"));
        Files.writeString(out.resolve("badges/coverage.json"), badge("JaCoCo coverage",
            String.format(java.util.Locale.ROOT, "%.1f%%", coverage), coverage >= 80 ? "brightgreen" : coverage >= 60 ? "yellow" : "orange"));
        System.out.printf("demo page: %d tests, %d passed, %d failed; coverage %.1f%% -> %s%n", tests[0], passed, tests[1], coverage, out);
    }

    private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*>");

    private static int attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"(\\d+)\"").matcher(tag);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Sums the surefire XML of every test class: tests, failed (failures and errors), skipped. */
    static int[] tests(Path dir) throws IOException {
        int[] t = new int[3];
        if (!Files.isDirectory(dir)) return t;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                Matcher m = SUITE.matcher(Files.readString(f, StandardCharsets.UTF_8));
                if (!m.find()) continue;
                String tag = m.group();
                t[0] += attr(tag, "tests");
                t[1] += attr(tag, "failures") + attr(tag, "errors");
                t[2] += attr(tag, "skipped");
            }
        }
        return t;
    }

    /** Line coverage in percent from JaCoCo's CSV report. */
    static double coverage(Path csv) {
        if (!Files.exists(csv)) return 0;
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            List<String> head = List.of(lines.get(0).split(","));
            int missed = head.indexOf("LINE_MISSED"), covered = head.indexOf("LINE_COVERED");
            long m = 0, c = 0;
            for (String line : lines.subList(1, lines.size())) {
                String[] v = line.split(",");
                m += Long.parseLong(v[missed]);
                c += Long.parseLong(v[covered]);
            }
            return m + c == 0 ? 0 : 100.0 * c / (m + c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String badge(String label, String message, String color) {
        return "{\"schemaVersion\": 1, \"label\": \"" + label + "\", \"message\": \"" + message + "\", \"color\": \"" + color + "\"}\n";
    }
}
