package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MirrorTree}: a repository with no checkout is read from its mirror's default
 * branch, exported read-only into a fresh private directory that is gone after, and never from a
 * branch a task pushed.
 */
class MirrorTreeTest {

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

    /**
     * A bare mirror whose default branch is 'trunk', with a script, a nested file and a link, and a
     * task's branch holding a file of its own.
     */
    private Path mirror(Path root) throws Exception {
        final Path source = Files.createDirectories(root.resolve("source"));
        git(source, "init", "-q", "-b", "trunk", ".");
        Files.writeString(source.resolve("pom.xml"), "<project>reviewed</project>\n");
        Files.createDirectories(source.resolve("src/main"));
        Files.writeString(source.resolve("src/main/App.java"), "class App {}\n");
        Files.writeString(source.resolve("build.sh"), "#!/bin/sh\ntrue\n");
        Files.setPosixFilePermissions(
                source.resolve("build.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createSymbolicLink(source.resolve("link"), Path.of("/etc/passwd"));
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "reviewed");
        git(source, "checkout", "-q", "-b", "porelo/task");
        Files.writeString(source.resolve("pom.xml"), "<project>pushed-by-a-task</project>\n");
        Files.writeString(source.resolve("task-only.txt"), "pushed-by-a-task\n");
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "a task's own");
        git(source, "checkout", "-q", "trunk");
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "-q", "--mirror", source.toString(), mirror.toString());
        return mirror;
    }

    private static List<String> files(Path directory) throws IOException {
        try (Stream<Path> all = Files.walk(directory)) {
            return all.filter(path -> !path.equals(directory))
                    .map(path -> directory.relativize(path).toString())
                    .sorted()
                    .toList();
        }
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void exportsTheDefaultBranchReadOnlyIntoAPrivateDirectoryGoneAfter(@TempDir Path root)
            throws Exception {

        final Path mirror = mirror(root);
        final Path runtime = root.resolve("run/porelo");

        final Path exported;
        try (MirrorTree tree = MirrorTree.export(mirror, runtime).orElseThrow()) {
            exported = tree.directory();
            assertThat(exported).startsWith(runtime);
            assertThat(permissions(exported)).as("private to this account").isEqualTo("rwx------");
            assertThat(files(exported))
                    .as("the default branch's tree, without its link")
                    .containsExactly("build.sh", "pom.xml", "src", "src/main", "src/main/App.java");
            assertThat(Files.readString(exported.resolve("pom.xml")))
                    .isEqualTo("<project>reviewed</project>\n");
            assertThat(Files.readString(exported.resolve("src/main/App.java")))
                    .isEqualTo("class App {}\n");
            assertThat(permissions(exported.resolve("pom.xml"))).isEqualTo("r--------");
            assertThat(permissions(exported.resolve("build.sh")))
                    .as("executable, still read-only")
                    .isEqualTo("r-x------");
        }
        assertThat(exported).as("removed after use").doesNotExist();
    }

    @Test
    void neverExportsWhatATaskPushed(@TempDir Path root) throws Exception {

        final Path mirror = mirror(root);
        final Path runtime = root.resolve("run/porelo");

        try (MirrorTree tree = MirrorTree.export(mirror, runtime).orElseThrow()) {
            assertThat(tree.directory().resolve("task-only.txt")).doesNotExist();
            try (Stream<Path> all = Files.walk(tree.directory())) {
                for (final Path file : all.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).doesNotContain("pushed-by-a-task");
                }
            }
        }

        git(mirror, "symbolic-ref", "HEAD", "refs/heads/porelo/task");
        assertThat(MirrorTree.export(mirror, runtime))
                .as("a HEAD naming a task's branch is no default branch")
                .isEmpty();
    }

    @Test
    void isGoneAlsoWhenTheReaderFails(@TempDir Path root) throws Exception {

        final Path mirror = mirror(root);
        final Path runtime = root.resolve("run/porelo");
        final List<Path> seen = new ArrayList<>();

        assertThatThrownBy(
                        () -> {
                            try (MirrorTree tree =
                                    MirrorTree.export(mirror, runtime).orElseThrow()) {
                                seen.add(tree.directory());
                                throw new IllegalStateException("an adapter failed");
                            }
                        })
                .hasMessage("an adapter failed");
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).doesNotExist();
    }

    @Test
    void aMissingMirrorOrEmptyOneExportsNothing(@TempDir Path root) throws Exception {

        final Path runtime = root.resolve("run/porelo");
        assertThat(MirrorTree.export(root.resolve("absent.git"), runtime)).isEmpty();

        final Path empty = root.resolve("empty.git");
        git(root, "init", "-q", "--bare", empty.toString());
        assertThat(MirrorTree.export(empty, runtime)).isEmpty();
    }
}
