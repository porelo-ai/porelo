package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link UnhandedWork}. */
class UnhandedWorkTest {

    private static void wrote(Path state, String content) throws IOException {
        Files.writeString(state.resolve(UnhandedWork.FILE), content);
    }

    /** git without the machine's own configuration. */
    private static final java.util.Map<String, String> GIT =
            java.util.Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");

    private static void git(Path workspace, String... arguments)
            throws IOException, InterruptedException {
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().putAll(GIT);
        final Process process = builder.start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("git " + String.join(" ", arguments)).isZero();
    }

    /** Asks a workspace on this machine as a running task's is asked: the census, run by sh. */
    private static UnhandedWork.Held asked(Path workspace) {
        return asked(workspace, java.util.Map.of());
    }

    /**
     * Asks a workspace as a task's is asked, with the environment the task's container has: a
     * gate's address only where it was given one, whatever the environment these tests run in has.
     */
    private static UnhandedWork.Held asked(Path workspace, java.util.Map<String, String> task) {
        final org.fuin.porelo.core.process.CommandRunner here =
                command -> {
                    final String script = command.arguments().getLast();
                    final java.util.Map<String, String> environment = new java.util.HashMap<>(GIT);
                    environment.putAll(task);
                    return new org.fuin.porelo.core.process.ProcessCommandRunner()
                            .run(
                                    org.fuin.porelo.core.process.Command.of(
                                                    "env",
                                                    "-u",
                                                    "PORELO_REMOTE_URL",
                                                    "sh",
                                                    "-c",
                                                    (task.containsKey("PORELO_REMOTE_URL")
                                                                    ? "export PORELO_REMOTE_URL='"
                                                                            + task.get(
                                                                                    "PORELO_REMOTE_URL")
                                                                            + "'; "
                                                                    : "")
                                                            + script.replace(
                                                                    UnhandedWork.WORKSPACE,
                                                                    workspace.toString()))
                                            .withEnvironment(environment));
                };
        return UnhandedWork.ask(new org.fuin.porelo.runtime.Podman(here), "porelo-p-t");
    }

    @Test
    void aNoGateTasksWorkspaceIsNoCheckoutAndHoldsNothingItCouldHandOn(@TempDir Path workspace)
            throws IOException {

        // With --no-gate, /workspace is the image's own directory and never a git checkout: git
        // failed there, the task read as unknown, and a stopped one was removed only with --force.
        Files.writeString(workspace.resolve("scratch.md"), "nothing a gate could take\n");

        assertThat(asked(workspace))
                .as("no checkout, so nothing that could reach a gate")
                .isEqualTo(new UnhandedWork.Held(true, 0, 0, null));
    }

    @Test
    void aGatedTasksWorkspaceWithoutItsCheckoutIsStillNobodyCouldLook(@TempDir Path workspace)
            throws IOException {

        // A task given a gate has its checkout at /workspace; one gone is no answer that it holds
        // nothing, so removing it still asks.
        Files.writeString(workspace.resolve("report.md"), "unpushed\n");

        assertThat(
                        asked(
                                        workspace,
                                        java.util.Map.of(
                                                "PORELO_REMOTE_URL",
                                                "http://169.254.1.2:9418/uc.git"))
                                .readable())
                .as("nobody could look")
                .isFalse();
    }

