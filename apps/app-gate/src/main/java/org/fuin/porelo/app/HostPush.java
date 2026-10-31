package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * Passes a task's branch on to its host's forge, with the forge token the host made for the task.
 *
 * <p>On a host, a task's gate sets {@code refs/heads/porelo/<task>} in its mirror and pushes that
 * one branch, without force, to the forge on the host's loopback, {@code
 * http://127.0.0.1:<git_port>}. The name is {@code porelo/<task>} on a host whatever the project's
 * branch scheme says, since that is what the host checks against the task. The forge checks the
 * token and its scopes itself; nothing here runs as another account.
 *
 * <p><strong>The token reaches git through its credential protocol.</strong> It is in the push's
 * environment, which only this account and root can read, and {@code porelo vault credential}
 * answers git from there; the URL and the arguments hold only the variable's name.
 */
final class HostPush {

    /** Where a host names its forge to its persons, with no secret in it. */
    static final Path FORGE_CONF = Path.of("/etc/porelo-host/forge.conf");

    /** The variable that holds the task's token for the push's credential helper. */
    static final String TOKEN_VARIABLE = "PORELO_HOST_TASK_TOKEN";

    private final PoreloContext context;

    private final Path mirror;

    private final String task;

    private final String repository;

    private final Supplier<Optional<String>> token;

    private final Path forgeConf;

    /**
     * Constructor for the host's own forge.
     *
     * @param context The machine.
     * @param mirror The gate's bare mirror.
     * @param task The task's name.
     * @param repository The repository on the host's forge, {@code <project>/<repo>}.
     * @param token Reads the task's forge token, at each push.
     */
    HostPush(
            PoreloContext context,
            Path mirror,
            String task,
            String repository,
            Supplier<Optional<String>> token) {
        this(context, mirror, task, repository, token, FORGE_CONF);
    }

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param mirror The gate's bare mirror.
     * @param task The task's name.
     * @param repository The repository on the host's forge, {@code <project>/<repo>}.
     * @param token Reads the task's forge token, at each push.
     * @param forgeConf Where the host names its forge's {@code git_port}.
     */
    HostPush(
            PoreloContext context,
            Path mirror,
            String task,
            String repository,
            Supplier<Optional<String>> token,
            Path forgeConf) {
        this.context = context;
        this.mirror = mirror;
        this.task = task;
        this.repository = repository;
        this.token = token;
        this.forgeConf = forgeConf;
    }

