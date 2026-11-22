package org.krmdemo.plansinstr.equilib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the example pictures (adaptive SVGs) into the given directory, {@code plans-math/docs/} by default:
 * <pre>mvn -pl plans-math compile exec:java@docs</pre>
 * (or {@code java -cp plans-math/target/classes org.krmdemo.plansinstr.equilib.EquilibDocs plans-math/docs}).
 */
public final class EquilibDocs {

    private EquilibDocs() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "plans-math/docs");
        Files.createDirectories(dir);
        write(dir, "equilib-thousands.svg", new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000));
        write(dir, "equilib-millions.svg", Sweep.DEMO);
        write(dir, "equilib-swept-in-zero.svg", new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000));
        write(dir, "equilib-target-balance.svg", new Sweep(50_000, 50_000, 5_000, 5_000, 20_000, 25_000));
    }

    private static void write(Path dir, String name, Sweep s) throws IOException {
        Files.writeString(dir.resolve(name), SvgRenderer.render(s), StandardCharsets.UTF_8);
        System.out.println("wrote " + dir.resolve(name));
    }
}
