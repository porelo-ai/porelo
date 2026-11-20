package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileException;
import org.fuin.porelo.core.project.file.ProjectFileReader;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.core.project.file.RepositoryFileReader;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@code velocity.seed}, the starting minutes per repository and size the person's
 * planner keeps in the project's file: a file that holds it must read and start as one without it,
 * keep it through a rewrite, and let no repository's own file set it.
 */
class VelocitySeedTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            repositories:
              shop:
                issues: ["B"]
              tools:
                issues: ["T"]
            """;

    private static final String SEEDED =
            """
            velocity:
              seed:
                shop: { XS: 30, S: 120, M: 480, L: 1440, XL: 2880 }
                tools: { M: 300, S: 90 }
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
    void aFileWithASeedReadsAsAProject() {
        // Every start reads the project's file: one the planner wrote the seed into must not stop
        // them.
        final Project project = ProjectReader.read(new StringReader(HEAD + SEEDED), "project.yml");

        assertThat(project.name()).as("the project reads as before").isEqualTo("p");
    }

    @Test
    void theSeedIsReadPerRepositoryAndSizeInTheOrderWritten() {
        final ProjectFile read = file(HEAD + SEEDED);

        assertThat(Objects.requireNonNull(read.velocity(), "velocity read").seed())
                .as("each repository's minutes, as written")
                .containsExactly(
                        Map.entry(
                                "shop",
                                Map.of("XS", 30, "S", 120, "M", 480, "L", 1440, "XL", 2880)),
                        Map.entry("tools", Map.of("M", 300, "S", 90)));
        assertThat(read.velocity().seed().get("tools").keySet())
                .as("the sizes in the order written")
                .containsExactly("M", "S");
    }

    @Test
    void aFileWithoutOneHasNoVelocity() {
        assertThat(file(HEAD).velocity()).as("absent is not an empty seed").isNull();
    }

    @Test
    void aRewriteKeepsTheSeedAsRead() {
        // The window writes the file back; what the planner kept must survive it unchanged.
        final ProjectFile read = file(HEAD + SEEDED);
        final ProjectFile again = file(ProjectFileWriter.write(read));

        assertThat(again.velocity()).as("the seed after a rewrite").isEqualTo(read.velocity());
        // A rewrite that reordered it would change the signed file's text for nothing.
        assertThat(Objects.requireNonNull(again.velocity(), "velocity rewritten").seed().keySet())
                .as("the repositories in the order written")
                .containsExactly("shop", "tools");
        assertThat(again.velocity().seed().get("tools").keySet())
                .as("the sizes in the order written")
                .containsExactly("M", "S");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "velocity:\\n  seed:\\n    shoe: { M: 60 }\\n|velocity.seed.shoe|not one of",
                "velocity:\\n  seed:\\n    shop: { XXL: 60 }\\n|velocity.seed.shop.XXL|XS, S, M, L",
                "velocity:\\n  seed:\\n    shop: { m: 60 }\\n|velocity.seed.shop.m|XS, S, M, L",
                "velocity:\\n  seed:\\n    shop: { M: 0 }\\n|velocity.seed.shop.M|1 to 100000",
                "velocity:\\n  seed:\\n    shop: { M: 100001 }\\n|velocity.seed.shop.M|1 to 100000",
                "velocity:\\n  seed:\\n    shop: { M: -5 }\\n|velocity.seed.shop.M|1 to 100000",
                "velocity:\\n  seed:\\n    shop: { M: 1.5 }\\n|velocity.seed.shop.M|whole number",
                "velocity:\\n  seed:\\n    shop: { M: ten }\\n|velocity.seed.shop.M|whole number",
                "velocity:\\n  seed:\\n    shop: 60\\n|velocity.seed.shop|mapping",
                "velocity:\\n  start:\\n    shop: { M: 60 }\\n|velocity.start|",
                "velocity:\\n|velocity|needs 'seed'",
                "velocity: 60\\n|velocity|mapping",
                "velocity:\\n  seed:\\n    shop: { M: 120, L: ~ }\\n|velocity.seed.shop.L|1 to 100000",
                "velocity:\\n  seed:\\n    shop:\\n      M:\\n|velocity.seed.shop.M|1 to 100000",
                "velocity:\\n  seed:\\n    shop: { M: 120, XXL: ~ }\\n|velocity.seed.shop.XXL|XS, S, M, L",
                "velocity:\\n  seed:\\n    shop: {}\\n|velocity.seed.shop|needs the minutes",
                "velocity: {}\\n|velocity|needs 'seed'",
                "velocity:\\n  seed:\\n|velocity|needs 'seed'"
            })
    void aMalformedSeedIsRefusedByItsPath(
            final String section, final String path, final String what) {
        assertThat(faults(section.replace("\\n", "\n")))
                .as("refused, naming where")
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .contains("'" + path + "'")
                                        .contains(what == null ? "" : what));
    }

    @Test
    void aRepositorysPoreloYmlSettingTheSeedIsRefusedByItsName() {
        // The seed is signed with the project's file alone, as the tracker is.
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(HEAD + SEEDED),
                                        "project.yml",
                                        SnippetFiles.beside(Path.of("project.yml")),
                                        List.of(
                                                new RepositorySettings.Layer(
                                                        "porelo.yml of shop",
                                                        "velocity:\n  seed:\n    shop: { M: 1 }\n"))))
                .as("refused, naming the key")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo.yml of shop, line 1: 'velocity'")
                .hasMessageContaining("' is the project's to say");
    }

    @Test
    void aDefaultProjectsRepositoryFileSettingTheSeedIsRefusedByItsName() {
        // A repository's own file beside the default project's settings is read on its own path.
        assertThatThrownBy(
                        () ->
                                RepositoryFileReader.readBesideSettings(
                                        new StringReader(
                                                "image:\n  from: \"ubuntu:24.04\"\n"
                                                        + "velocity:\n  seed:\n    shop: { M: 1 }\n"),
                                        "porelo.yml of shop",
                                        null))
                .as("refused, naming the key")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining("'velocity'")
                .hasMessageContaining("cannot be set here");
    }
}
