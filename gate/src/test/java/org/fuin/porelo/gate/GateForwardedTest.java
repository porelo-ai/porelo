package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task may replace its last approval, but never work another writer put on that branch. */
class GateForwardedTest {

    @TempDir Path dir;
    private final CommandRunner runner = new ProcessCommandRunner();
    private Path upstream;
    private Path work;
    private Path mirror;
    private String first;
    private String amended;

    @BeforeEach
    void anApprovalFollowedByAnAmendment() {
        upstream = dir.resolve("upstream.git");
        work = dir.resolve("work");
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "--bare", "-q", "-b", "main", upstream.toString());
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        git(work, "commit", "--allow-empty", "-q", "-m", "base");
        git(work, "push", "-q", "origin", "main");
        gate(runner).initialize();
        git(work, "commit", "--allow-empty", "-q", "-m", "reviewed work");
        first = git(work, "rev-parse", "HEAD");
        waiting("fix", first);
        gate(runner).approve("fix", "porelo/fix", first);
        git(work, "commit", "--amend", "--allow-empty", "-q", "-m", "amended work");
        amended = git(work, "rev-parse", "HEAD");
        waiting("fix", amended);
    }

    // Rejecting every amendment forces duplicate branches and reviews for the same task.
    @Test
    void aNewGateInstanceCanReplaceWhatItLastForwardedForThisTask() {
        gate(runner).approve("fix", "porelo/fix", amended);

        assertThat(git(upstream, "rev-parse", "porelo/fix"))
                .as("the amended approval replaces this task's prior push")
                .isEqualTo(amended);
        assertThat(gate(runner).pending()).as("the completed approval no longer waits").isEmpty();
    }

    // Off a host the branch may be main: an amendment forced over it rewrites shared history.
    @Test
    void offAHostAnAmendedApprovalStillRefusesTheBranchItsFirstApprovalWentTo() {
        final GitGate offHost =
                new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());

        assertThatThrownBy(() -> offHost.approve("fix", "porelo/fix", amended))
                .isInstanceOf(GateException.BranchExists.class);
        assertThat(git(upstream, "rev-parse", "porelo/fix")).isEqualTo(first);
    }

    // Ownership refs must not expose another task's approved work through a second name.
    @Test
    void ownershipRecordsAreNeverAdvertisedToTasks() {
        for (final String ref : new String[] {null, GitGate.INCOMING + "other"}) {
            final List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(
                    GitHttpServer.hidingOthers(
                            ref,
                            "upload-pack",
                            "--stateless-rpc",
                            "--advertise-refs",
                            mirror.toString()));

            assertThat(runner.runOrFail(Command.of(command)).standardOutput())
                    .as("ownership records stay private, with or without a task-specific endpoint")
                    .doesNotContain(GitGate.FORWARDED);
        }
    }

    // A lease must protect a writer who arrives between the head check and the actual push.
    @Test
    void aConcurrentWriterCannotBeOverwrittenByTheAmendedApproval() {
        final String other = git(work, "rev-parse", "HEAD~1");
        final AtomicBoolean raced = new AtomicBoolean();
        final CommandRunner racing =
                command -> {
                    if (command.arguments().contains("push")) {
                        raced.set(true);
                        git(upstream, "update-ref", "refs/heads/porelo/fix", other, first);
                    }
                    return runner.run(command);
                };

        assertThatThrownBy(() -> gate(racing).approve("fix", "porelo/fix", amended))
                .as("the stale lease refuses the push")
                .isInstanceOf(GateException.class);
        assertThat(raced).as("the writer arrives after the head check").isTrue();
        assertThat(git(upstream, "rev-parse", "porelo/fix"))
                .as("the concurrent writer's head survives")
                .isEqualTo(other);
        assertThat(gate(runner).pending())
                .as("the unforwarded amendment still waits")
                .contains("fix");
    }

    // A task's record is no permission for another task to force the same branch.
    @Test
    void anotherTaskCannotReplaceTheFirstTasksApproval() {
        waiting("other", amended);

        assertThatThrownBy(() -> gate(runner).approve("other", "porelo/fix", amended))
                .as("only the authoring task may replace its approval")
                .isInstanceOf(GateException.BranchExists.class);
        assertThat(git(upstream, "rev-parse", "porelo/fix"))
                .as("the first task's work survives")
                .isEqualTo(first);
    }

    // A copied head on another branch is not evidence that this gate forwarded it there.
    @Test
    void aForwardToOneBranchDoesNotAuthorizeReplacingAnotherBranch() {
        git(upstream, "update-ref", "refs/heads/review/other", first);

        assertThatThrownBy(() -> gate(runner).approve("fix", "review/other", amended))
                .as("ownership belongs to the branch that was actually forwarded")
                .isInstanceOf(GateException.BranchExists.class);
        assertThat(git(upstream, "rev-parse", "review/other"))
                .as("the unrecognized branch survives")
                .isEqualTo(first);
    }

    // An explicit upstream must not inherit permission to rewrite work on a different forge.
    @Test
    void aForwardToOneUpstreamDoesNotAuthorizeReplacingAnotherUpstream() {
        final Path elsewhere = dir.resolve("elsewhere.git");
        git(dir, "init", "--bare", "-q", "-b", "main", elsewhere.toString());
        git(work, "push", "-q", elsewhere.toString(), first + ":refs/heads/porelo/fix");
        final GitGate other =
                new GitGate(runner, mirror, GateMode.GATEKEEPING, elsewhere.toString());

        assertThatThrownBy(() -> other.approve("fix", "porelo/fix", amended))
                .as("ownership belongs to the upstream that received the approval")
                .isInstanceOf(GateException.BranchExists.class);
        assertThat(git(elsewhere, "rev-parse", "porelo/fix"))
                .as("the other upstream survives")
                .isEqualTo(first);
    }

    // The recorded commit must match the live head; a matching task name alone loses others' work.
    @Test
    void aHeadChangedSinceTheLastApprovalStillRefuses() {
        final String other = git(work, "rev-parse", "HEAD~1");
        git(upstream, "update-ref", "refs/heads/porelo/fix", other, first);
        // A divergent head, rather than an ancestor that an ordinary push may grow from.
        git(work, "checkout", "-q", "--detach", other);
        git(work, "commit", "--allow-empty", "-q", "-m", "somebody else's work");
        final String held = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", "origin", "HEAD:refs/heads/porelo/fix");

        assertThatThrownBy(() -> gate(runner).approve("fix", "porelo/fix", amended))
                .as("unrecognized work still needs another branch")
                .isInstanceOf(GateException.BranchExists.class);
        assertThat(git(upstream, "rev-parse", "porelo/fix"))
                .as("the unrecognized head is unchanged")
                .isEqualTo(held);
    }

    /** A gate on a host, whose approvals open a pull request from the task's own branch. */
    private GitGate gate(CommandRunner commands) {
        return new GitGate(commands, mirror, GateMode.GATEKEEPING, upstream.toString())
                .pullRequests((task, branch, subject) -> "https://forge.example.org/pulls/1");
    }

    private void waiting(String task, String commit) {
        git(
                work,
                "push",
                "-q",
                "--force",
                mirror.toString(),
                commit + ":refs/porelo/incoming/" + task);
    }

    private String git(Path in, String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }
}
