package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.wire.Json;

/**
 * A task's start and end told to the host it runs on, through the host's own helper for its
 * persons, {@code porelo-host}.
 *
 * <p>A host records every task's certificate and makes the task's forge token itself; the token
 * goes to the task's gate, never into its container. A machine without host configuration or the
 * tool starts tasks as before. A configured host requires the installed tool. The contract is the
 * host's: the certificate on the helper's input, the token alone on its output, and exit 64, 65, 69
 * or 73 for wrong use, a refusal, an unreachable forge and a host file.
 */
final class HostPerson {

    private final PoreloContext context;

    private final Path program;
    private final Path hostDirectory;

    /**
     * Constructor for the host's helper.
     *
     * @param context The machine.
     */
    HostPerson(PoreloContext context) {
        this(context, context.paths().hostProgram());
    }

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param program The helper.
     */
    HostPerson(PoreloContext context, Path program) {
        this(context, program, context.paths().hostDirectory());
    }

    HostPerson(PoreloContext context, Path program, Path hostDirectory) {
        this.context = context;
        this.program = program;
        this.hostDirectory = hostDirectory;
    }

    /**
     * Whether this machine is a host whose persons start tasks.
     *
     * <p>Whether it is there, not whether this account may run it: a host keeps it root's alone,
     * mode {@code 0700}, and reaches it through sudo, so asking for executable read every person on
     * a real host as no host.
     *
     * @return {@code true} when host configuration or the installed tool is present.
     */
    boolean present() {
        return Files.isRegularFile(program) || Files.isDirectory(hostDirectory);
    }

    /**
     * Records a task's start on the host and takes its forge token.
     *
     * @param task The task's name.
     * @param certificate Its certificate line.
     * @return The forge token, or {@code ""} on a host without a forge.
     * @throws Refused If the host refused it, or could not be asked.
     */
    String start(String task, String certificate) {
        final CommandResult started =
                run(
                        HostForgeLending.onHost(program, "task", "start", task)
                                .withInput(certificate + "\n"));
        final String token = started.standardOutput();
        // One line end a helper may print after the token is no part of it.
        return token.endsWith("\n") ? token.substring(0, token.length() - 1) : token;
    }

    /**
     * Records a task's start without a certificate, which a host accepts only while root's test
     * switch {@code start-uncertified} holds: the empty input is how it is asked.
     *
     * @param task The task's name.
     * @return The forge token, or {@code ""} on a host without a forge.
     * @throws Refused If the host refused it, or could not be asked.
     */
    String startUncertified(String task) {
        final CommandResult started =
                run(HostForgeLending.onHost(program, "task", "start", task).withInput(""));
        final String token = started.standardOutput();
        return token.endsWith("\n") ? token.substring(0, token.length() - 1) : token;
    }

    /** The one test switch Porelo acts on: a task may start without a certificate. */
    static final String START_UNCERTIFIED = "start-uncertified";

    /**
     * The test switches root's role holds on this host, as {@code porelo-host test-switches}
     * answers them.
     *
     * <p>A host that cannot be asked, a helper that does not know the question, and an answer that
     * does not read all hold none: never more than the host said.
     *
     * @return The switches the host named.
     */
    java.util.Set<String> switches() {
        if (!Files.isRegularFile(program)) {
            return java.util.Set.of();
        }
        final CommandResult asked;
        try {
            asked =
                    context.runner()
                            .run(HostForgeLending.onHost(program, "test-switches", "--json"));
        } catch (CommandException ex) {
            return java.util.Set.of();
        }
        if (!asked.successful()) {
            return java.util.Set.of();
        }
        try {
            if (!(Json.parse(asked.standardOutput().strip()) instanceof Map<?, ?> answer)
                    || !(answer.get("switches") instanceof java.util.List<?> names)) {
                return java.util.Set.of();
            }
            final java.util.Set<String> held = new java.util.TreeSet<>();
            for (final Object name : names) {
                if (!(name instanceof String text)) {
                    return java.util.Set.of();
                }
                held.add(text);
            }
            return java.util.Set.copyOf(held);
        } catch (IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
            return java.util.Set.of();
        }
    }

    /**
     * Tells the host a task ended, so its forge token goes.
     *
     * @param task The task's name.
     * @return Whether the host answered that this task ended.
     * @throws Refused If the host refused it, or could not be asked.
     */
    boolean end(String task) {
        final CommandResult ended = run(HostForgeLending.onHost(program, "task", "end", task));
        try {
            return Json.parse(ended.standardOutput().strip()) instanceof Map<?, ?> answer
                    && task.equals(answer.get("task"))
                    && Boolean.TRUE.equals(answer.get("ended"));
        } catch (IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
            // No answer, or one that does not read, says no task ended; it ends no removal.
            return false;
        }
    }

    /**
     * The host's record of a task key's certificate, as {@code porelo task certificate} answers it.
     *
     * @param fingerprint The key's fingerprint, {@code SHA256:...}.
     * @return The record, or {@code null} when the host keeps none or could not be asked.
     * @throws IllegalArgumentException If it answered something else than a record of this key.
     */
    @org.jspecify.annotations.Nullable
    Map<?, ?> show(String fingerprint) {
        if (!present()) {
            return null;
        }
        requireProgram();
        final CommandResult shown;
        try {
            // The records are root's: sudo, which allows any admitted person.
            shown =
                    context.runner()
                            .run(HostForgeLending.onHost(program, "task", "show", fingerprint));
        } catch (CommandException ex) {
            return null;
        }
        if (!shown.successful() || shown.standardOutput().isBlank()) {
            return null;
        }
        final Object answer = Json.parse(shown.standardOutput().strip());
        if (!(answer instanceof Map<?, ?> map)
                || !fingerprint.equals(map.get("fingerprint"))
                || !(map.get("certificate") instanceof String line)
                || !certifies(line, fingerprint)) {
            throw new IllegalArgumentException("not a record of " + fingerprint);
        }
        return map;
    }

    /** Whether a line is a certificate, its own signature checked, of exactly this key. */
    private static boolean certifies(String line, String fingerprint) {
        try {
            return SshCertificate.read(line).fingerprint().equals(fingerprint);
        } catch (CardException ex) {
            return false;
        }
    }

    private CommandResult run(Command command) {
        requireProgram();
        final CommandResult result;
        try {
            result = context.runner().run(command);
        } catch (CommandException ex) {
            throw new Refused(69, "the host's helper could not be run");
        }
        if (!result.successful()) {
            // Its error output only: its standard output may hold a token.
            throw new Refused(
                    result.exitCode(),
                    "the host refused (exit "
                            + result.exitCode()
                            + "): "
                            + result.standardError().strip().replaceAll("\\p{Cntrl}", "?"));
        }
        return result;
    }

    private void requireProgram() {
        if (!Files.isRegularFile(program)) {
            throw new Refused(
                    69,
                    "the host's /usr/sbin/porelo-host is missing; install the porelo-host package");
        }
    }

    /** The host refused, or could not be asked. */
    static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Returns the helper's exit code.
         *
         * @return It, or 69 when it could not be run.
         */
        int code() {
            return code;
        }
    }
}
