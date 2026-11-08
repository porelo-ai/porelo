package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An online task's push on a host goes to the copy its forge holds, named after central. */
class HostPushRepositoryTest {

    private static final Project PORELO =
            ProjectReader.read(
                    new StringReader(
                            """
                            project:
                              name: porelo
                              security_class: online
                              central: "git@github.com:porelo-ai/porelo-project.git"
                            image:
                              from: ubuntu:24.04
                            repositories:
                              core:
                                central: "git@github.com:porelo-ai/porelo.git"
                              github:
                                central: "git@github.com:porelo-ai/porelo-adapters.git"
                              host:
                                central: "git@github.com:porelo-ai/porelo-host.git"
                              site:
                                central: "git@github.com:porelo-ai/porelo-ai.github.io.git"
                            """),
                    "project.yml");

    private static Repository row(final Project project, final String name) {
        return project.repositories().stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theRepositoryPartIsCentralsLastPartNotTheRowsName(@TempDir final Path dir)
            throws Exception {
        final Path conf = Files.writeString(dir.resolve("forge.conf"), "git_port=3001\n");
        final Project placed = new HostForgeAddress(conf).placed(PORELO);

        assertThat(TaskLaunch.hostPushRepository(placed, row(placed, "core")))
                .isEqualTo("porelo/porelo");
        assertThat(TaskLaunch.hostPushRepository(placed, row(placed, "github")))
                .isEqualTo("porelo/porelo-adapters");
        assertThat(TaskLaunch.hostPushRepository(placed, row(placed, "host")))
                .isEqualTo("porelo/porelo-host");
        assertThat(TaskLaunch.hostPushRepository(placed, row(placed, "site")))
                .isEqualTo("porelo/porelo-ai.github.io");
        // The same copy the gate's own check of a taken branch asks about.
        assertThat(row(placed, "core").upstream())
                .isEqualTo("http://127.0.0.1:3001/porelo/porelo.git");
    }
}
