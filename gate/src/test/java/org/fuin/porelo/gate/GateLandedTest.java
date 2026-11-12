package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task started to read another task's work is shown that work once it landed, read only, and
 * nothing of any other task: a reader polled for half an hour for a branch the gate hid from it.
 */
class GateLandedTest {

    @TempDir Path dir;
    private final CommandRunner runner = new ProcessCommandRunner();
    private Path upstream;
    private Path work;
    private Path mirror;
    private String landed;
    private String held;

    @BeforeEach
    void oneTaskApprovedAndAnotherStillWaiting() {
        upstream = dir.resolve("upstream.git");
        work = dir.resolve("work");
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "--bare", "-q", "-b", "main", upstream.toString());
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        git(work, "commit", "--allow-empty", "-q", "-m", "base");
        git(work, "push", "-q", "origin", "main");
        gate().initialize();
        git(work, "commit", "--allow-empty", "-q", "-m", "the builder's reviewed work");
        landed = git(work, "rev-parse", "HEAD");
        waiting("foo-1", landed);
        gate().approve("foo-1", "porelo/foo-1", landed);
        git(work, "commit", "--allow-empty", "-q", "-m", "work still at the gate");
        held = git(work, "rev-parse", "HEAD");
        waiting("foo-10", held);
    }

    @Test
    void aReaderNamingTheBuilderIsShownItsLandedWorkUnderTheBranchItLandedOn() {
        assertThat(advertised("reader", List.of("foo-1")))
                .as("the reader lists the builder's work, as git ls-remote would")
                .containsEntry(GitGate.LANDED + "foo-1/porelo/foo-1", landed);
    }

    @Test
    void aTaskThatNamesNoBuilderSeesNothingOfItsWork() {
        assertThat(advertised("other", List.of()).keySet())
                .as("no task sees another's work unless it was started to read it")
                .noneMatch(ref -> ref.startsWith(GitGate.LANDED))
                .noneMatch(ref -> ref.startsWith("refs/heads/porelo/"));
    }

    @Test
    void workStillWaitingAtTheGateIsShownToNoReader() {
        assertThat(advertised("reader", List.of("foo-1", "foo-10")).keySet())
                .as("unreviewed work is never handed on, even to a reader that names its task")
                .noneMatch(ref -> ref.startsWith(GitGate.LANDED + "foo-10/"))
                .noneMatch(ref -> ref.equals(GitGate.INCOMING + "foo-10"));
    }

    @Test
    void namingOneTaskRevealsNoTaskWhoseNameItBegins() {
        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString())
                .recordLanded("foo-10", "porelo/foo-10", landed);

        assertThat(advertised("reader", List.of("foo-1")).keySet())
                .as("git matches at a component boundary: foo-1 is not foo-10")
                .noneMatch(ref -> ref.startsWith(GitGate.LANDED + "foo-10/"));
    }

    @Test
    void aNameThatIsNoTasksNameRevealsNothing() {
        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString())
                .recordLanded("foo-10", "porelo/foo-10", landed);

        assertThat(advertised("reader", List.of("", "foo-1/../foo-10", "foo-10/porelo")).keySet())
                .as("only a task's plain name unhides, and only its own work")
                .noneMatch(ref -> ref.startsWith(GitGate.LANDED));
    }

    @Test
    void theGateSaysWhereATasksWorkLanded() {
        assertThat(gate().landed("foo-1"))
                .as("the branch and the commit, for the task's row")
                .containsExactly(new GitGate.Landed("porelo/foo-1", landed));
        assertThat(gate().landed("foo-10")).as("nothing while it waits").isEmpty();
    }

    @Test
    void aFleetViewReadsWhereWorkLandedFromTheMirrorsFilesLooseOrPacked() {
        assertThat(GitGate.landedIn(mirror, "foo-1"))
                .as("loose, as the gate wrote it")
                .containsExactly(new GitGate.Landed("porelo/foo-1", landed));
        git(mirror, "pack-refs", "--all");
        assertThat(GitGate.landedIn(mirror, "foo-1"))
                .as("packed, as git gc leaves it")
                .containsExactly(new GitGate.Landed("porelo/foo-1", landed));
        assertThat(GitGate.landedIn(mirror, "foo-10")).as("nothing while it waits").isEmpty();
        assertThat(GitGate.landedIn(mirror, "../foo-1")).as("no path out").isEmpty();
        assertThat(GitGate.landedIn(dir.resolve("none.git"), "foo-1")).as("no mirror").isEmpty();
    }

    @Test
    void aTaskForgottenLeavesNoLandedWorkForTheNextOfItsName() {
        gate().forget("foo-1");

        assertThat(GitGate.landedIn(mirror, "foo-1"))
                .as("a new task of the name has landed nothing yet")
                .isEmpty();
        assertThat(advertised("reader", List.of("foo-1")).keySet())
                .noneMatch(ref -> ref.startsWith(GitGate.LANDED));
    }

    @Test
    void aReaderCannotWriteToTheWorkItReads() {
        // receive-pack takes only the task's own incoming ref; the landed ref is hidden from it.
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(
                GitHttpServer.hidingOthers(
                        GitGate.INCOMING + "reader",
                        "receive-pack",
                        "--stateless-rpc",
                        "--advertise-refs",
                        mirror.toString()));
        assertThat(runner.runOrFail(Command.of(command)).standardOutput())
                .as("a ref not advertised for push is no ref the reader can update")
                .doesNotContain(GitGate.LANDED);
    }

    private Map<String, String> advertised(String task, List<String> reads) {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(
                GitHttpServer.hidingOthers(
                        GitGate.INCOMING + task,
                        reads,
                        "upload-pack",
                        "--stateless-rpc",
                        "--advertise-refs",
                        mirror.toString()));
        final Map<String, String> refs = new java.util.LinkedHashMap<>();
        for (final String line :
                runner.runOrFail(Command.of(command)).standardOutput().split("\n")) {
            // pkt-line: four hex digits of length, then "<commit> <ref>", capabilities after a NUL.
            if (line.length() > 4 + 41) {
                final String body = line.substring(4).split("\0")[0].strip();
                final int space = body.indexOf(' ');
                if (space == 40) {
                    refs.put(body.substring(space + 1), body.substring(0, space));
                }
            }
        }
        return refs;
    }

    private GitGate gate() {
        return new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());
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
