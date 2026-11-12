package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.file.ProjectFileCheck;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Extension;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.ecosystem.api.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests what a start asks the ecosystem adapters a project names, and what it takes back. */
class ToolSettingsTest {

    @TempDir Path dir;

    private static final String TWO_ADAPTERS =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            egress:
              domains: ["repo.example.org"]
            tool_repositories:
              - ecosystem: first
                name: one
                url: "https://repo.example.org/one/"
              - ecosystem: second
                name: two
                url: "https://repo.example.org/two/"
            """;

    /** Answers every adapter with the same file. */
    private static final EcosystemDetector SAME_FILE =
            new EcosystemDetector() {
                @Override
                public Detection detect(
                        final InstalledEcosystems.Description plugin,
                        final Path checkout,
                        final Path home) {
                    throw new AssertionError("a start detects nothing");
                }

                @Override
                public List<File> configure(
                        final InstalledEcosystems.Description plugin,
                        final List<Repository> repositories,
                        final Extension extension) {
                    return List.of(new File(".config/settings", "", false));
                }
            };

    private void installed(final String name) throws IOException {
        final Path adapter = dir.resolve(name);
        Files.writeString(
                adapter,
                "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"" + name + "\"}'\n");
        adapter.toFile().setExecutable(true);
    }

    @Test
    void theProjectFileRefusesWhatTheContractWouldNotCarry() {
        // Refused when the file is read, so a block a start could not send never reaches one.
        assertThat(ProjectFileCheck.EXTENSION_BYTES).isEqualTo(Extension.MOST_BYTES);
        assertThat(ProjectFileCheck.EXTENSION_DEPTH).isEqualTo(Extension.MOST_DEPTH);
    }

    @Test
    void twoAdaptersWritingOneFileStopTheStart() throws IOException {
        installed("first");
        installed("second");
        final Project project = ProjectReader.read(new StringReader(TWO_ADAPTERS), "test.yml");

        final ToolSettings.Answer answer = ToolSettings.ask(project, List.of(dir), SAME_FILE);

        assertThat(answer)
                .isInstanceOfSatisfying(
                        ToolSettings.Answer.Refused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .contains(
                                                "'first' and 'second'",
                                                "/home/agent/.config/settings"));
    }

    @Test
    void anAdapterNotInstalledStopsTheStartNamingIt() throws IOException {
        installed("first");
        final Project project = ProjectReader.read(new StringReader(TWO_ADAPTERS), "test.yml");

        final ToolSettings.Answer answer = ToolSettings.ask(project, List.of(dir), SAME_FILE);

        assertThat(answer)
                .isInstanceOfSatisfying(
                        ToolSettings.Answer.Refused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .contains(
                                                "'second'", "not installed", "tool_repositories"));
    }

    @Test
    void anAdapterIsGivenTheBrokersAddressForARepositoryWithACredential() throws IOException {
        installed("maven");
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                                project: {name: uc, security_class: guarded}
                                image: {from: 'ubuntu:24.04'}
                                egress: {domains: [repo.example.org]}
                                tool_repositories:
                                  - ecosystem: maven
                                    name: public
                                    url: "https://repo.example.org/maven/"
                                  - ecosystem: maven
                                    name: corp-nexus
                                    url: "https://nexus.corp.example/repository/maven/"
                                    credential: repository/corp-nexus
                                """),
                        "test.yml");
        final List<Repository> asked = new java.util.ArrayList<>();
        final EcosystemDetector recording =
                new EcosystemDetector() {
                    @Override
                    public Detection detect(
                            final InstalledEcosystems.Description plugin,
                            final Path checkout,
                            final Path home) {
                        throw new AssertionError("a start detects nothing");
                    }

                    @Override
                    public List<File> configure(
                            final InstalledEcosystems.Description plugin,
                            final List<Repository> repositories,
                            final Extension extension) {
                        asked.addAll(repositories);
                        return List.of();
                    }
                };

        ToolSettings.ask(project, List.of(dir), recording);

        assertThat(asked)
                .as(
                        "the task reads the private one through its broker, by its name, and never"
                                + " learns where it is")
                .containsExactly(
                        new Repository("public", "https://repo.example.org/maven/"),
                        new Repository("corp-nexus", "http://127.0.0.1:9421/corp-nexus/"));
    }

    @Test
    void aProjectThatDeclaresNoneAsksNoAdapter() {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                "project: {name: uc, security_class: guarded}\n"
                                        + "image: {from: 'ubuntu:24.04'}\n"),
                        "test.yml");

        assertThat(ToolSettings.ask(project, List.of(dir.resolve("none")), SAME_FILE))
                .isEqualTo(new ToolSettings.Answer.Files(List.of()));
    }
}
