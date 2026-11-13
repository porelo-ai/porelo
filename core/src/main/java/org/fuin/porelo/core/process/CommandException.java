package org.fuin.porelo.core.process;

import org.jspecify.annotations.Nullable;

/**
 * An external program could not be started, ended with a non-zero exit code, or was ended at its
 * limit.
 */
public class CommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Command command;

    private final String standardError;

    @Nullable private final transient CommandResult result;

    /** The limit the program was ended at, or {@code null} when it ended by itself. */
    private final java.time.@Nullable Duration limit;

    /**
     * Constructor for a program that ran and failed.
     *
     * @param command The command.
     * @param result What it left behind.
     */
    public CommandException(Command command, CommandResult result) {
        super(message(command, result));
        this.command = command;
        this.result = result;
        this.standardError = result.standardError();
        this.limit = null;
    }

    /**
     * Constructor for a program that could not be started.
     *
     * @param command The command.
     * @param cause Cause.
     */
    public CommandException(Command command, Throwable cause) {
        this(command, cause, "");
    }

    /**
     * Creates a failure retaining a diagnostic from an ended run.
     *
     * @param command The command.
     * @param cause Why it ended.
     * @param standardError What it read before ending.
     */
    public CommandException(Command command, Throwable cause, String standardError) {
        this(command, cause, standardError, null);
    }

    private CommandException(
            Command command,
            Throwable cause,
            String standardError,
            java.time.@Nullable Duration limit) {
        super(
                "Cannot run '"
                        + command.describe()
                        + "'"
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage()),
                cause);
        this.command = command;
        this.result = null;
        this.standardError = standardError;
        this.limit = limit;
    }

    /**
     * Creates the failure of a program that was ended at its limit.
     *
     * @param command The command.
     * @param limit The limit it reached.
     * @param standardError What it said before it was ended.
     * @return The failure, which says it {@link #timedOut()}.
     */
    public static CommandException timedOut(
            Command command, java.time.Duration limit, String standardError) {
        return new CommandException(
                command,
                new java.io.IOException("Timed out after " + limit.toSeconds() + " s"),
                standardError,
                limit);
    }

    /**
     * Returns standard error retained when a run was ended.
     *
     * @return The error output.
     */
    public String standardError() {
        return standardError;
    }

    /**
     * Returns whether the program was ended at its limit rather than failing by itself, so a caller
     * need not read the message to tell.
     *
     * @return Whether the limit was reached.
     */
    public boolean timedOut() {
        return limit != null;
    }

    /**
     * Returns the limit the program was ended at: the shorter of its own and the runner's.
     *
     * @return The limit, or {@code null} when it ended by itself.
     */
    public java.time.@Nullable Duration limit() {
        return limit;
    }

    private CommandException(CommandException original, String standardError) {
        super(original.getMessage(), original.getCause());
        this.command = original.command;
        this.standardError = standardError;
        this.limit = original.limit;
        final CommandResult previous = original.result;
        this.result =
                previous == null
                        ? null
                        : new CommandResult(
                                command,
                                previous.exitCode(),
                                previous.standardOutput(),
                                standardError);
    }

    /**
     * Replaces retained stderr without changing the refusal or its underlying cause.
     *
     * @param standardError The diagnostic safe for the caller to retain.
     * @return The same failure with the replacement diagnostic.
     */
    public CommandException withStandardError(String standardError) {
        return new CommandException(this, standardError);
    }

    private static String message(Command command, CommandResult result) {
        // The failing command's own error output is the useful part. Without it the caller sees
        // 'exit code 125' and has to reproduce the run by hand to learn anything.
        final String detail =
                result.standardError().isBlank()
                        ? result.standardOutput().strip()
                        : result.standardError().strip();
        return "'"
                + command.describe()
                + "' failed with exit code "
                + result.exitCode()
                + (detail.isEmpty() ? "" : ": " + detail);
    }

    /**
     * Returns the command that failed.
     *
     * @return Command.
     */
    public Command getCommand() {
        return command;
    }

    /**
     * Returns what the program left behind.
     *
     * @return Result, or {@code null} if the program never started.
     */
    @Nullable
    public CommandResult getResult() {
        return result;
    }
}
