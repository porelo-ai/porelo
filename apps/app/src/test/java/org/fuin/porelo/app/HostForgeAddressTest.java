package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** On a host a task's work reaches the host's own forge, whatever the project's file says. */
class HostForgeAddressTest {

    private static final String LOCAL = "http://127.0.0.1:3001/demo/first.git";

    @TempDir private Path dir;

    private Path conf;

    private HostForgeAddress host;

    private static Project project(final String central) {
        return ProjectReader.read(
                new StringReader(
                        """
                        project:
                          name: demo
                          security_class: guarded
                        image:
                          base_image: ubuntu:24.04
                        repositories:
                          first:
                            upstream: "git@central.example:acme/first.git"
                        """
                                + central
                                + """
                          second:
                            upstream: "git@central.example:acme/second.git"
                        """),
                "project.yml");
    }

    @BeforeEach
    void forge() throws Exception {
        conf = dir.resolve("forge.conf");
        Files.writeString(conf, "port=3000\ngit_port=3001\n");
        host = new HostForgeAddress(conf);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git@central.example:acme/first.git",
                "ssh://git@central.example/acme/first.git",
                "https://central.example/acme/first",
                "git@central.example:first.git",
                "https://central.example/acme/first.git/"
            })
    void aRowWithACentralIsReachedOnTheHostsForge(final String central) {
        final Project placed = host.placed(project("    central: \"" + central + "\"\n"));

        assertThat(placed.repository("first").upstream()).isEqualTo(LOCAL);
        assertThat(placed.repository("first").central()).isEqualTo(central);
    }

    @Test
    void aRowWithNoCentralKeepsItsAddressAndATaskThereIsRefused() {
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(host.placed(file).repository("second").upstream())
                .isEqualTo("git@central.example:acme/second.git");
        assertThat(host.refusal(file, "second", null)).contains("'second'", "central:");
        assertThat(host.refusal(file, "first", null)).isNull();
    }

    @Test
    void aStartMayNotNameAnotherAddressThanTheForgesOnAHost() {
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(host.refusal(file, "first", "git@central.example:acme/first.git"))
                .contains("--upstream");
        assertThat(host.refusal(file, "first", LOCAL)).isNull();
    }

    @Test
    void aMachineThatIsNoHostLeavesTheFileAsItIs() throws Exception {
        Files.delete(conf);
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(host.placed(file)).isEqualTo(file);
        assertThat(host.refusal(file, "second", null)).isNull();
        assertThat(host.refusal(file, "first", "git@elsewhere.example:x.git")).isNull();
    }

    @Test
    void aCentralWhoseLastPartIsNoNameOnTheForgeIsNoCopy() {
        final Project file =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: demo
                                  security_class: guarded
                                image:
                                  base_image: ubuntu:24.04
                                repositories:
                                  first:
                                    upstream: "git@central.example:acme/-x.git"
                                    central: "https://central.example/acme/-x"
                                """),
                        "project.yml");

        assertThat(host.refusal(file, "first", null)).contains("no central");
    }
}
