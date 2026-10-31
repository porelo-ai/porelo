package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;

/**
 * Git on a person's registry checkout, as the registry verbs run it: in a chosen environment, with
 * no hook and no file system monitor.
 *
 * <p>The checkout is anybody's who can write the registry, and the caller's environment may name
 * another repository ({@code GIT_DIR}), extra configuration ({@code GIT_CONFIG_COUNT}) or replace
 * objects; none of it reaches these commands, and no program the checkout's configuration names is
 * started by them.
 */
final class RegistryGit {

    /** What every command starts from: nothing of the caller's but where programs are found. */
    static final Map<String, String> ENVIRONMENT =
            Map.of(
                    "PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                    "LC_ALL", "C",
                    "GIT_CONFIG_NOSYSTEM", "1",
                    "GIT_CONFIG_GLOBAL", "/dev/null",
                    "GIT_ATTR_NOSYSTEM", "1",
                    "GIT_NO_REPLACE_OBJECTS", "1",
                    "GIT_TERMINAL_PROMPT", "0");

    private RegistryGit() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a Git command on the checkout.
     *
     * @param checkout The checkout's directory.
     * @param variables Variables on top of the chosen environment, such as the agent's socket.
     * @param arguments Git's arguments.
     * @return The command.
     */
    static Command git(
            final Path checkout, final Map<String, String> variables, final String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-C",
                                checkout.toString(),
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false"));
        all.addAll(List.of(arguments));
        return Command.of(all).withEnvironment(variables).withChosenEnvironment(ENVIRONMENT);
    }
}
