package org.fuin.porelo.core.process;

/**
 * Runs external programs.
 *
 * <p>An interface rather than a static call, because everything Porelo does to a container goes
 * through {@code podman}, {@code nft} and {@code git}. Tests that had to invoke those for real
 * would be slow, root-dependent and impossible to run in CI.
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Runs a command to completion.
     *
     * @param command Command to run.
     * @return What it left behind, whatever the exit code.
     * @throws CommandException If the program could not be started.
     */
    CommandResult run(Command command);

    /**
     * Runs a command to completion, handing over each line of its standard error, as it comes where
     * the runner can. The whole of it is still in the result.
     *
     * <p>A line is the program's own and not yet safe to show: cleaning and redacting are the
     * caller's. A consumer that fails loses that line and costs the program nothing.
     *
     * @param command Command to run.
     * @param lines Takes each line, without its end.
     * @return What it left behind, whatever the exit code.
     * @throws CommandException If the program could not be started.
     */
    default CommandResult run(
            final Command command, final java.util.function.Consumer<String> lines) {
        final CommandResult result = run(command);
        result.standardError()
                .lines()
                .forEach(
                        line -> {
                            try {
                                lines.accept(line);
                            } catch (RuntimeException ex) {
                                // Only that line is lost.
                            }
                        });
        return result;
    }

    /**
     * Runs a command to completion, handing over every line of its standard output in order, and
     * keeping none of it: for an output that grows with what the program reads, such as a
     * repository's history, of which {@link #run(Command)} keeps only the end.
     *
     * <p>Nothing is lost and nothing is cut: each line is handed whole, without its end, on a
     * thread of the runner's, or the run fails. A consumer that throws ends the program, and what
     * it threw reaches the caller. The default implementation runs the command and hands over its
     * output once it has ended, and refuses an output its runner says was cut.
     *
     * @param command Command to run.
     * @param lines Takes each line of standard output.
     * @return What it left behind, whatever the exit code; its standard output is empty.
     * @throws CommandException If the program could not be started, or its standard output could
     *     not be handed over whole.
     */
    default CommandResult streamOutput(
            final Command command, final java.util.function.Consumer<String> lines) {
        final CommandResult result = run(command);
        if (result.standardOutputCut()) {
            throw new CommandException(
                    command,
                    new java.io.IOException(
                            "its standard output was cut, so it cannot be read whole"),
                    result.standardError());
        }
        result.standardOutput().lines().forEach(lines);
        return new CommandResult(command, result.exitCode(), "", result.standardError());
    }

    /**
     * Runs a command and fails if it does.
     *
     * @param command Command to run.
     * @return What it left behind.
     * @throws CommandException If the program could not be started, or exited non-zero.
     */
    default CommandResult runOrFail(Command command) {
        return run(command).orFail();
    }
}
