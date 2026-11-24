package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinuxProcessesTest {
    private static final String BOOT = "12345678-1234-1234-1234-123456789abc";

    // A process name may contain spaces and closing parentheses, so token 22 starts after its last
    // one.
    @Test
    void aCurrentIdentityCanBeVerifiedOnThisMachine() throws Exception {
        final var processes = new LinuxProcesses();
        final var identity = processes.current();
        assertThat(identity.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThatCode(() -> processes.verify(identity)).doesNotThrowAnyException();
    }

    @Test
    void theLastParenthesisSeparatesTheCommandFromItsStartTicks() throws Exception {
        final long pid = ProcessHandle.current().pid();
        final var processes =
                new LinuxProcesses(
                        path ->
                                path.toString().endsWith("boot_id")
                                        ? BOOT + "\n"
                                        : pid
                                                + " (name with (spaces) and ))) S "
                                                + "0 ".repeat(18)
                                                + Long.MAX_VALUE
                                                + " 999\n");
        assertThat(processes.current())
                .isEqualTo(new LinuxProcesses.Identity(pid, Long.MAX_VALUE, BOOT));
        assertThatCode(() -> processes.verify(processes.current())).doesNotThrowAnyException();
    }

    // Invalid published identities cannot authenticate a different process or boot.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "0,0,12345678-1234-1234-1234-123456789abc", "-1,0,12345678-1234-1234-1234-123456789abc",
        "4194305,0,12345678-1234-1234-1234-123456789abc",
                "9223372036854775807,0,12345678-1234-1234-1234-123456789abc",
        "1,-1,12345678-1234-1234-1234-123456789abc", "1,0,12345678-1234-1234-1234-123456789ABC",
        "1,0,unknown", "1,0,12345678-1234-1234-1234-123456789abcd"
    })
    void invalidIdentityRangesAndBootIdsAreRefused(long pid, long start, String boot) {
        assertThatThrownBy(() -> new LinuxProcesses.Identity(pid, start, boot))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityBoundsAreInclusive() {
        assertThat(new LinuxProcesses.Identity(1, 0, BOOT).pid()).isEqualTo(1);
        assertThat(new LinuxProcesses.Identity(4194304, Long.MAX_VALUE, BOOT).start())
                .isEqualTo(Long.MAX_VALUE);
    }

    // The default reader must bound real bytes, even for files with misleading reported sizes.
    @Test
    void processReadsRefuseMoreThanTheByteLimit(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        final var file =
                java.nio.file.Files.writeString(directory.resolve("stat"), "a".repeat(65536));
        assertThat(LinuxProcesses.read(file)).hasSize(65536);
        java.nio.file.Files.writeString(file, "a".repeat(65537));
        assertThatThrownBy(() -> LinuxProcesses.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too large");
    }

    // Malformed or dead proc answers cannot prove that a follow is working.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "short",
                "wrong-pid",
                "overflow",
                "negative",
                "missing-close",
                "state",
                "Z",
                "X",
                "x"
            })
    void malformedOrTerminatedProcessesAreRefused(String damage) {
        final long pid = ProcessHandle.current().pid();
        final String stat =
                switch (damage) {
                    case "short" -> pid + " (follow) S 0";
                    case "missing-close" -> pid + " (follow S " + "0 ".repeat(19);
                    case "wrong-pid" -> "0 (follow) S " + "0 ".repeat(19);
                    case "overflow" ->
                            pid + " (follow) S " + "0 ".repeat(18) + "9223372036854775808";
                    case "negative" -> pid + " (follow) S " + "0 ".repeat(18) + "-1";
                    default -> pid + " (follow) " + damage + " " + "0 ".repeat(19);
                };
        final var processes =
                new LinuxProcesses(path -> path.toString().endsWith("boot_id") ? BOOT : stat);
        assertThatThrownBy(processes::current)
                .as("an unconfirmed identity cannot be published")
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> processes.verify(new LinuxProcesses.Identity(pid, 0, BOOT)))
                .isInstanceOf(IOException.class);
    }
}
