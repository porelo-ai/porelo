package org.fuin.porelo.core.process;

/**
 * What an external program left behind.
 *
 * @param command The command that produced this result.
 * @param exitCode Exit code.
 * @param standardOutput Everything written to standard output, or only its end when {@code
 *     standardOutputCut} says so.
 * @param standardError Everything written to standard error.
 * @param standardOutputCut Whether standard output was longer than the runner keeps, so {@code
 *     standardOutput} holds only its end, behind a line saying how much went: a caller that must
 *     read the whole of it refuses such a result, or reads it with {@link
 *     CommandRunner#streamOutput}.
 */
public record CommandResult(
        Command command,
        int exitCode,
        String standardOutput,
        String standardError,
        boolean standardOutputCut) {

    /**
     * A result whose standard output is whole.
     *
     * @param command The command that produced this result.
     * @param exitCode Exit code.
     * @param standardOutput Everything written to standard output.
     * @param standardError Everything written to standard error.
     */
    public CommandResult(
            Command command, int exitCode, String standardOutput, String standardError) {
        this(command, exitCode, standardOutput, standardError, false);
    }

    /** Without what it holds, as a line must never carry that. */
    @Override
    public String toString() {
        return "CommandResult[exitCode=" + exitCode + "]";
    }

    /**
     * Tells whether the program succeeded.
     *
     * @return {@code true} if the exit code is zero.
     */
    public boolean successful() {
        return exitCode == 0;
    }

    /**
     * Returns standard output without trailing line separators.
     *
     * @return Trimmed output.
     */
    public String trimmedOutput() {
        return standardOutput.strip();
    }

    /**
     * Returns this result, or throws if the program failed.
     *
     * @return This result.
     * @throws CommandException If the exit code is not zero.
     */
    public CommandResult orFail() {
        if (!successful()) {
            throw new CommandException(command, this);
        }
        return this;
    }
}
