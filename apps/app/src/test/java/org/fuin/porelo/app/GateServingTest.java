package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;

class GateServingTest {

    private static Project project(final String securityClass) {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: "uc"
                  security_class: "%s"
                  upstream: "https://forge.example/uc.git"
                image:
                  base_image: "ubuntu:24.04"
                """
                                .formatted(securityClass)),
                "test");
    }

    @Test
    void anOnlineTasksGatePassesItsPushOnAndAGuardedOnesKeepsIt() {
        // Checked on the command itself: read back from a start's record, it was there only where a
        // 'porelo' could be started, and a runner without one recorded no gate at all.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"), "127.0.0.1", 9418, state, "shell", "uc", null))
                .contains("--pass-on")
                .containsSequence("--ref", "refs/porelo/incoming/shell")
                .containsSequence("--repository", "uc")
                .noneMatch(word -> word.contains("ssh-agent"));
        assertThat(
                        GateWiring.serving(
                                project("guarded"), "127.0.0.1", 9418, state, "shell", null, null))
                .doesNotContain("--pass-on")
                .doesNotContain("--repository");
    }

    @Test
    void anOnlineTasksGateIsToldItsBranchOnlyWhenItIsNotTodays() {

        // On the recorded command line, so a resumed task passes on where it did; a task started
        // before keeps the command line it had.
        final Path state = Path.of("/run/user/1000/porelo/porelo-uc-shell");

        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "bugfix/porelo/shell"))
                .containsSequence("--branch", "bugfix/porelo/shell");
        assertThat(
                        GateWiring.serving(
                                project("online"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "porelo/shell"))
                .doesNotContain("--branch");
        assertThat(
                        GateWiring.serving(
                                project("guarded"),
                                "127.0.0.1",
                                9418,
                                state,
                                "shell",
                                null,
                                "bugfix/porelo/shell"))
                .as("a guarded task's work waits at the gate; nothing is passed on")
                .doesNotContain("--branch");
    }
}
