package org.fuin.porelo.app;

import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;

/** Git asked about the person's own checkout and settings, never about an organization. */
public final class LocalGit {

    private LocalGit() {}

    /**
     * Returns the question for the address a checkout was taken from.
     *
     * @param checkout The checkout.
     * @return The command.
     */
    public static Command origin(final Path checkout) {
        return Command.of("git", "remote", "get-url", "origin").in(checkout);
    }

    /**
     * Returns the question for one of the person's settings.
     *
     * @param field The setting's name.
     * @return The command.
     */
    public static Command config(final String field) {
        return Command.of("git", "config", "--get", field);
    }
}
