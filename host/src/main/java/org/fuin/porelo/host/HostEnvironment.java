package org.fuin.porelo.host;

import java.util.Map;
import java.util.Set;

/** Refuses changes to the environment boundary of sudo and root's units. */
final class HostEnvironment {
    static final Set<String> ALLOWED =
            Set.of(
                    "SUDO_USER",
                    "SUDO_UID",
                    "SUDO_GID",
                    "SUDO_COMMAND",
                    "SUDO_HOME",
                    "HOME",
                    "PATH",
                    "LOGNAME",
                    "USER",
                    "SHELL",
                    "MAIL",
                    "TERM",
                    "LANG",
                    "COLUMNS",
                    "LINES",
                    "INVOCATION_ID",
                    "JOURNAL_STREAM",
                    "NOTIFY_SOCKET",
                    "LISTEN_FDS",
                    "LISTEN_PID",
                    "LISTEN_FDNAMES",
                    // systemd 258 and later: the listening process's pidfd id, and each
                    // connection's
                    // cookie of an Accept=yes socket.
                    "LISTEN_PIDFDID",
                    "SO_COOKIE",
                    "RUNTIME_DIRECTORY",
                    "STATE_DIRECTORY",
                    "CACHE_DIRECTORY",
                    "LOGS_DIRECTORY",
                    "CONFIGURATION_DIRECTORY",
                    "CREDENTIALS_DIRECTORY",
                    "SYSTEMD_EXEC_PID",
                    "MANAGERPID",
                    "WATCHDOG_PID",
                    "WATCHDOG_USEC",
                    // Set by sudo's env_reset besides the above (Ubuntu's sudo sets USERNAME;
                    // sudo 1.9.16 adds SUDO_TTY).
                    "USERNAME",
                    "SUDO_TTY",
                    // Set by systemd for some units: memory pressure, a stop's result, a trigger,
                    // OnSuccess/OnFailure's monitored unit, an Accept=yes socket's peer.
                    "MEMORY_PRESSURE_WATCH",
                    "MEMORY_PRESSURE_WRITE",
                    "MAINPID",
                    "PIDFILE",
                    "SERVICE_RESULT",
                    "EXIT_CODE",
                    "EXIT_STATUS",
                    "TRIGGER_UNIT",
                    "TRIGGER_PATH",
                    "TRIGGER_TIMER_REALTIME_USEC",
                    "TRIGGER_TIMER_MONOTONIC_USEC",
                    "MONITOR_SERVICE_RESULT",
                    "MONITOR_EXIT_CODE",
                    "MONITOR_EXIT_STATUS",
                    "MONITOR_INVOCATION_ID",
                    "MONITOR_UNIT",
                    "REMOTE_ADDR",
                    "REMOTE_PORT",
                    // Kept by sudo's env_reset by default (its built-in keep list): a call from a
                    // desktop's terminal carries them. None reaches a child, whose environment is
                    // fixed, and the program's own locale and encoding are fixed in main.
                    "LANGUAGE",
                    "LINGUAS",
                    "_XKB_CHARSET",
                    "TZ",
                    "COLORS",
                    "COLORTERM",
                    "LS_COLORS",
                    "DISPLAY",
                    "XAUTHORITY",
                    "XAUTHORIZATION",
                    "XDG_CURRENT_DESKTOP",
                    "HOSTNAME",
                    "KRB5CCNAME",
                    "PS1",
                    "PS2");

    private HostEnvironment() {}

    static void check(Map<String, String> environment) {
        for (String name : environment.keySet().stream().sorted().toList()) {
            if (!ALLOWED.contains(name) && !name.startsWith("LC_")) {
                // Even an unusual environment name must not inject terminal control characters.
                final String safeName = name.replaceAll("[^a-zA-Z0-9_]", "?");
                throw new HostRefusal(
                        78,
                        "porelo-host: refusing environment variable "
                                + safeName
                                + "; broken host environment (check sudo env_keep or the unit environment)");
            }
        }
    }
}
