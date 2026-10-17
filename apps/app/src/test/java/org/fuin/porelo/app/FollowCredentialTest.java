package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link FollowCredential}: what git is given to reach a destination. */
class FollowCredentialTest {

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_STATE_HOME".equals(name)
                                        ? dir.resolve("state").toString()
                                        : null,
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void twoLendsForOneLabelNeverShareASocket(@TempDir Path dir) {

        // Each lend removed whatever lay at git-<label>.sock before it served its key there, so a
        // follow by the daemon
        // and one at the command line at the same moment took each other's socket away, and one
        // fetch failed.
        final PoreloContext context = context(dir);

        final Path first =
                FollowCredential.socketFor(
                        context, "a-project-with-a-long-name-and-its-repository");
        final Path second =
                FollowCredential.socketFor(
                        context, "a-project-with-a-long-name-and-its-repository");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.getParent()).isEqualTo(second.getParent());
        assertThat(first.toString().length()).as("a unix socket's path").isLessThan(108);
    }

    @Test
    void anSshDestinationWithNothingLentStillGetsTheMachinesHostKeyPolicy(@TempDir Path dir) {

        // The developer's own agent key reaches the origin, so nothing is lent - and ssh, given no
        // policy, checked the
        // host against the user's own known hosts and refused one this machine had vouched for.
        try (FollowCredential lent =
                FollowCredential.open(
                        context(dir), "ssh://dev@forge.example/team/repo.git", "test")) {
            assertThat(lent.environment().get("GIT_SSH_COMMAND"))
                    .contains("StrictHostKeyChecking=yes")
                    .contains(
                            "UserKnownHostsFile='" + dir.resolve("state/porelo/known_hosts") + "'");
            // A policy is not a credential: what failed is still answered as nothing stored for it.
            assertThat(lent.lent()).isFalse();
        }
    }

    @Test
    void aDestinationThatIsNotSshIsGivenNoSshPolicy(@TempDir Path dir) {

        try (FollowCredential lent =
                FollowCredential.open(context(dir), "/srv/git/repo.git", "test")) {
            assertThat(lent.environment()).doesNotContainKey("GIT_SSH_COMMAND");
        }
    }
}
