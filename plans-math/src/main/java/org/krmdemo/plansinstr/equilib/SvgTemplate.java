package org.krmdemo.plansinstr.equilib;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders the model of {@link SvgRenderer} with the Thymeleaf template {@code templates/equilib.svg} (XML mode).
 * The first thing in every SVG is a comment that says when, with which Java and which Thymeleaf it was made.
 * No Spring here: plain Thymeleaf with its standard dialect.
 */
final class SvgTemplate {

    private static final TemplateEngine ENGINE = engine();

    private SvgTemplate() {
    }

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(SvgTemplate.class.getClassLoader());
        resolver.setPrefix("templates/");
        resolver.setSuffix(".svg");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    static String render(Map<String, Object> model) {
        Context ctx = new Context();
        ctx.setVariables(model);
        ctx.setVariable("comment", "<!-- " + generatedBy() + " -->");
        return ENGINE.process("equilib", ctx);
    }

    /** "Balances Equilibrium (plans-math) · generated 2026-11-21T04:10:00Z · Java 21.0.9 · Thymeleaf 3.1.3.RELEASE". */
    static String generatedBy() {
        return "Balances Equilibrium (plans-math) · generated " + Instant.now().truncatedTo(ChronoUnit.SECONDS)
            + " · Java " + Runtime.version() + " · Thymeleaf " + thymeleafVersion();
    }

    static String thymeleafVersion() {
        String v = TemplateEngine.class.getPackage().getImplementationVersion();
        if (v == null) {
            try {
                v = (String) Class.forName("org.thymeleaf.Thymeleaf").getField("VERSION").get(null);
            } catch (ReflectiveOperationException | ClassCastException e) {
                v = "3";
            }
        }
        return v;
    }
}
