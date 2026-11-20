package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileException;
import org.fuin.porelo.core.project.file.ProjectFileReader;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@code tracker}, which the person's planner keeps in the project's file: a file that
 * holds them must read and start as one without them, keep them through a rewrite, and let no
 * repository's own file set them.
 */
class PlanningKeysTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            repositories:
              shop:
                issues: ["B", "P"]
            """;

    private static final String PLANNED =
            """
            tracker:
              kind: github
              project: acme/7
            """;

    private static ProjectFile file(final String yaml) {
        return ProjectFileReader.read(new StringReader(yaml), "project.yml", null);
    }

    private static List<String> faults(final String section) {
        final ProjectFileException refused =
                catchThrowableOfType(ProjectFileException.class, () -> file(HEAD + section));
        assertThat(refused).as("the refusal of:\n" + section).isNotNull();
        return refused.faults();
    }

    @Test
    void aFileWithATrackerReadsAsAProject() {
        // Every start reads the project's file: one the planner wrote to must not stop them.
        final Project project = ProjectReader.read(new StringReader(HEAD + PLANNED), "project.yml");

        assertThat(project.name()).as("the project reads as before").isEqualTo("p");
    }

    @Test
    void theTrackersSettingsAreReadInTheOrderWritten() {
        final ProjectFile read = file(HEAD + PLANNED);

        assertThat(read.tracker())
                .as("the planner's settings, as written")
                .containsExactly(Map.entry("kind", "github"), Map.entry("project", "acme/7"));
    }

    @Test
    void aFileWithoutOneHasNoTracker() {
        final ProjectFile read = file(HEAD);

        assertThat(read.tracker()).as("absent is not an empty tracker").isNull();
    }

    @Test
    void aRewriteKeepsTheTrackerAsRead() {
        // The window writes the file back; what the planner kept must survive it unchanged.
        final ProjectFile read = file(HEAD + PLANNED);
        final ProjectFile again = file(ProjectFileWriter.write(read));
        assertThat(again.tracker())
                .as("the tracker, in order, after a rewrite")
                .containsExactlyEntriesOf(read.tracker());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "tracker:\\n|tracker|needs 'kind'",
                "tracker:\\n  project: acme/7\\n|tracker|needs 'kind'",
                "tracker:\\n  kind: GitHub\\n|tracker.kind|lower-case",
                "tracker:\\n  kind: -x\\n|tracker.kind|lower-case",
                "tracker:\\n  kind: github\\n  project:\\n|tracker.project|one line",
                "tracker:\\n  kind: github\\n  Project: x\\n|tracker.Project|no setting's name",
                "tracker:\\n  kind: github\\n  project: [a]\\n|tracker.project|single value",
                "tracker: github\\n|tracker|mapping",
                "tracker:\\n  kind: github\\n  a234567890123456789012345678901234567890123456789012345678901234: x\\n"
                        + "|tracker.a234567890123456789012345678901234567890123456789012345678901234"
                        + "|no setting's name"
            })
    void aMalformedEntryIsRefusedByItsPath(
            final String section, final String path, final String what) {
        assertThat(faults(section.replace("\\n", "\n")))
                .as("refused, naming where")
                .anySatisfy(fault -> assertThat(fault).contains("'" + path + "'").contains(what));
    }

    @Test
    void aTrackerValueOverTwoLinesIsRefused() {
        assertThat(faults("tracker:\n  kind: github\n  project: \"acme\\n7\"\n"))
                .as("a setting over two lines is refused by its path")
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .contains("'tracker.project'")
                                        .contains("one line"));
    }

    @Test
    void anAddressWithACredentialInItIsRefusedWithoutShowingIt() {
        // A token written into the file by mistake must not then be printed by every start.
        final List<String> refused =
                faults(
                        "tracker:\n  kind: github\n  api: \"https://me:sekrit-value@forge.example/\"\n");

        assertThat(refused)
                .as("refused for the credential in it")
                .anySatisfy(fault -> assertThat(fault).contains("user or password"));
        assertThat(String.join("\n", refused))
                .as("the value is never repeated in a refusal")
                .doesNotContain("sekrit-value");
    }

    @Test
    void aSettingLongerThanAFileShouldHoldIsRefused() {
        assertThat(faults("tracker:\n  kind: github\n  project: " + "a".repeat(513) + "\n"))
                .as("a setting is at most 512 characters")
                .anySatisfy(
                        fault -> assertThat(fault).contains("'tracker.project'").contains("512"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://me%40example.org:sekrit-value@forge.example/",
                "https://me:sekrit-value@forge.example?x=1",
                "me:sekrit-value@forge.example:acme/repo"
            })
    void everyFormOfACredentialInAnAddressIsRefused(final String address) {
        assertThat(faults("tracker:\n  kind: github\n  api: \"" + address + "\"\n"))
                .as("refused, and the value is never repeated")
                .anySatisfy(fault -> assertThat(fault).contains("user or password"))
                .noneSatisfy(fault -> assertThat(fault).contains("sekrit-value"));
    }

    @Test
    void anAtSignAfterTheAddressIsNoCredential() {
        // A query may carry an address of its own; refusing it would refuse a working setting.
        final ProjectFile read =
                file(HEAD + "tracker:\n  kind: github\n  api: \"https://forge.example/?to=a@b\"\n");

        assertThat(read.tracker())
                .as("kept as written")
                .containsEntry("api", "https://forge.example/?to=a@b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracker:\n  kind: other\n"})
    void aRepositorysPoreloYmlSettingTheTrackerIsRefusedByItsName(final String repository) {
        // Both are signed with the project's file alone: a repository's own file, which a
        // developer signs, could otherwise move the floor down or point the planner elsewhere.
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(HEAD + PLANNED),
                                        "project.yml",
                                        SnippetFiles.beside(Path.of("project.yml")),
                                        List.of(
                                                new RepositorySettings.Layer(
                                                        "porelo.yml of shop", repository))))
                .as("refused, naming the key")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo.yml of shop, line 1: '")
                .hasMessageContaining("' is the project's to say");
    }
}
