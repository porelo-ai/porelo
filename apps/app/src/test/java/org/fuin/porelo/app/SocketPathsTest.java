package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
}
