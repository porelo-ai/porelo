package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    void forgettingWhereATaskLandedKeepsItsWaitingWorkAndEveryOtherTasks() {
        // A new task of an earlier one's name: in a guarded project the earlier one's ref may hold
        // work nobody reviewed yet, and only where it landed is no longer the name's.
        waiting("foo-1", held);
        gate().recordLanded("foo-10", "porelo/foo-10", landed);

        gate().forgetLanded("foo-1");

        assertThat(GitGate.landedIn(mirror, "foo-1"))
                .as("the new task of the name has landed nothing")
                .isEmpty();
        assertThat(gate().resolves(GitGate.INCOMING + "foo-1"))
                .as("work waiting at the gate under the name is never lost")
                .isTrue();
        assertThat(GitGate.landedIn(mirror, "foo-10"))
                .as("foo-1 is not foo-10")
                .containsExactly(new GitGate.Landed("porelo/foo-10", landed));
    }

    @Test
    void aReadersPushThatCreatesUpdatesOrDeletesTheWorkItReadsIsRefused() throws Exception {
        // A reader rewriting what a person reviewed would hand every later reader other work.
        final String landedRef = GitGate.LANDED + "foo-1/porelo/foo-1";
        // Its work landed on a second branch too, so the delete does not hang on the update.
        final String secondRef = GitGate.LANDED + "foo-1/second";
        gate().recordLanded("foo-1", "second", landed);
        final TaskToken token = TaskToken.mint();
        final GitHttpServer server =
                new GitHttpServer(
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                                mirror,
                                token,
                                new GitSubprocess(),
                                GitGate.INCOMING + "reader")
                        .reading(List.of("foo-1"));
        server.start();
        try (server) {
            final String url =
                    "http://"
                            + InetAddress.getLoopbackAddress().getHostAddress()
                            + ":"
                            + server.port()
                            + "/mirror.git";
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            git(work, "commit", "--allow-empty", "-q", "-m", "the reader's rewrite");

            final org.assertj.core.api.SoftAssertions softly =
                    new org.assertj.core.api.SoftAssertions();
            softly.assertThatThrownBy(
                            () ->
                                    git(
                                            work,
                                            "push",
                                            url,
                                            "HEAD:" + GitGate.LANDED + "foo-1/porelo/other"))
                    .as("a new ref beside the work it reads, pushed by git")
                    .hasMessageContaining("rejected");
            // git sends no update or delete of a ref its push was not shown, so these are sent as
            // a client that knows the ref would send them; the new commit is one the mirror holds.
            softly.assertThat(receive(url, token, landed, held, landedRef))
                    .as("the work it reads, replaced")
                    .contains("ng " + landedRef);
            softly.assertThat(receive(url, token, landed, "0".repeat(40), secondRef))
                    .as("the work it reads, deleted")
                    .contains("ng " + secondRef);
            softly.assertAll();
        }

        assertThat(GitGate.landedIn(mirror, "foo-1"))
                .as("what the reader reads is what was reviewed, and nothing beside it")
                .containsExactly(
                        new GitGate.Landed("porelo/foo-1", landed),
                        new GitGate.Landed("second", landed));
    }

    /** Sends one receive-pack command as git would, with an empty pack unless it deletes. */
    private static String receive(String url, TaskToken token, String old, String now, String ref)
            throws Exception {
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(
                (pkt(old + " " + now + " " + ref + "\0report-status delete-refs\n") + "0000")
                        .getBytes(StandardCharsets.UTF_8));
        if (!now.equals("0".repeat(40))) {
            final byte[] header = {'P', 'A', 'C', 'K', 0, 0, 0, 2, 0, 0, 0, 0};
            body.writeBytes(header);
            body.writeBytes(java.security.MessageDigest.getInstance("SHA-1").digest(header));
        }
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url + "/git-receive-pack"))
                                .header("Authorization", token.basicAuthorization())
                                .header("Content-Type", "application/x-git-receive-pack-request")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String pkt(final String payload) {
        return String.format("%04x", payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
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