    /**
     * Passes a commit on as the task's branch.
     *
     * @param commit What the branch is to point at.
     * @return {@code null} when the forge took it, else why not.
     */
    @Nullable String passOn(String commit) {
        // What reaches git's arguments and the URL is checked here, not trusted from the caller.
        if (!task.matches("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*")
                || !repository.matches(SEGMENT + "/" + SEGMENT)
                || !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            return "the gate refuses to pass on porelo/"
                    + printable(task)
                    + " to "
                    + printable(repository);
        }
        final Optional<String> forge = token.get();
        if (forge.isEmpty() || forge.get().isBlank()) {
            return "no forge token is kept for this task; the vault may be locked";
        }
        final String secret = forge.get().strip();
        if (!secret.matches("[\\x21-\\x7e]+")) {
            // A line break would let the token reach git's own warnings.
            return "the forge token kept for this task is not one word";
        }
        final String port = gitPort();
        if (port == null) {
            return forgeConf + " names no valid git_port";
        }
        final String base = "http://127.0.0.1:" + port;
        final String branch = "refs/heads/porelo/" + task;
        try {
            final CommandResult set =
                    context.runner()
                            .run(
                                    Command.of(
                                                    "git",
                                                    "-c",
                                                    "core.hooksPath=/dev/null",
                                                    "--git-dir",
                                                    mirror.toString(),
                                                    "update-ref",
                                                    branch,
                                                    commit)
                                            .withChosenEnvironment(gitEnvironment()));
            if (!set.successful()) {
                return "the gate could not set porelo/"
                        + task
                        + ": "
                        + printable(set.standardError().strip());
            }
            final CommandResult pushed =
                    context.runner()
                            .run(
                                    Command.of(
                                                    "git",
                                                    // No config but this: no hooks, no proxy, no
                                                    // transport but http, no helper but ours.
                                                    "-c",
                                                    "core.hooksPath=/dev/null",
                                                    "-c",
                                                    "protocol.allow=never",
                                                    "-c",
                                                    "protocol.http.allow=always",
                                                    "-c",
                                                    "http.proxy=",
                                                    "-c",
                                                    "credential.helper=",
                                                    "-c",
                                                    "credential."
                                                            + base
                                                            + ".helper=!"
                                                            + word(PoreloBinary.path().toString())
                                                            + " vault credential --env "
                                                            + TOKEN_VARIABLE,
                                                    "--git-dir",
                                                    mirror.toString(),
                                                    "push",
                                                    "--no-verify",
                                                    base + "/" + repository + ".git",
                                                    branch + ":" + branch)
                                            .withEnvironment(Map.of(TOKEN_VARIABLE, secret))
                                            .withChosenEnvironment(gitEnvironment()));
            if (!pushed.successful()) {
                return "the host's forge did not take porelo/"
                        + task
                        + " (exit "
                        + pushed.exitCode()
                        + "): "
                        + printable(masked(pushed.standardError().strip(), secret));
            }
            return null;
        } catch (CommandException ex) {
            return "the push to the host's forge could not be run: "
                    + printable(masked(String.valueOf(ex.getMessage()), secret));
        }
    }

    /**
     * The forge's port from the host's file, read at each push; {@code null} when it names none.
     */
    private @Nullable String gitPort() {
        final java.util.List<String> lines;
        try {
            lines = Files.readAllLines(forgeConf);
        } catch (IOException ex) {
            return null;
        }
        for (final String line : lines) {
            if (line.startsWith("git_port=")) {
                final String value = line.substring("git_port=".length()).strip();
                return value.matches("[1-9][0-9]{0,4}") && Integer.parseInt(value) <= 65535
                        ? value
                        : null;
            }
        }
        return null;
    }

    /** A name on the host's forge: no leading dot or dash, so never {@code ..} or an option. */
    private static final String SEGMENT = "[A-Za-z0-9_][A-Za-z0-9._-]*";

    /**
     * The push's environment, chosen rather than inherited: none of the account's or the system's
     * git config, no proxy, no trace, no prompt. HOME and PATH alone, for the credential helper.
     */
    private Map<String, String> gitEnvironment() {
        final Map<String, String> chosen = new java.util.LinkedHashMap<>();
        chosen.put(
                "PATH",
                java.util.Objects.requireNonNullElse(System.getenv("PATH"), "/usr/bin:/bin"));
        final String home = System.getenv("HOME");
        if (home != null) {
            chosen.put("HOME", home);
        }
        chosen.put("LC_ALL", "C");
        chosen.put("GIT_CONFIG_NOSYSTEM", "1");
        chosen.put("GIT_CONFIG_GLOBAL", "/dev/null");
        chosen.put("GIT_ATTR_NOSYSTEM", "1");
        chosen.put("GIT_NO_REPLACE_OBJECTS", "1");
        chosen.put("GIT_PROTOCOL_FROM_USER", "0");
        chosen.put("GIT_TERMINAL_PROMPT", "0");
        return chosen;
    }

    /** The token, and the Basic credential git makes of it, never travel in a message. */
    private static String masked(final String text, final String secret) {
        final String basic =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                ("x-access-token:" + secret)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return text.replace(basic, "<token>").replace(secret, "<token>");
    }

    private static String printable(final String value) {
        return value.replaceAll(
                "[\\p{Cntrl}\\u0080-\\u009f\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069]", "?");
    }

    private static String word(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
