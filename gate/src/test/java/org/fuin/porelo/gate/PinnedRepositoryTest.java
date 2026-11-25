package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reviewer's repository is built fresh from the pinned head and base alone, with git stood in: no
 * tags, no shared objects, {@code HEAD} at the pinned ref.
 */
class PinnedRepositoryTest {

    private static final String HEAD = "a".repeat(40);

    private static final String BASE = "b".repeat(40);

    @TempDir Path dir;

    private final List<List<String>> asked = new ArrayList<>();

    private int failing = -1;

    private final CommandRunner git =
            command -> {
                asked.add(command.arguments());
                return new CommandResult(command, asked.size() - 1 == failing ? 128 : 0, "", "");
            };

    @Test
    void itFetchesOnlyTheHeadAndTheBaseIntoAFreshBareRepositoryWithoutTags() throws IOException {
        final Path into = dir.resolve("pinned.git");
        final Path mirror = dir.resolve("mirror.git");
        PinnedRepository.build(git, mirror, into, "rev-1", HEAD, BASE);
        assertThat(asked.get(0))
                .as("how the repository is made")
                .containsExactly("git", "init", "--bare", "-q", into.toString());
        assertThat(asked.get(1))
                .as("what it is given")
                .contains("--no-tags")
                .doesNotContain("--shared", "--reference", "--tags")
                .endsWith(
                        "--",
                        mirror.toString(),
                        HEAD + ":" + GitGate.PINNED + "rev-1",
                        BASE + ":" + PinnedRepository.BASE + "rev-1");
        assertThat(asked.get(2))
                .as("where its HEAD points")
                .containsExactly(
                        "git",
                        "-C",
                        into.toString(),
                        "symbolic-ref",
                        "HEAD",
                        GitGate.PINNED + "rev-1");
    }

    @Test
    void withoutABaseOnlyTheHeadIsFetched() throws IOException {
        PinnedRepository.build(
                git, dir.resolve("mirror.git"), dir.resolve("pinned.git"), "rev-1", HEAD, null);
        assertThat(asked.get(1).getLast())
                .as("the last thing fetched")
                .isEqualTo(HEAD + ":" + GitGate.PINNED + "rev-1");
    }

    @Test
    void aDirectoryThatIsThereAlreadyIsNeverBuiltIn() throws IOException {
        final Path into = Files.createDirectory(dir.resolve("pinned.git"));
        assertThatThrownBy(
                        () ->
                                PinnedRepository.build(
                                        git, dir.resolve("mirror.git"), into, "rev-1", HEAD, BASE))
                .as("a build into a directory left behind")
                .isInstanceOf(IOException.class);
        assertThat(asked).as("what git was asked").isEmpty();
    }

    @Test
    void aFetchGitRefusesFailsTheBuild() {
        failing = 1;
        assertThatThrownBy(
                        () ->
                                PinnedRepository.build(
                                        git,
                                        dir.resolve("mirror.git"),
                                        dir.resolve("pinned.git"),
                                        "rev-1",
                                        HEAD,
                                        BASE))
                .as("a build whose fetch git refused")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aReplacementMovesTheNewRepositoryIntoPlaceAndKeepingItRemovesTheEarlierOne()
            throws IOException {
        final Path served = earlier();
        final PinnedRepository.Replacement replaced =
                PinnedRepository.replace(
                        git, dir.resolve("mirror.git"), served, "rev-1", HEAD, BASE);
        assertThat(served.resolve("earlier")).as("the earlier repository's mark").doesNotExist();
        assertThat(served).as("the served repository").isDirectory();
        replaced.keep();
        assertThat(replaced.earlier()).as("the earlier repository, once kept").doesNotExist();
    }

    @Test
    void anUndoneReplacementPutsTheEarlierRepositoryBack() throws IOException {
        final Path served = earlier();
        PinnedRepository.replace(git, dir.resolve("mirror.git"), served, "rev-1", HEAD, BASE)
                .undo();
        assertThat(served.resolve("earlier"))
                .as("the earlier repository's mark, once undone")
                .exists();
    }

    @Test
    void aReplacementWhoseBuildFailsLeavesTheServedRepositoryAsItWas() throws IOException {
        final Path served = earlier();
        failing = 1;
        assertThatThrownBy(
                        () ->
                                PinnedRepository.replace(
                                        git,
                                        dir.resolve("mirror.git"),
                                        served,
                                        "rev-1",
                                        HEAD,
                                        BASE))
                .as("a replacement whose fetch git refused")
                .isInstanceOf(IOException.class);
        assertThat(served.resolve("earlier")).as("the served repository's mark").exists();
        assertThat(dir.resolve("pinned.git.new")).as("the half-built repository").doesNotExist();
    }

    private Path earlier() throws IOException {
        final Path served = Files.createDirectory(dir.resolve("pinned.git"));
        Files.writeString(served.resolve("earlier"), "");
        return served;
    }
}
