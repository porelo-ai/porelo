package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeTest {

    private static final Map<String, String> SESSION =
            Map.of("XDG_RUNTIME_DIR", "/run/user/1008", "PATH", "/home/alice/.local/bin:/usr/bin");

    @Test
    void runsATasksProcessesInAScopeOfTheirOwnWhenThereIsAUserManager() {
        final List<String> wrapped =
                Scope.around(
                        "porelo porelo-p-t gate",
                        List.of("porelo", "gate", "serve"),
                        List.of(),
                        SESSION::get,
                        path -> path.equals(Path.of("/run/user/1008/systemd/private")),
                        path -> path.equals(Path.of("/usr/bin/systemd-run")));

        assertThat(wrapped)
                .containsExactly(
                        "/usr/bin/systemd-run",
                        "--user",
                        "--scope",
                        "--quiet",
                        "--collect",
                        "--slice=porelo.slice",
                        "--description=porelo porelo-p-t gate",
                        "--",
                        "porelo",
                        "gate",
                        "serve");
    }

    @Test
    void aScopeCarriesTheLimitsItIsGivenSoWhatItStartsIsHeldByThemToo() {
        // A task's gate and every git it starts ran with no limit of their own, taking from porelod
        // and every task.
        final List<String> wrapped =
                Scope.around(
                        "porelo porelo-p-t gate",
                        List.of("porelo", "gate", "serve"),
                        List.of("MemoryMax=1G", "TasksMax=256"),
                        SESSION::get,
                        path -> path.equals(Path.of("/run/user/1008/systemd/private")),
                        path -> path.equals(Path.of("/usr/bin/systemd-run")));

        assertThat(wrapped)
                .containsSequence(
                        "--property=MemoryMax=1G", "--property=TasksMax=256", "--", "porelo");
    }

    @Test
    void leavesTheCommandAsItIsWithoutAUserManager() {
        // A container, or a machine without systemd: what it did before, rather than a start that
        // fails.
        assertThat(
                        Scope.around(
                                "x",
                                List.of("porelo"),
                                List.of(),
                                SESSION::get,
                                path -> false,
                                path -> true))
                .containsExactly("porelo");
    }

    @Test
    void leavesTheCommandAsItIsWithoutSystemdRun() {
        assertThat(
                        Scope.around(
                                "x",
                                List.of("porelo"),
                                List.of(),
                                SESSION::get,
                                path -> true,
                                path -> false))
                .containsExactly("porelo");
    }

    @Test
    void leavesTheCommandAsItIsWithoutARuntimeDirectory() {
        assertThat(
                        Scope.around(
                                "x",
                                List.of("porelo"),
                                List.of(),
                                Map.of("PATH", "/usr/bin")::get,
                                path -> true,
                                path -> true))
                .containsExactly("porelo");
    }

    @Test
    void leavesTheCommandAsItIsWhenScopesAreSwitchedOff() {
        final java.util.Map<String, String> off = new java.util.HashMap<>(SESSION);
        off.put(Scope.SWITCH, Scope.OFF);

        assertThat(
                        Scope.around(
                                "x",
                                List.of("porelo"),
                                List.of(),
                                off::get,
                                path -> true,
                                path -> true))
                .containsExactly("porelo");
    }
}
