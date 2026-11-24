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
