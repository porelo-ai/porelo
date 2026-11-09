package org.fuin.porelo.core.project.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reads every example of {@code doc/project-file.md} with the reader it documents, and writes it
 * back: an example a person copies must be a file Porelo takes, and one it writes the same way.
 */
@Tag("documents")
class FileExamplesTest {

    private static final Path PAGE = Path.of("..", "doc", "project-file.md");

    private static List<String> examples() throws IOException {
        final Matcher block =
                Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL)
                        .matcher(Files.readString(PAGE));
        final List<String> found = new ArrayList<>();
        while (block.find()) {
            found.add(block.group(1));
        }
        return found;
    }

    private static boolean isProjectFile(final String yaml) {
        return yaml.lines().anyMatch(line -> line.equals("project:"));
    }

    @Test
    void everyExampleIsAFileTheReaderTakesAndTheWriterWritesAlike() throws IOException {

        final List<String> examples = examples();

        // A guard that found no example would pass forever.
        assertThat(examples.stream().filter(FileExamplesTest::isProjectFile))
                .as("project file examples in " + PAGE)
                .hasSize(1);
        assertThat(examples.stream().filter(yaml -> !isProjectFile(yaml)))
                .as("porelo.yml examples in " + PAGE)
                .hasSize(2);
        for (final String yaml : examples) {
            if (addsSettings(yaml)) {
                continue;
            }
            if (isProjectFile(yaml)) {
                final ProjectFile file =
                        ProjectFileReader.read(new StringReader(yaml), "project-file.md", null);
                final String written = ProjectFileWriter.write(file);
                assertThat(ProjectFileReader.read(new StringReader(written), "written", null))
                        .as("the documented project file, written and read back")
                        .isEqualTo(file);
            } else {
                final RepositoryFile file =
                        RepositoryFileReader.read(new StringReader(yaml), "project-file.md", null);
                final String written = RepositoryFileWriter.write(file);
                assertThat(RepositoryFileReader.read(new StringReader(written), "written", null))
                        .as("the documented porelo.yml, written and read back")
                        .isEqualTo(file);
            }
        }
    }

    @Test
    void theDocumentedSettingsAddToTheDocumentedProjectAsThePageSays() throws IOException {
        final List<String> examples = examples();
        final String settings =
                examples.stream().filter(FileExamplesTest::addsSettings).findFirst().orElseThrow();
        final String project =
                examples.stream().filter(FileExamplesTest::isProjectFile).findFirst().orElseThrow();

        final RepositorySettings.Read read =
                RepositorySettings.read(
                        new StringReader(project),
                        "project-file.md",
                        null,
                        List.of(new RepositorySettings.Layer("porelo.yml of shop", settings)));

        assertThat(read.file().egress().domains()).containsExactly("build.example.org");
        assertThat(read.file().limits().memory()).isEqualTo("16g");
        assertThat(read.file().build()).isNotNull();
        assertThat(read.file().build().packageSources()).isNull();
        assertThat(read.asked())
                .extracting(RepositorySettings.Asked::key)
                .containsExactly(
                        "egress.domains[build.example.org]",
                        "limits.memory",
                        "build.package_sources[http://azure.archive.ubuntu.com/ubuntu/]",
                        "egress.domains[nexus.corp.example]");
    }

    /** The example of a repository's own settings: no image, settings the project file holds. */
    private static boolean addsSettings(final String yaml) {
        return !isProjectFile(yaml) && yaml.lines().noneMatch(line -> line.equals("image:"));
    }

    @Test
    void theDocumentedProjectFileSaysWhatThePageSaysItDoes() throws IOException {

        final ProjectFile file =
                ProjectFileReader.read(
                        new StringReader(
                                examples().stream()
                                        .filter(FileExamplesTest::isProjectFile)
                                        .findFirst()
                                        .orElseThrow()),
                        "project-file.md",
                        null);

        // Every optional key, because the point of the page is the ones a first file leaves out.
        assertThat(file.project().name()).as("the project's name").isEqualTo("myproject");
        assertThat(file.project().securityClass()).as("its class").isEqualTo("guarded");
        assertThat(file.project().nickname()).as("its nickname").isEqualTo("Planner");
        assertThat(file.image().from()).as("the image's base").isEqualTo("ubuntu:24.04");
        assertThat(file.image().run()).as("the image's lines").contains("apt-get install");
        assertThat(file.build().packageSources())
                .as("the documented package sources")
                .allMatch(source -> source.startsWith("http://"));
        assertThat(file.egress().hostServices())
                .as("the documented services on this host")
                .containsExactly("forge.internal.example", "registry.internal.example");
        assertThat(file.limits().handIn()).as("the documented hand-in limit").isEqualTo("64m");
        assertThat(file.repositories())
                .as("the repositories")
                .containsOnlyKeys("backend", "frontend");
        assertThat(file.repositories().get("backend").issues())
                .as("the documented prefixes")
                .containsExactly("B", "P");
        assertThat(file.repositories().get("backend").nickname())
                .as("the documented nickname")
                .isEqualTo("Payments");
        assertThat(file.credentials())
                .as("the credentials")
                .containsEntry("search", "brave-search");
        assertThat(file.parameters()).as("the parameters").containsOnlyKeys("type", "ticket");
        assertThat(file.parameters().get("ticket").env())
                .as("the ticket's variable")
                .isEqualTo("TASK_TICKET");
        assertThat(file.mail().peers().get("reviewer").address())
                .as("the documented peer")
                .isEqualTo("matrix:");
        assertThat(file.branch().pattern()).as("the documented branch pattern").isNotNull();
        // The page's model id is a real one, which only a document may name.
        assertThat(file.providers().values())
                .as("the documented default model")
                .singleElement()
                .satisfies(entry -> assertThat(entry.label()).as("with its label").isNotNull());
    }
}
