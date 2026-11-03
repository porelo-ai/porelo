package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where one project's file comes from, when a machine follows its repository.
 *
 * <p><strong>This is the question that had three answers.</strong> The directory somebody stood in,
 * the one a create call chose, and the clone the machine verified - and until this existed, the
 * verified one was the only one nothing read. Everything following promises rests on the answer
 * being the checked file.
 */
class ProjectSourceTest {

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project this machine follows, with a file in its clone. */
    private void placed(
            final PoreloContext context,
            final String name,
            final String commit,
            final String content)
            throws IOException {
        PlacedFixture.record(context, name);
        final Path clone = context.paths().projects().organizationClone(name);
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), content);
    }

    /** A project a task once ran for, recorded where its file was. */
    private Path recorded(final PoreloContext context, final Path dir, final String name)
            throws IOException {
        final Path file = dir.resolve(name + "-local.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember(name, file);
        return file;
    }

    /** A file written in a project's place, as a person registers one by hand. */
    private Path written(final PoreloContext context, final String place, final String named)
            throws IOException {
        final Path file = context.paths().projects().defaultProjectFile(place);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "project:\n  name: \"" + named + "\"\n");
        return file;
    }

    /** Written by hand it is the project's file, and nobody ever checked it. */
    @Test
    void aFileWrittenInItsPlaceAnswersAndIsNotVerified(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path file = written(context, "off", "off");

        final ProjectSource.Found found = ProjectSource.resolve(context, "off");

        assertThat(found.file()).isEqualTo(file);
        assertThat(ProjectSource.names(context)).contains("off");
    }

    /** It wins over a file a task once ran from, since a person just wrote it. */
    @Test
    void aFileWrittenInItsPlaceWinsOverARecordedOne(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        recorded(context, dir, "off");
        final Path file = written(context, "off", "off");

        assertThat(ProjectSource.resolve(context, "off").file()).isEqualTo(file);
    }

    /** A name is a directory here: one that is no name must not walk out of the place. */
    @Test
    void aNameThatIsNoNameIsNotRead(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path outside = dir.resolve("config/porelo/outside/project.yml");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "project:\n  name: \"outside\"\n");

        assertThat(ProjectSource.resolve(context, "../outside").file()).isNull();
        written(context, "Not_A_Name", "Not_A_Name");
        assertThat(ProjectSource.resolve(context, "Not_A_Name").file()).isNull();
        assertThat(ProjectSource.names(context)).doesNotContain("Not_A_Name");
    }

    /** Which project a task belongs to must not depend on which directory the file stands in. */
    @Test
    void aFileNamingAnotherProjectIsRefusedNamingBoth(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        written(context, "off", "other");

        assertThatThrownBy(() -> ProjectSource.require(context, "off"))
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class)
                .hasMessageContaining("'off'")
                .hasMessageContaining("'other'");
    }

    @Test
    void theVerifiedCloneWinsOverAFileSomebodyRecorded(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        recorded(context, dir, "acme");
        placed(context, "acme", "a1b2c3", "project:\n  name: \"acme\"\n");

        final ProjectSource.Found found = ProjectSource.resolve(context, "acme");

        // Not close: one was checked against a key pinned out of band, the other was wherever a
        // task last ran.
        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.ORGANIZATION);
        assertThat(found.file())
                .isEqualTo(
                        context.paths()
                                .projects()
                                .organizationClone("acme")
                                .resolve("project.yml"));
        assertThat(found.commit()).isEqualTo("c0");
    }

    @Test
    void aProjectNobodyFollowsStillResolvesToWhereItWasRecorded(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path file = recorded(context, dir, "solo");

        final ProjectSource.Found found = ProjectSource.resolve(context, "solo");

        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.RECORDED);
        assertThat(found.file()).isEqualTo(file);
        // Nothing checked it, and the answer says so rather than implying otherwise.
    }

    @Test
    void aPlacedProjectWithNoCloneResolvesToNothing(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path local = recorded(context, dir, "acme");
        // Placed, and its clone gone.
        PlacedFixture.record(context, "acme");

        final ProjectSource.Found found = ProjectSource.resolve(context, "acme");

        // The local file is NOT the fallback. Falling back would run exactly the thing the machine
        // declined to apply, which is worse than not starting - and it would make a refusal look
        // like it had no effect.
        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.UNKNOWN);
        assertThat(found.file()).isNull();
        assertThat(local).exists();
    }

    @Test
    void aNameThisMachineDoesNotHaveIsItsOwnAnswer(@TempDir final Path dir) {
        assertThat(ProjectSource.resolve(context(dir), "nowhere").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }

    @Test
    void theNamesItCanResolveArePlacedFirst(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        recorded(context, dir, "solo");
        placed(context, "acme", "a1b2c3", "project:\n  name: \"acme\"\n");

        // What a refusal prints. 'default' first, which every machine has; then followed, because
        // that is the list somebody should be choosing from.
        assertThat(ProjectSource.names(context))
                .containsExactly(DefaultProject.NAME, "acme", "solo");
    }

    /** A name that cannot be read must not stop a walk over all of them, nor be offered. */
    @Test
    void aRecordedFileThatIsGoneIsNotAmongTheNames(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        Files.delete(recorded(context, dir, "gone"));

        assertThat(ProjectSource.names(context)).doesNotContain("gone");
    }

    @Test
    void aRecordedFileThatIsGoneIsNotAnAnswer(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        Files.delete(recorded(context, dir, "solo"));

        // The registry remembers where a task last read one; the file can be deleted afterwards and
        // nothing here would know. Answering with a path that is not there would make the failure
        // happen somewhere further in.
        assertThat(ProjectSource.resolve(context, "solo").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }
}
