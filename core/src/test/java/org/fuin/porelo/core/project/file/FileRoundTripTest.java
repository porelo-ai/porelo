package org.fuin.porelo.core.project.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A model written and read back is the model it was, and written again is the same text: what
 * Porelo writes is what it reads, key for key, so a written file never means something else.
 */
class FileRoundTripTest {

    /** Every type of the project file, each filled. */
    static ProjectFile full() {
        final Map<String, Parameter> parameters = new LinkedHashMap<>();
        parameters.put(
                "type",
                new Parameter(
                        "The kind of work",
                        List.of("feature", "bugfix"),
                        null,
                        "feature",
                        true,
                        null));
        parameters.put(
                "ticket",
                new Parameter("The ticket", null, "[A-Z]+-[0-9]+", null, false, "TASK_TICKET"));
        parameters.put("quiet", new Parameter(null, null, null, null, null, "none"));
        final Map<String, RepositoryEntry> repositories = new LinkedHashMap<>();
        repositories.put(
                "backend",
                new RepositoryEntry(
                        "git@github.com:you/backend.git",
                        "git@github.com:acme/backend.git",
                        "optional",
                        "The payments side",
                        List.of("B", "P"),
                        "helper",
                        "Payments",
                        "early-bird",
                        new BranchScheme("{type}/{task}", "^(feature|bugfix)/.+$")));
        repositories.put(
                "front.end",
                new RepositoryEntry(
                        "https://forge.example/you/front.end.git",
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null));
        final Map<String, Mail.Peer> peers = new LinkedHashMap<>();
        peers.put("reviewer", new Mail.Peer("matrix:", "external", 200, "prompt"));
        peers.put("task:other:worker", new Mail.Peer("matrix:@w:example.org", "vouched", 7, null));
        final Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("homeserver", "https://matrix.example.org");
        matrix.put("room", "#shop:example.org");
        matrix.put("port", "8448");
        return new ProjectFile(
                new ProjectInfo(
                        "shop",
                        "The thing that talks to the payment provider",
                        "online",
                        "git@github.com:you/shop.git",
                        "required",
                        "Planner"),
                new Image(
                        "ubuntu:24.04",
                        Map.of("JAVA_HOME", "/opt/graalvm"),
                        "ARG VERSION=1\nENV TOOL=/opt/tool\nRUN apt-get update \\\n"
                                + "    && apt-get install -y jq\n",
                        null),
                new BuildSources(List.of("http://azure.archive.ubuntu.com/ubuntu/")),
                new Egress(
                        List.of("maven", "git-hosting"),
                        List.of("nexus.corp.example"),
                        List.of("forge.internal.example"),
                        List.of("telemetry.nexus.corp.example")),
                new Limits("16g", 4096, "2.5", "64m", 2),
                new Mail(
                        peers,
                        new Mail.Rules("allow", "prompt", "deny"),
                        "reporting",
                        Map.of("matrix", new Mail.Transport(matrix))),
                new Builds("github", "github-actions", "https://ghe.example/api/v3", "all"),
                parameters,
                new BranchScheme("{type}/porelo/{task}", "^(feature|bugfix)/porelo/.+$"),
                Map.of("search", "brave-search"),
                repositories);
    }

    static Stream<ProjectFile> projectFiles() {
        return Stream.of(
                full(),
                new ProjectFile(
                        new ProjectInfo("plain", null, "guarded", null, null, null),
                        new Image("ubuntu:24.04", Map.of(), null, "Containerfile.fragment"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        Map.of(),
                        Map.of("work", emptyEntry())),
                new ProjectFile(
                        new ProjectInfo("closed", "", "offline", null, null, null),
                        new Image("ubuntu:24.04", Map.of(), null, null),
                        new BuildSources(null),
                        new Egress(List.of(), List.of(), List.of("forge.internal.example"), null),
                        new Limits("none", null, "none", null, 0),
                        new Mail(Map.of(), null, null, Map.of("matrix", new Mail.Transport(null))),
                        null,
                        Map.of(),
                        new BranchScheme(null, "^porelo/.+$"),
                        Map.of(),
                        Map.of()));
    }

    private static RepositoryEntry emptyEntry() {
        return new RepositoryEntry(null, null, null, null, List.of(), null, null, null, null);
    }

    @Test
    void theFullFileHoldsEveryTypeFilled() {

        // A round trip of a file that left a type out would prove nothing about that type.
        final ProjectFile full = full();

        assertThat(
                        List.of(
                                full.project(),
                                full.image(),
                                full.build(),
                                full.egress(),
                                full.limits(),
                                full.mail(),
                                full.builds(),
                                full.branch()))
                .as("every section of the full file")
                .doesNotContainNull();
        assertThat(full.mail().rules()).as("the mail rules").isNotNull();
        assertThat(full.mail().peers()).as("the peers").isNotEmpty();
        assertThat(full.mail().transports()).as("the transports").isNotEmpty();
        assertThat(full.parameters()).as("the parameters").isNotEmpty();
        assertThat(full.credentials()).as("the credentials").isNotEmpty();
        assertThat(full.repositories().get("backend").branch())
                .as("a repository's branch scheme")
                .isNotNull();
        assertThat(ProjectFileCheck.check(full, null)).as("the check of the full file").isEmpty();
    }

    @ParameterizedTest
    @MethodSource("projectFiles")
    void aProjectFileWrittenAndReadIsTheSameFileAndTheSameText(final ProjectFile file) {

        final String written = ProjectFileWriter.write(file);
        final ProjectFile read =
                ProjectFileReader.read(new StringReader(written), "project.yml", null);

        assertThat(read).as("the file read back from:\n" + written).isEqualTo(file);
        assertThat(ProjectFileWriter.write(read)).as("the file written again").isEqualTo(written);
    }

    static Stream<RepositoryFile> repositoryFiles() {
        return Stream.of(
                new RepositoryFile(
                        new Image(
                                "ubuntu:24.04@sha256:0123",
                                Map.of("JAVA_HOME", "/opt/graalvm", "EMPTY", ""),
                                "RUN apt-get install -y rpm\n",
                                null)),
                new RepositoryFile(new Image(null, Map.of(), null, "build/Containerfile.part")),
                new RepositoryFile(new Image(null, Map.of(), null, null)));
    }

    @ParameterizedTest
    @MethodSource("repositoryFiles")
    void aRepositoryFileWrittenAndReadIsTheSameFileAndTheSameText(final RepositoryFile file) {

        final String written = RepositoryFileWriter.write(file);
        final RepositoryFile read =
                RepositoryFileReader.read(new StringReader(written), "porelo.yml", null);

        assertThat(read).as("the file read back from:\n" + written).isEqualTo(file);
        assertThat(RepositoryFileWriter.write(read))
                .as("the file written again")
                .isEqualTo(written);
    }

    @Test
    void aFileTheCheckRefusesIsNotWritten() {

        // Only a usable file is written: a written file that is refused at the next read is lost
        // work for whoever relied on it.
        final ProjectFile online = full();
        final ProjectFile guarded =
                new ProjectFile(
                        new ProjectInfo("shop", null, "guarded", null, null, null),
                        online.image(),
                        null,
                        null,
                        null,
                        null,
                        online.builds(),
                        Map.of(),
                        null,
                        Map.of(),
                        Map.of());

        assertThatThrownBy(() -> ProjectFileWriter.write(guarded))
                .as("a guarded project with builds")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining("'builds' is for online projects only");
        assertThatThrownBy(() -> RepositoryFileWriter.write(new RepositoryFile(null)))
                .as("a repository's file without an image")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining("'image' is required");
    }
}
