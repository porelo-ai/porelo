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
                          from: ubuntu:24.04
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
        // A host carries the project for this person: root writes its file.
        host = new HostForgeAddress(conf, hostFiles(""));
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
                                  from: ubuntu:24.04
                                repositories:
                                  first:
                                    upstream: "git@central.example:acme/-x.git"
                                    central: "https://central.example/acme/-x"
                                """),
                        "project.yml");

        assertThat(host.refusal(file, "first", null)).contains("no central");
    }

    /** The host's file of the project, naming the repositories it carries. */
    private Path hostFiles(final String repositories) throws Exception {
        final Path files = dir.resolve("host/projects");
        Files.createDirectories(files);
        Files.writeString(
                files.resolve("demo.json"),
                """
                {"project": "demo", "revision": "56420b3", "address": "git@central.example:acme/demo.git",
                 "fetch_from": null, "signing_keys": [], "homeserver": null, "room": null,
                 "repositories": [%s]}
                """
                        .formatted(repositories));
        return files;
    }

    @Test
    void aStartOnARepositoryTheHostDoesNotCarryIsRefused() throws Exception {
        // Started, the task's work has no copy on this host's forge to go to.
        final HostForgeAddress carrying = new HostForgeAddress(conf, hostFiles("\"second\""));
        final Project file =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: demo
                                  security_class: guarded
                                image:
                                  from: ubuntu:24.04
                                repositories:
                                  first:
                                    upstream: "git@central.example:acme/first.git"
                                    central: "git@central.example:acme/first.git"
                                  second:
                                    upstream: "git@central.example:acme/second.git"
                                    central: "git@central.example:acme/second.git"
                                """),
                        "project.yml");
        assertThat(carrying.refusal(file, "first", null))
                .as("the host's file names only 'second'")
                .isNotNull();
        assertThat(carrying.refusal(file, "second", null))
                .as("named by the host's file, with a central")
                .isNull();
    }

    @Test
    void aProjectCarriedWholeRefusesNoRepositoryForNotBeingNamed() throws Exception {
        final HostForgeAddress carrying = new HostForgeAddress(conf, hostFiles(""));
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(carrying.refusal(file, "first", null))
                .as("a host file naming no repository carries the whole project")
                .isNull();
    }

    @Test
    void aHostFileThatDoesNotReadRefusesTheStart() throws Exception {
        final Path files = hostFiles("");
        Files.writeString(files.resolve("demo.json"), "{not json");
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(new HostForgeAddress(conf, files).refusal(file, "first", null))
                .as("what the host carries cannot be read, so the start does not go on")
                .isNotNull();
    }

    @Test
    void aStartOnAHostThatCarriesTheProjectNotForThisPersonIsRefused() throws Exception {
        // Its rows are read at the host's forge, where it keeps no copy for this person.
        final Path files = dir.resolve("other-host/projects");
        Files.createDirectories(files);
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(new HostForgeAddress(conf, files).refusal(file, "first", null))
                .as("no file of the project on this host for this person")
                .isNotNull();
    }

    @Test
    void aStartByAPersonLockedOnTheHostIsRefused() throws Exception {
        // Root takes a locked member's read of the file off; the refusal must not go with it.
        final Path files = hostFiles("\"second\"");
        java.nio.file.Files.setPosixFilePermissions(
                files.resolve("demo.json"),
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        final Project file = project("    central: \"git@central.example:acme/first.git\"\n");

        assertThat(new HostForgeAddress(conf, files).refusal(file, "first", null))
                .as("the host's file can no longer be read by this person")
                .isNotNull();
    }
}
