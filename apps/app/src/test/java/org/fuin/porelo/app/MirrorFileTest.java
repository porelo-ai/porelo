package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ProjectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MirrorFile}: a repository's image is read from reviewed work only, never from a
 * branch a task pushed, since the image is built as root outside the task's firewall.
 */
class MirrorFileTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private void git(Path directory, String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "init.defaultBranch=main"));
        all.addAll(List.of(arguments));
        runner.runOrFail(
                new Command(
                        all,
                        directory,
                        Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"),
                        null));
    }

    /** A bare mirror whose default branch is 'trunk', holding porelo.yml, and a task's branch. */
    private Path mirror(Path root) throws Exception {
        final Path source = Files.createDirectories(root.resolve("source"));
        git(source, "init", "-q", "-b", "trunk", ".");
        Files.writeString(source.resolve("porelo.yml"), "image:\n  base_image: reviewed\n");
        Files.writeString(source.resolve("image.sh"), "RUN true\n");
        Files.createSymbolicLink(source.resolve("link.sh"), Path.of("/etc/passwd"));
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "reviewed");
        git(source, "checkout", "-q", "-b", "porelo/task");
        Files.writeString(source.resolve("porelo.yml"), "image:\n  base_image: pushed-by-a-task\n");
        git(source, "commit", "-q", "-am", "a task's own");
        git(source, "checkout", "-q", "trunk");
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "-q", "--mirror", source.toString(), mirror.toString());
        return mirror;
    }

    @Test
    void readsTheDefaultBranchEvenWhenItIsNotMain(@TempDir Path root) throws Exception {

        final Path mirror = mirror(root);

        assertThat(MirrorFile.defaultBranch(runner, mirror))
                .as("the reviewed default branch is reported as found")
                .isEqualTo(new MirrorFile.Found("refs/heads/trunk"));
        assertThat(MirrorFile.read(runner, mirror, "porelo.yml"))
                .as("reviewed work, not what a task pushed")
                .contains("image:\n  base_image: reviewed\n");
    }

    @Test
    void neverReadsABranchATaskPushed(@TempDir Path root) throws Exception {

        // A mirror whose HEAD points at a task's branch would build the image the task wrote.
        final Path mirror = mirror(root);
        git(mirror, "symbolic-ref", "HEAD", "refs/heads/porelo/task");

        assertThat(MirrorFile.read(runner, mirror, "porelo.yml")).isEmpty();
    }

    @Test
    void aMissingFileOrMirrorIsNoFile(@TempDir Path root) throws Exception {

        final Path mirror = mirror(root);

        assertThat(MirrorFile.read(runner, mirror, "absent.yml")).isEmpty();
        assertThat(MirrorFile.read(runner, root.resolve("no-mirror.git"), "porelo.yml")).isEmpty();
    }

    @Test
    void aSnippetIsAPlainFileOfTheRepositoryAndNothingElse(@TempDir Path root) throws Exception {

        // A link would hand the image build whatever it names; a path with '..' leaves the tree.
        final Path mirror = mirror(root);
        final var snippets = MirrorFile.snippets(runner, mirror, "refs/heads/trunk");

        assertThat(snippets.read("image.sh", "porelo.yml")).isEqualTo("RUN true\n");
        assertThatThrownBy(() -> snippets.read("link.sh", "porelo.yml"))
                .as("a link")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("not a plain file");
        assertThatThrownBy(() -> snippets.read("../outside", "porelo.yml"))
                .as("a path leaving the repository")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("must name a file of the repository");
    }
}
