package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The way to a test's sockets fits the Unix limit wherever the checkout lies. */
class SocketPathsTest {

    @Test
    void aTemporaryDirectoryFarFromADeepCheckoutIsTakenAbsolute() {
        // A git worktree twelve directories down climbs twelve times to reach /tmp: 36 more bytes,
        // which put an agent's socket past the 108 a Unix socket's path may have.
        final Path deep =
                Path.of("/home/someone/git/porelo/.claude/worktrees/agent-0123456789/apps/app");
        final Path run = Path.of("/tmp/junit-14289437515116103606/run");

        assertThat(SocketPaths.shortest(deep, run)).isEqualTo(run);
    }

    @Test
    void aDirectoryInsideTheCheckoutIsTakenRelative() {
        final Path here =
                Path.of("/home/someone/a/very/long/way/to/the/checkout/of/porelo/apps/app");

        assertThat(SocketPaths.shortest(here, here.resolve("target/run")))
                .isEqualTo(Path.of("target/run"));
    }

    @Test
    void theShorterWayIsCountedInTheBytesASocketPathIsLimitedBy() {
        // The limit is 108 bytes of UTF-8, not 108 characters: an umlaut is two.
        // "/\u00f6\u00f6\u00f6/x" is 6
        // characters but 9 bytes, "../../x" 7 of each.
        final Path here = Path.of("/\u00f6\u00f6\u00f6/b/c");

        assertThat(SocketPaths.shortest(here, Path.of("/\u00f6\u00f6\u00f6/x")))
                .isEqualTo(Path.of("../../x"));
    }

    @Test
    void aWorkingDirectoryReachedThroughALinkClimbsFromWhereItReallyIs(@TempDir final Path dir)
            throws java.io.IOException {
        // The kernel takes ".." from where the directory really is, not from the link's name.
        final Path real =
                java.nio.file.Files.createDirectories(dir.resolve("deep/down/real/checkout"));
        final Path link = java.nio.file.Files.createSymbolicLink(dir.resolve("short"), real);
        final Path sockets = java.nio.file.Files.createDirectories(dir.resolve("run"));

        final Path way = SocketPaths.shortest(link, sockets);

        assertThat(real.resolve(way).normalize()).isEqualTo(sockets);
    }
}
