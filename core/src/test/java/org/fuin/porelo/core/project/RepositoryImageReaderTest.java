package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RepositoryImageReader}: a repository's own file may say what its image is built
 * from and nothing else, since anything more would let it widen what its tasks may do.
 */
class RepositoryImageReaderTest {

    private static final SnippetFiles NONE =
            (named, origin) -> {
                throw new AssertionError("no snippet file expected, asked for " + named);
            };

    private static ImageSource read(String yaml) {
        return RepositoryImageReader.read(yaml, "porelo.yml", NONE);
    }

    @Test
    void readsTheImageItsRepositoryNeeds() {

        final ImageSource source =
                read(
                        """
                        image:
                          base_image: "ubuntu:24.04"
                          snippet: |
                            RUN apt-get install -y rpm
                          env:
                            JAVA_HOME: /opt/graalvm
                            GRAALVM_HOME: /opt/graalvm
                        """);

        assertThat(source.baseImage()).as("base image").isEqualTo("ubuntu:24.04");
        assertThat(source.snippet()).as("snippet").isEqualTo("RUN apt-get install -y rpm\n");
        assertThat(source.env())
                .as("variables, in the order written")
                .containsExactly(
                        java.util.Map.entry("JAVA_HOME", "/opt/graalvm"),
                        java.util.Map.entry("GRAALVM_HOME", "/opt/graalvm"));
        assertThat(source.from()).as("where it came from").isEqualTo("porelo.yml");
    }

    @Test
    void leavesUnsetWhatTheFileDoesNotSay() {

        // A later source in the order gives it; a value made up here would hide that source.
        final ImageSource source = read("image:\n  env:\n    FOO: bar\n");

        assertThat(source.baseImage()).as("base image").isNull();
        assertThat(source.snippet()).as("snippet").isNull();
    }

    @Test
    void refusesEveryRuleATaskRunsUnderByName() {

        // A repository naming its own egress or limits would widen what its own tasks may reach.
        for (final String key : new String[] {"egress", "limits", "project", "repositories"}) {
            assertThatThrownBy(() -> read("image:\n  base_image: a\n" + key + ": {}\n"))
                    .as(key)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("'" + key + "'")
                    .hasMessageContaining("only image may be set here");
        }
        assertThatThrownBy(() -> read("image:\n  package_sources: [x]\n"))
                .as("where packages are trusted from is the project's")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'image.package_sources'");
    }

    @Test
    void refusesAVariablePoreloSetsInEveryTask() {

        // PATH decides which programs the task runs, HOME where its tools keep their state, and a
        // PORELO_ name where its own token and gate are found.
        for (final String name : new String[] {"PATH", "HOME", "LANG", "PORELO_TASK_REF"}) {
            assertThatThrownBy(() -> read("image:\n  env:\n    " + name + ": x\n"))
                    .as(name)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("set by Porelo");
        }
    }

    @Test
    void refusesAValueThatWouldWriteAContainerfileLineOfItsOwn() {

        assertThatThrownBy(() -> read("image:\n  env:\n    FOO: \"a\\nRUN rm -rf /\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("control character");
        assertThatThrownBy(() -> read("image:\n  env:\n    \"A B\": x\n"))
                .as("a name with a space")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("no variable name");
    }

    @Test
    void readsASnippetFileOfTheRepositoryAndNoneOutsideIt(@TempDir Path dir) throws Exception {

        // A path leaving the repository read any file the person can - a key included - into the
        // image.
        final Path repository = Files.createDirectories(dir.resolve("repository"));
        Files.writeString(repository.resolve("image.sh"), "RUN true\n");
        Files.writeString(dir.resolve("secret"), "not the repository's\n");
        final Path file = repository.resolve("porelo.yml");
        final SnippetFiles beside = SnippetFiles.beside(file);

        assertThat(
                        RepositoryImageReader.read(
                                        "image:\n  snippet_file: image.sh\n",
                                        file.toString(),
                                        beside)
                                .snippet())
                .isEqualTo("RUN true\n");
        assertThatThrownBy(
                        () ->
                                RepositoryImageReader.read(
                                        "image:\n  snippet_file: ../secret\n",
                                        file.toString(),
                                        beside))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("must name a file of the repository");
    }
}
