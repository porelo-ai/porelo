package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mirror seeded from the person's checkout, in a project with no upstream, follows the checkout
 * when a task starts from it: fast-forward only, never back over what the mirror holds beyond it.
 */
class GateFollowsCheckoutTest {

    @TempDir Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private Path checkout;

    private Path mirror;

    private GitGate gate;

    @BeforeEach
    void seeded() {
        checkout = dir.resolve("checkout");
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "-q", "-b", "main", checkout.toString());
        git(checkout, "config", "user.email", "t@example.com");
        git(checkout, "config", "user.name", "T");
        git(checkout, "commit", "-q", "--allow-empty", "-m", "initial");
        gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, null, checkout.toString());
        gate.initialize();
    }

    private String head(final Path repository, final String ref) {
        return git(repository, "rev-parse", ref).strip();
    }

    @Test
    void aCommitMadeInTheCheckoutSinceTheSeedReachesTheMirror() {
        git(checkout, "commit", "-q", "--allow-empty", "-m", "a key");

        final String said = gate.followCheckout(checkout, "main");

        assertThat(said).isEmpty();
        assertThat(head(mirror, "refs/heads/main")).isEqualTo(head(checkout, "HEAD"));
    }

    @Test
    void aBranchOnlyTheCheckoutHoldsReachesTheMirror() {
        git(checkout, "checkout", "-q", "-b", "topic");
        git(checkout, "commit", "-q", "--allow-empty", "-m", "topic work");

        assertThat(gate.followCheckout(checkout, "topic")).isEmpty();
        assertThat(head(mirror, "refs/heads/topic")).isEqualTo(head(checkout, "HEAD"));
    }

    @Test
    void aMirrorAheadOfTheCheckoutIsNeverMovedBackAndTheStartIsTold() {
        git(checkout, "commit", "-q", "--allow-empty", "-m", "approved");
        assertThat(gate.followCheckout(checkout, "main")).isEmpty();
        final String ahead = head(mirror, "refs/heads/main");
        git(checkout, "reset", "-q", "--hard", "HEAD~1");

        final String said = gate.followCheckout(checkout, "main");

        assertThat(head(mirror, "refs/heads/main")).isEqualTo(ahead);
        assertThat(said)
                .contains(ahead.substring(0, 12))
                .contains(head(checkout, "HEAD").substring(0, 12))
                .contains("starts from the mirror's");
    }

    @Test
    void aMirrorThatHasDivergedIsKeptAndTheStartIsTold() {
        git(checkout, "commit", "-q", "--allow-empty", "-m", "one");
        assertThat(gate.followCheckout(checkout, "main")).isEmpty();
        final String mirrored = head(mirror, "refs/heads/main");
        git(checkout, "reset", "-q", "--hard", "HEAD~1");
        git(checkout, "commit", "-q", "--allow-empty", "-m", "other");

        final String said = gate.followCheckout(checkout, "main");

        assertThat(head(mirror, "refs/heads/main")).isEqualTo(mirrored);
        assertThat(said).contains(mirrored.substring(0, 12)).contains("starts from the mirror's");
    }

    @Test
    void aTaskBranchCheckedOutHereNeverReachesTheMirror() {
        // The gate keeps a task's branch out of the mirror every task fetches from.
        git(checkout, "checkout", "-q", "-b", "porelo/fix-1");
        git(checkout, "commit", "-q", "--allow-empty", "-m", "a task's work");

        final String said = gate.followCheckout(checkout, "porelo/fix-1");

        assertThat(gate.resolves("refs/heads/porelo/fix-1")).isFalse();
        assertThat(said).contains("porelo/fix-1").contains("--from");
    }

    @Test
    void aFetchThatFailsForAnotherReasonSaysThatReason() {
        git(checkout, "commit", "-q", "--allow-empty", "-m", "later");
        // A repository that holds no such branch: the fetch fails, and not for a divergence.
        final Path broken = dir.resolve("not-a-repository");
        git(dir, "init", "-q", "-b", "main", broken.toString());

        final String said = gate.followCheckout(broken, "main");

        assertThat(said)
                .doesNotContain("is not your checkout's")
                .contains("could not be brought")
                .as("git's own reason, not a guess")
                .contains("refs/heads/main");
    }

    @Test
    void aGateThatForwardsToAnUpstreamLeavesItToTheRefresh() {
        final GitGate forwarding =
                new GitGate(
                        runner,
                        dir.resolve("other.git"),
                        GateMode.GATEKEEPING,
                        checkout.toString(),
                        checkout.toString());
        forwarding.initialize();
        git(checkout, "commit", "-q", "--allow-empty", "-m", "later");

        assertThat(forwarding.followCheckout(checkout, "main")).isEmpty();
        assertThat(head(dir.resolve("other.git"), "refs/heads/main"))
                .isNotEqualTo(head(checkout, "HEAD"));
    }

    private String git(final Path in, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput();
    }
}
