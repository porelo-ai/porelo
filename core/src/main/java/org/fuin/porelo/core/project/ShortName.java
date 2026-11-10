package org.fuin.porelo.core.project;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The optional container spelling shared by projects and tasks. */
public final class ShortName {
    private ShortName() {}

    /** Returns why a supplied spelling cannot be used; absence needs no spelling. */
    public static Optional<String> refusal(@Nullable String name) {
        if (name == null) return Optional.empty();
        if (!name.matches("[a-z][a-z0-9-]*")) {
            return Optional.of(
                    "use lowercase letters, digits and hyphens, starting with a letter; no underscore");
        }
        if (name.length() > 16) return Optional.of("use at most 16 characters");
        if (name.startsWith("porelo-"))
            return Optional.of("a short name must not start with porelo-");
        return Optional.empty();
    }
}
