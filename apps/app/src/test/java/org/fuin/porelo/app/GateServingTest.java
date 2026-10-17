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

        assertThat(GateWiring.serving(project("online"), "127.0.0.1", 9418, state, "shell", "uc"))
                .contains("--pass-on")
                .containsSequence("--ref", "refs/porelo/incoming/shell")
                .containsSequence("--repository", "uc")
                .noneMatch(word -> word.contains("ssh-agent"));
        assertThat(GateWiring.serving(project("guarded"), "127.0.0.1", 9418, state, "shell", null))
                .doesNotContain("--pass-on")
                .doesNotContain("--repository");
    }
}
