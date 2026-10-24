package org.fuin.porelo.core.process;

import org.jspecify.annotations.Nullable;

/** An external program could not be started, or ended with a non-zero exit code. */
public class CommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Command command;

    private final String standardError;

    @Nullable private final transient CommandResult result;

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
        super(
                "Cannot run '"
                        + command.describe()
                        + "'"
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage()),
                cause);
        this.command = command;
        this.result = null;
        this.standardError = standardError;
    }

    /**
     * Returns standard error retained when a run was ended.
     *
     * @return The error output.
     */
    public String standardError() {
        return standardError;
    }

    private CommandException(CommandException original, String standardError) {
        super(original.getMessage(), original.getCause());
        this.command = original.command;
        this.standardError = standardError;
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
