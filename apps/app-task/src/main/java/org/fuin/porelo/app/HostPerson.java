package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.wire.Json;

/**
 * A task's start and end told to the host it runs on, through the host's own helper for its
 * persons, {@code porelo-host-person}.
 *
 * <p>A host records every task's certificate and makes the task's forge token itself; the token
 * goes to the task's gate, never into its container. A machine without the helper is no such host,
 * and its tasks start as before. The contract is the host's: the certificate on the helper's input,
 * the token alone on its output, and exit 64, 65, 69 or 73 for wrong use, a refusal, an unreachable
 * forge and a host file.
 */
final class HostPerson {

    /** Where a host installs the helper; sudo allows it by this path alone. */
    static final Path PROGRAM = Path.of("/usr/local/sbin/porelo-host-person");

    private final PoreloContext context;

    private final Path program;

    /**
     * Constructor for the host's helper.
     *
     * @param context The machine.
     */
    HostPerson(PoreloContext context) {
        this(context, PROGRAM);
    }

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param program The helper.
     */
    HostPerson(PoreloContext context, Path program) {
        this.context = context;
        this.program = program;
    }

    /**
     * Whether this machine is a host whose persons start tasks.
     *
     * @return {@code true} when the helper is there.
     */
    boolean present() {
        return Files.isExecutable(program);
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
                        Command.of("sudo", "-n", program.toString(), "task", task)
                                .withInput(certificate + "\n"));
        return started.standardOutput();
    }

    /**
     * Tells the host a task ended, so its forge token goes.
     *
     * @param task The task's name.
     * @return Whether the host answered that this task ended.
     * @throws Refused If the host refused it, or could not be asked.
     */
    boolean end(String task) {
        final CommandResult ended =
                run(Command.of("sudo", "-n", program.toString(), "task", "--end", task));
        try {
            return Json.parse(ended.standardOutput().strip()) instanceof Map<?, ?> answer
                    && task.equals(answer.get("task"))
                    && Boolean.TRUE.equals(answer.get("ended"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private CommandResult run(Command command) {
        final CommandResult result;
        try {
            result = context.runner().run(command);
        } catch (CommandException ex) {
            throw new Refused(69, "the host's helper could not be run: " + ex.getMessage());
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
