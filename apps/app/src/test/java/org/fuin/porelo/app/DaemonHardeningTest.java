package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DaemonHardening}: doctor reports the running daemon's dumpable flag, read from
 * its {@code /proc} entry's owner, not its own process's.
 */
class DaemonHardeningTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    @Test
    void aDaemonWhoseEntriesBelongToItsOwnUserIsDumpable(@TempDir final Path proc)
            throws IOException {
        Files.createFile(Files.createDirectory(proc.resolve("4242")).resolve("environ"));
        runner.answering("MainPID", "4242\n");

        final DaemonHardening read = DaemonHardening.read(runner, proc, "porelod");

        assertThat(read.state()).isEqualTo(DaemonHardening.State.DUMPABLE);
        assertThat(read.said()).startsWith("yes - pid 4242").contains("systemctl --user restart");
    }

    @Test
    void aDaemonWhoseEntriesBelongToAnotherUserIsNotDumpable() {
        // The machine's own init stands in for a daemon whose entries the kernel gave to root.
        runner.answering("MainPID", "1\n");

        final DaemonHardening read = DaemonHardening.read(runner, Path.of("/proc"), "porelod");

        assertThat(read.state())
                .as("read as %s", System.getProperty("user.name"))
                .isEqualTo(
                        "root".equals(System.getProperty("user.name"))
                                ? DaemonHardening.State.DUMPABLE
                                : DaemonHardening.State.NOT_DUMPABLE);
    }

    @Test
    void readsAnEntryUnderTheDaemonsDirectoryWhichStaysItsUsersWhateverTheFlag()
            throws IOException {
        // On a machine /proc/<pid> stays the user's and only what is under it goes to root, so a
        // directory of the user's with nothing under it says nothing.
        runner.answering("MainPID", "4242\n");
        final Path proc = Files.createTempDirectory("proc");
        Files.createDirectory(proc.resolve("4242"));

        assertThat(DaemonHardening.read(runner, proc, "porelod").state())
                .isEqualTo(DaemonHardening.State.UNKNOWN);
    }

    @Test
    void noDaemonIsSaidAsNotRunningRatherThanAsAFinding(@TempDir final Path proc) {
        runner.answering("MainPID", "0\n");

        assertThat(DaemonHardening.read(runner, proc, "porelod").said()).isEqualTo("not running");
    }

    @Test
    void anEntryThatIsGoneIsUnknown(@TempDir final Path proc) {
        runner.answering("MainPID", "4242\n");

        assertThat(DaemonHardening.read(runner, proc, "porelod").said())
                .isEqualTo("unknown - /proc/4242 could not be read");
    }
}
