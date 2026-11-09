package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostEnvironmentTest {
    @Test
    void theSudoAndSystemdNamesAreAcceptedWithoutInterpretingTheirValues() {
        final Map<String, String> environment = new HashMap<>();
        for (String name :
                ("SUDO_USER SUDO_UID SUDO_GID SUDO_COMMAND SUDO_HOME HOME PATH LOGNAME USER SHELL MAIL "
                                + "TERM LANG LC_ALL LC_TIME LC_CUSTOM COLUMNS LINES INVOCATION_ID JOURNAL_STREAM NOTIFY_SOCKET "
                                + "LISTEN_FDS LISTEN_PID LISTEN_FDNAMES RUNTIME_DIRECTORY STATE_DIRECTORY CACHE_DIRECTORY "
                                + "LOGS_DIRECTORY CONFIGURATION_DIRECTORY CREDENTIALS_DIRECTORY SYSTEMD_EXEC_PID MANAGERPID "
                                + "WATCHDOG_PID WATCHDOG_USEC")
                        .split(" ")) {
            environment.put(name, "untrusted value");
        }
        assertThatCode(() -> HostEnvironment.check(environment))
                .as("accepted names do not confer meaning on their values")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "USERNAME",
                "SUDO_TTY",
                "MEMORY_PRESSURE_WATCH",
                "MAINPID",
                "SERVICE_RESULT",
                "TRIGGER_UNIT",
                "MONITOR_UNIT",
                "REMOTE_ADDR",
                "LISTEN_PIDFDID",
                "SO_COOKIE",
                "DISPLAY",
                "XAUTHORITY",
                "TZ",
                "LANGUAGE",
                "KRB5CCNAME",
                "PS1",
                "LS_COLORS",
                "HOSTNAME"
            })
    void whatSudoAndSystemdSetBesidesIsAcceptedToo(String name) {
        // Ubuntu's sudo sets USERNAME and systemd sets these for some units: refused, every call
        // through the sudo rule or such a unit would fail (2026-11-09: systemd 259's
        // LISTEN_PIDFDID and SO_COOKIE stopped every socket-activated porelo-host on the test
        // host).
        assertThatCode(() -> HostEnvironment.check(Map.of(name, "value")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "LD_PRELOAD",
                "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "GIT_CONFIG",
                "GIT_SSH_COMMAND",
                "SSH_AUTH_SOCK",
                "XDG_CONFIG_HOME",
                "XDG_DATA_HOME",
                "http_proxy",
                "HTTP_PROXY",
                "UNLISTED"
            })
    void anUnapprovedVariableIsRefusedWithoutDisclosingItsValue(String name) {
        // Inherited configuration could redirect privileged execution or disclose credentials.
        assertThatThrownBy(() -> HostEnvironment.check(Map.of(name, "private-marker")))
                .as("only explicitly approved names may cross the root boundary")
                .isInstanceOfSatisfying(
                        HostRefusal.class,
                        refusal ->
                                org.assertj.core.api.Assertions.assertThat(refusal.exitCode())
                                        .as("an unsafe environment is EX_CONFIG")
                                        .isEqualTo(78))
                .hasMessageContaining(name)
                .hasMessageNotContaining("private-marker");
    }

    @Test
    void aVariableNameCannotInjectAnotherDiagnosticLineOrTerminalEscape() {
        // Environment names are caller bytes too and must not forge diagnostics.
        assertThatThrownBy(() -> HostEnvironment.check(Map.of("BAD\n\u001b[31m", "private-marker")))
                .as("diagnostics remain one plain line")
                .hasMessageNotContaining("\n")
                .hasMessageNotContaining("\u001b")
                .hasMessageNotContaining("private-marker");
    }
}
