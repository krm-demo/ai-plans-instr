package org.krmdemo.plansinstr.core.statechart;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders the state-chart SVG templates in {@code src/main/statechart/} into the enum's javadoc
 * {@code doc-files/} folder. Every status link points at the status's javadoc and constant in the
 * enum source on GitHub ({@code #L<first>-L<constant>}), read from the source on each run.
 *
 * <p>Runs in the Maven build ({@code exec-maven-plugin}, phase {@code process-test-classes}), so
 * the diagrams always match the source. On its own: {@code mvn -pl plans-core test-compile exec:java@state-charts}.
 * It lives in the test sources: build tooling, not part of the published jar.
 *
 * <p>Template model: {@code hrefs['X']} and {@code titles['X']} per constant {@code X};
 * {@code enumHref} and {@code enumTitle} for the enum declaration.
 */
public final class StateChartGenerator {

    static final String ENUM_SOURCE = "src/main/java/org/krmdemo/plansinstr/core/model/enums/AuditStatus.java";
    static final String TEMPLATES = "src/main/statechart";
    static final String SOURCE_URL =
            "https://github.com/krm-demo/ai-plans-instr/blob/main/plans-core/" + ENUM_SOURCE;

    private static final Pattern CONSTANT = Pattern.compile("^    ([A-Z][A-Z0-9_]*)\\s*[,;(]");
    private static final Pattern DECLARATION = Pattern.compile("^public enum (\\w+)\\b");

    /** Lines of one constant: the first line of its javadoc (or the constant itself) and the constant. */
    public record Range(int first, int line) {
        String anchor() {
            return first == line ? "#L" + line : "#L" + first + "-L" + line;
        }
    }

    /** What the generator reads from the enum source. */
    public record EnumSource(String name, int declarationLine, Map<String, Range> constants) {
    }

    private StateChartGenerator() {
    }

    /** @param args the module base directory ({@code ${project.basedir}}); the current directory if absent */
    public static void main(String[] args) throws IOException {
        Path basedir = Path.of(args.length > 0 ? args[0] : ".");
        Path enumSource = basedir.resolve(ENUM_SOURCE);
        Path output = enumSource.resolveSibling("doc-files");
        for (Path written : generate(enumSource, basedir.resolve(TEMPLATES), output, SOURCE_URL)) {
            System.out.println("[state-chart] wrote " + basedir.relativize(written));
        }
    }

    /** Renders every {@code *.svg} template in {@code templates} into {@code output}; returns the files written. */
    public static List<Path> generate(Path enumSource, Path templates, Path output, String sourceUrl) throws IOException {
        EnumSource source = scan(Files.readAllLines(enumSource, StandardCharsets.UTF_8));
        String file = enumSource.getFileName().toString();

        Map<String, String> hrefs = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        source.constants().forEach((name, range) -> {
            hrefs.put(name, sourceUrl + range.anchor());
            titles.put(name, source.name() + "." + name + ", " + file + " line " + range.line());
        });
        Context context = new Context();
        context.setVariable("hrefs", hrefs);
        context.setVariable("titles", titles);
        context.setVariable("enumHref", sourceUrl + "#L" + source.declarationLine());
        context.setVariable("enumTitle", "enum " + source.name() + ", " + file + " line " + source.declarationLine());

        TemplateEngine engine = engine(templates);
        Files.createDirectories(output);
        List<Path> svgTemplates;
        try (Stream<Path> list = Files.list(templates)) {
            svgTemplates = list.filter(p -> p.getFileName().toString().endsWith(".svg")).sorted().toList();
        }
        List<Path> result = new ArrayList<>();
        for (Path template : svgTemplates) {
            String name = template.getFileName().toString();
            String svg = engine.process(name, context);
            for (String constant : source.constants().keySet()) {
                if (!svg.contains("data-status=\"" + constant + "\"")) {
                    throw new IllegalStateException(name + ": " + source.name() + "." + constant + " is not drawn");
                }
            }
            Path target = output.resolve(name);
            Files.writeString(target, svg, StandardCharsets.UTF_8);
            result.add(target);
        }
        return result;
    }

    /** Finds the enum declaration and, for every constant, its javadoc's first line and its own line. */
    public static EnumSource scan(List<String> lines) {
        String name = null;
        int declaration = 0;
        Integer docStart = null;
        Map<String, Range> constants = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            String stripped = text.strip();
            int n = i + 1;
            Matcher declared = DECLARATION.matcher(text);
            if (name == null && declared.find()) {
                name = declared.group(1);
                declaration = n;
                docStart = null;
                continue;
            }
            Matcher constant = CONSTANT.matcher(text);
            if (name != null && constant.find()) {
                constants.put(constant.group(1), new Range(docStart == null ? n : docStart, n));
                docStart = null;
                if (stripped.endsWith(";")) {
                    break;   // the constant list is over
                }
            } else if (stripped.startsWith("/**")) {
                docStart = n;
            } else if (!stripped.startsWith("*")) {
                docStart = null;
            }
        }
        if (name == null || constants.isEmpty()) {
            throw new IllegalStateException("no enum constants found");
        }
        return new EnumSource(name, declaration, constants);
    }

    private static TemplateEngine engine(Path templates) {
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(templates.toAbsolutePath() + "/");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
