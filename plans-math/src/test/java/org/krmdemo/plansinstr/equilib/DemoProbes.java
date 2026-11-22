package org.krmdemo.plansinstr.equilib;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;

/**
 * The balances of the set of parameters the enclosing {@code @ParameterizedClass} invocation stands for, found by its
 * display name (the title of the set, unique), each named as the test shows it: "x = $32k = Tₘᵢₙ − τₘᵢₙ → …".
 */
class DemoProbes implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
        for (Optional<ExtensionContext> c = Optional.of(context); c.isPresent(); c = c.get().getParent()) {
            String shown = c.get().getDisplayName();
            for (DemoCases.Group g : DemoCases.groups()) {
                for (DemoCases.Frame f : g.frames()) {
                    if (shown.equals(f.title())) {
                        return f.probes().stream().map(p -> Arguments.of(Named.of(DemoCases.name(f.sweep(), p), p)));
                    }
                }
            }
        }
        throw new IllegalStateException("no set of parameters around " + context.getUniqueId());
    }
}
