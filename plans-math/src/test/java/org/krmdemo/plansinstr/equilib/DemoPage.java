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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <pre>  java … DemoPage &lt;pages dir&gt; &lt;plans-math dir&gt;     (exec:java@demo-page)</pre>
 */
public final class DemoPage {

    private DemoPage() {
    }

    /** The files of src/site that are Thymeleaf templates (the rest is copied as is). */
    private static final List<String> TEMPLATES = List.of("index.html", "demo-tests.html");

    public static void main(String[] args) throws IOException {
        Path base = Path.of(args.length > 1 ? args[1] : ".");             // the module (exec:java runs in Maven's directory)
        Path out = Path.of(args.length > 0 ? args[0] : "target/pages/plans-math");
        Files.createDirectories(out.resolve("badges"));
        Path site = base.resolve("src/site");
        try (Stream<Path> files = Files.list(site)) {
            for (Path f : files.filter(f -> !TEMPLATES.contains(f.getFileName().toString())).toList()) {
                Files.copy(f, out.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        int[] tests = tests(base.resolve("target/surefire-reports"));       // {tests, failed, skipped}
        double coverage = coverage(base.resolve("target/site/jacoco/jacoco.csv"));
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
        Files.writeString(out.resolve("index.html"), engine.process(site.resolve("index.html").toAbsolutePath().toString(), ctx), StandardCharsets.UTF_8);

        ctx.setVariable("groups", gallery(base.resolve("target/open-test-report/demo")));
        ctx.setVariable("cases", tests[0]);
        Files.writeString(out.resolve("demo-tests.html"), engine.process(site.resolve("demo-tests.html").toAbsolutePath().toString(), ctx),
            StandardCharsets.UTF_8);

        Files.writeString(out.resolve("badges/tests.json"), badge("JUnit tests",
            passed + " passed" + (tests[1] > 0 ? ", " + tests[1] + " failed" : ""), tests[1] > 0 ? "red" : "brightgreen"));
        Files.writeString(out.resolve("badges/coverage.json"), badge("JaCoCo coverage",
            String.format(java.util.Locale.ROOT, "%.1f%%", coverage), coverage >= 80 ? "brightgreen" : coverage >= 60 ? "yellow" : "orange"));
        System.out.printf("demo page: %d tests, %d passed, %d failed; coverage %.1f%% -> %s%n", tests[0], passed, tests[1], coverage, out);
    }

    /**
     * The demo tests as one page to scroll: every group, set and balance, with the picture the test published (found
     * by its name in the Open Test Reporting output, which the workflow copies to tests/demo/).
     */
    static List<Map<String, Object>> gallery(Path otr) throws IOException {
        Map<String, String> pictures = new HashMap<>();
        if (Files.isDirectory(otr)) {
            try (Stream<Path> files = Files.walk(otr)) {
                files.filter(f -> f.toString().endsWith(".svg")).forEach(f -> pictures.put(f.getFileName().toString(), url(otr.relativize(f))));
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        int n = 0;
        for (DemoCases.Group g : DemoCases.groups()) {
            n++;
            List<Map<String, Object>> sets = new ArrayList<>();
            for (DemoCases.Frame f : g.frames()) {
                List<Map<String, Object>> cases = new ArrayList<>();
                for (DemoCases.Probe p : f.probes()) {
                    Sweep.Point pt = f.sweep().at(p.balance());
                    Map<String, Object> c = new HashMap<>();
                    c.put("x", "x = " + DemoCases.money(p.balance()));
                    c.put("symbol", p.symbol() == null ? null : "= " + p.symbol().replace("_min", "ₘᵢₙ").replace("_max", "ₘₐₓ"));
                    c.put("status", pt.zone().title() + (pt.zone().terminal() ? " ⏚" : ""));
                    c.put("color", pt.zone().ink());
                    c.put("amount", pt.amount() == null ? "∄" : DemoCases.money(pt.amount()));
                    String pic = pictures.get(DemoCases.pictureName(f, p));
                    c.put("src", pic == null ? null : "tests/demo/" + pic);
                    cases.add(c);
                }
                sets.add(Map.of("title", f.title(), "cases", cases));
            }
            groups.add(Map.of("title", n + " · " + g.title(), "about", g.about(), "sets", sets));
        }
        return groups;
    }

    private static String url(Path rel) {
        StringBuilder u = new StringBuilder();
        for (Path part : rel) {
            if (!u.isEmpty()) u.append('/');
            u.append(URLEncoder.encode(part.toString(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return u.toString();
    }

    private static int count(String xml, String regex) {
        Matcher m = Pattern.compile(regex).matcher(xml);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Counts the test cases in the surefire XML of every test class: tests, failed (failures and errors), skipped.
     * By the cases, not the suite totals: the suite of a class with nested and parameterized classes reports 0.
     */
    static int[] tests(Path dir) throws IOException {
        int[] t = new int[3];
        if (!Files.isDirectory(dir)) return t;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                String xml = Files.readString(f, StandardCharsets.UTF_8);
                t[0] += count(xml, "<testcase\\b");
                t[1] += count(xml, "<failure\\b") + count(xml, "<error\\b");
                t[2] += count(xml, "<skipped\\b");
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
