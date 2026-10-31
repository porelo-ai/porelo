package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;

/**
 * Git on a person's registry checkout, as the registry and identity verbs run every command on it:
 * in a chosen environment, with no hook, file system monitor, lazy fetch, ssh command, credential
 * helper or protocol of the checkout's.
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
                    // A partial checkout fetches a missing object from its promisor remote, by
                    // whatever program its configuration names; nothing here fetches.
                    "GIT_NO_LAZY_FETCH", "1",
                    "GIT_TERMINAL_PROMPT", "0");

    /**
     * Settings that leave no program, credential or rewrite of a checkout's configuration to a
     * command that may reach a remote, and allow no protocol at all: a command that needs one
     * allows it after these.
     */
    static final List<String> SEALED =
            List.of(
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "core.fsmonitor=false",
                    "-c",
                    "core.sshCommand=ssh",
                    "-c",
                    "credential.helper=",
                    "-c",
                    "http.followRedirects=false",
                    "-c",
                    "protocol.allow=never");

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
        final List<String> all = new ArrayList<>(List.of("git", "-C", checkout.toString()));
        all.addAll(SEALED);
        all.addAll(List.of(arguments));
        return Command.of(all).withEnvironment(variables).withChosenEnvironment(ENVIRONMENT);
    }

    /**
     * Returns a Git command on a person's checkout, as {@link #git} makes every one, with no
     * variables of its own.
     *
     * @param checkout The checkout's directory.
     * @param arguments Git's arguments.
     * @return The command.
     */
    static Command sealed(final Path checkout, final String... arguments) {
        return git(checkout, Map.of(), arguments);
    }

    /**
     * A runner for code that names its own {@code git -C DIRECTORY ...} commands, such as {@link
     * org.fuin.porelo.vault.AccessPersons}: each runs as {@link #git} makes it, and any other
     * command is refused.
     *
     * @param runner The runner that runs them.
     * @return The sealing runner.
     */
    static org.fuin.porelo.core.process.CommandRunner sealing(
            final org.fuin.porelo.core.process.CommandRunner runner) {
        return command -> {
            final List<String> given = command.arguments();
            if (given.size() < 3 || !given.get(0).equals("git") || !given.get(1).equals("-C")) {
                throw new IllegalArgumentException("only git -C DIRECTORY runs sealed");
            }
            final Command sealed =
                    sealed(
                            Path.of(given.get(2)),
                            given.subList(3, given.size()).toArray(String[]::new));
            final String input = command.input();
            return runner.run(input == null ? sealed : sealed.withInput(input));
        };
    }
}
