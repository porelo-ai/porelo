package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * Whether the running daemon, which holds the vault's secrets, can be core-dumped, read from
 * outside it.
 *
 * <p>{@code doctor} printed the flags of its own process, which nothing hardens, so a line that
 * read like a finding described a process that holds no secret. The daemon sets itself not dumpable
 * first thing; the kernel shows that by giving its entries under {@code /proc} to root, since
 * {@code /proc/<pid>/status} has no field for it.
 *
 * @param pid The daemon's pid, or {@code null} when it is not running or could not be asked.
 * @param state What the report says.
 */
record DaemonHardening(@Nullable Long pid, State state) {

    /** What is known of the daemon's dumpable flag. */
    enum State {

        /** Its entries belong to another user: the kernel's sign that it is not dumpable. */
        NOT_DUMPABLE,

        /**
         * Its entries belong to its own user: a core dump or ptrace of it reads the vault's
         * secrets.
         */
        DUMPABLE,

        /** No daemon runs for this account. */
        NOT_RUNNING,

        /** The user manager could not say, or the entry could not be read. */
        UNKNOWN
    }

    /**
     * Reads the daemon's state.
     *
     * @param runner What runs {@code systemctl}.
     * @param proc Where the processes are, {@code /proc} on a machine.
     * @param unit The daemon's unit.
     * @return What is known.
     */
    static DaemonHardening read(final CommandRunner runner, final Path proc, final String unit) {
        final CommandResult shown =
                runner.run(
                        Command.of(
                                "systemctl", "--user", "show", "-p", "MainPID", "--value", unit));
        if (shown.exitCode() != 0) {
            return new DaemonHardening(null, State.UNKNOWN);
        }
        final long pid;
        try {
            pid = Long.parseLong(shown.trimmedOutput());
        } catch (NumberFormatException ex) {
            return new DaemonHardening(null, State.UNKNOWN);
        }
        if (pid <= 0) {
            return new DaemonHardening(null, State.NOT_RUNNING);
        }
        // An entry inside it, not the directory: /proc/<pid> stays the user's whatever the flag,
        // while
        // what is under it goes to root - read on the VM, where the directory said "dumpable" of a
        // daemon whose environ was root's.
        final Path entry = proc.resolve(Long.toString(pid)).resolve("environ");
        try {
            final String owner = Files.getOwner(entry).getName();
            return new DaemonHardening(
                    pid,
                    owner.equals(System.getProperty("user.name"))
                            ? State.DUMPABLE
                            : State.NOT_DUMPABLE);
        } catch (IOException | SecurityException ex) {
            return new DaemonHardening(pid, State.UNKNOWN);
        }
    }

    /**
     * Says it on one line, after the label.
     *
     * @return The line's text.
     */
    String said() {
        return switch (state) {
            case NOT_DUMPABLE -> "no - pid " + pid + ", its /proc entries belong to root";
            case DUMPABLE ->
                    "yes - pid "
                            + pid
                            + ": a core dump or ptrace of it reads the vault's secrets; restart it with"
                            + " 'systemctl --user restart porelod'";
            case NOT_RUNNING -> "not running";
            case UNKNOWN ->
                    pid == null
                            ? "unknown - systemctl --user could not say which process it is"
                            : "unknown - /proc/" + pid + " could not be read";
        };
    }
}