    @Test
    void aWorkspaceNothingCanWriteHoldsNothing(@TempDir Path workspace) throws IOException {

        // A task that reads its whole project: its repositories, read only, no repository on top.
        Files.createDirectories(workspace.resolve("front"));
        Files.writeString(workspace.resolve("front").resolve("index.html"), "read\n");
        Files.setPosixFilePermissions(
                workspace, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(asked(workspace))
                    .as("nothing it could hand on, which is not 'nobody could look'")
                    .isEqualTo(new UnhandedWork.Held(true, 0, 0, null));
        } finally {
            Files.setPosixFilePermissions(
                    workspace,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aWorkspaceGitCannotReadIsNotAWorkspaceThatHoldsNothing(@TempDir Path workspace)
            throws IOException, InterruptedException {

        // Counted through a pipe, a failing git status counted no lines: the task read as holding
        // nothing, and a removal went ahead without asking.
        git(workspace, "init", "-q");
        Files.writeString(workspace.resolve("report.md"), "unpushed\n");
        Files.writeString(
                workspace.resolve(".git/config"),
                "[core\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(asked(workspace).readable()).as("nobody could look").isFalse();
    }

    @Test
    void aWorkspaceIsCountedFromItsUnbornHeadOn(@TempDir Path workspace)
            throws IOException, InterruptedException {

        // A task's workspace is checked out on an unborn HEAD, so the census must count there too.
        git(workspace, "init", "-q");
        Files.writeString(workspace.resolve("report.md"), "unpushed\n");
        assertThat(asked(workspace))
                .as("one new file, no commit")
                .isEqualTo(new UnhandedWork.Held(true, 1, 0, null));

        git(workspace, "add", "report.md");
        git(
                workspace,
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@example.test",
                "commit",
                "-qm",
                "r");
        Files.writeString(workspace.resolve("notes.md"), "more\n");
        assertThat(asked(workspace))
                .as("one commit on no remote and one new file")
                .isEqualTo(new UnhandedWork.Held(true, 1, 1, null));
    }

    @Test
    void carriesTheCountsBackRatherThanASentence(@TempDir Path state) {

        UnhandedWork.note(state, new UnhandedWork.Held(true, 3, 2, null));

        final UnhandedWork.Held read = UnhandedWork.read(state);
        assertThat(read.readable()).isTrue();
        assertThat(read.changedFiles()).isEqualTo(3);
        assertThat(read.unpushedCommits()).isEqualTo(2);
        assertThat(read.asOf()).as("a recorded answer is historical and says so").isNotNull();
    }

    @Test
    void tellsHoldingNothingApartFromNobodyLooking(@TempDir Path state) {

        // The whole reason a note is written even when there is nothing. Only one of these two
        // makes it safe to remove a task without asking.
        UnhandedWork.note(state, new UnhandedWork.Held(true, 0, 0, null));
        assertThat(UnhandedWork.read(state).readable()).isTrue();
        assertThat(UnhandedWork.read(state).anything()).isFalse();

        assertThat(UnhandedWork.read(state.resolve("no-note-here")).readable()).isFalse();
    }

    @Test
    void readsBackANoteAnOlderPoreloWrote(@TempDir Path state) throws IOException {

        // The old format recorded the sentence. Parsed rather than discarded: it is our own wording
        // in one of three shapes, and answering "nobody looked" about a task that was measured
        // would destroy work this exists to protect.
        wrote(state, "2 commits and 3 changed files");
        assertThat(UnhandedWork.read(state))
                .satisfies(
                        held -> {
                            assertThat(held.readable()).isTrue();
                            assertThat(held.unpushedCommits()).isEqualTo(2);
                            assertThat(held.changedFiles()).isEqualTo(3);
                            assertThat(held.asOf())
                                    .as("taken from the file, the only thing that knows")
                                    .isNotNull();
                        });

        wrote(state, "1 commit");
        assertThat(UnhandedWork.read(state).unpushedCommits()).isEqualTo(1);
        assertThat(UnhandedWork.read(state).changedFiles()).isZero();

        wrote(state, "1 changed file");
        assertThat(UnhandedWork.read(state).changedFiles()).isEqualTo(1);
        assertThat(UnhandedWork.read(state).unpushedCommits()).isZero();
    }

    @Test
    void treatsAnEmptyOldNoteAsHavingHeldNothing(@TempDir Path state) throws IOException {

        // That is what an empty note meant, and it meant it deliberately.
        wrote(state, "");
        assertThat(UnhandedWork.read(state).readable()).isTrue();
        assertThat(UnhandedWork.read(state).anything()).isFalse();
    }

    @Test
    void refusesANoteFromALaterPorelo(@TempDir Path state) throws IOException {

        // A misread answer here decides whether work is destroyed, so an unknown version is "nobody
        // looked" rather than a guess - the same rule the sidecar follows.
        wrote(state, "{\"version\":99,\"changedFiles\":0,\"unpushedCommits\":0}");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
    }

    @Test
    void refusesANoteItCannotMakeSenseOf(@TempDir Path state) throws IOException {

        wrote(state, "something nobody wrote on purpose");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
        wrote(state, "{not json");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
    }

    @Test
    void answersUnknownWhenTheContainerSaidSomethingUnusable() {

        // Two numbers or nothing. A container that answered something else is not a container that
        // answered "nothing".
        assertThat(UnhandedWork.census("3 2").readable()).isTrue();
        assertThat(UnhandedWork.census("").readable()).isFalse();
        assertThat(UnhandedWork.census("lots").readable()).isFalse();
        assertThat(UnhandedWork.census("a b").readable()).isFalse();
    }

    @Test
    void wordsTheCountsOnlyWhereSomebodyReadsThem() {

        assertThat(new UnhandedWork.Held(true, 3, 2, null).phrase())
                .isEqualTo("2 commits and 3 changed files");
        assertThat(new UnhandedWork.Held(true, 1, 1, null).phrase())
                .isEqualTo("1 commit and 1 changed file");
        assertThat(new UnhandedWork.Held(true, 0, 0, null).phrase()).isNull();
        assertThat(UnhandedWork.Held.unknown().phrase()).isNull();
    }
}
