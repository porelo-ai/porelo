package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Tests for opening a shell in a running task.
 *
 * <p>The arguments are the whole behaviour: this command replaces its own process with the session,
 * so what it builds is what a person ends up talking to. Getting the program and its arguments
 * joined into one string would have podman look for an executable with spaces in its name, which
 * fails as "no such file" and reads like a broken container.
 */
class TaskAttachCommandTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<String> attached = new ArrayList<>();

    private final StringWriter err = new StringWriter();

    private final StringWriter out = new StringWriter();

    private boolean terminal;

    private int execExit;

    /** Runs attach with a terminal and exit code supplied by the test. */
    private int run(Path dir, String... arguments) throws java.io.IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        command -> {
                            attached.addAll(command);
                            return execExit;
                        });
        LegacyTaskFixture.record(context, "porelo-uc-shell-1", "uc", "a1b2c3d4e5f6");
        LegacyTaskFixture.record(
                context, "porelo-offlineproj-shell-1", "offlineproj", "a1b2c3d4e5f6");
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        ((TaskAttachCommand)
                                cmd.getSubcommands()
                                        .get("task")
                                        .getSubcommands()
                                        .get("attach")
                                        .getCommand())
                        .terminal =
                () -> terminal;
        return cmd.execute(arguments);
    }

    /** The attach command restores the terminal after either podman exit status. */
    @ParameterizedTest
    @ValueSource(ints = {0, 125})
    void returnsTheTerminalAfterAttachingToAnExistingSession(int code, @TempDir Path dir)
            throws java.io.IOException {
        runner.answering(
                "ps",
                LegacyTaskFixture.listed("porelo-uc-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));
        runner.answering("stty -g", "saved-settings\n");
        terminal = true;
        execExit = code;

        assertThat(run(dir, "task", "attach", "porelo-uc-shell-1"))
                .as("attach preserves the session's exit code")
                .isEqualTo(code);

        assertThat(attached)
                .as("the existing tmux session is attached")
                .contains(org.fuin.porelo.runtime.Podman.SESSION_START);
        assertThat(runner.only("stty \"$1\"").arguments())
                .as("the terminal settings saved before attach are restored")
                .endsWith("saved-settings");
        assertThat(out.toString())
                .as("attach resets every terminal mode on its output stream")
                .isEqualTo(TerminalAttachTest.RESETS);
    }

    @Test
    void attachesToASessionThatIsFoundAgainOnReturn(@TempDir Path dir) throws java.io.IOException {

        // 'new-session -A' is attach-or-create in one call, so coming back and starting for the
        // first time are the same operation - and the one that matters is exercised every time
        // rather than only once.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed("porelo-uc-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));

        assertThat(run(dir, "task", "attach", "porelo-uc-shell-1")).isZero();

        assertThat(attached)
                .containsSubsequence(
                        "exec",
                        "--interactive",
                        "--tty",
                        "porelo-uc-shell-1",
                        "sh",
                        "-c",
                        org.fuin.porelo.runtime.Podman.SESSION_START);
    }

    @Test
    void theSessionReadsNoStartFileTheTaskWrote(@TempDir Path dir) throws java.io.IOException {

        // What the session's first window runs goes through a shell, and a shell reads the file
        // BASH_ENV or ENV names: both empty, so a file the agent wrote never sets what the next
        // start runs with.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed("porelo-uc-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));

        assertThat(run(dir, "task", "attach", "porelo-uc-shell-1")).isZero();

        assertThat(attached.subList(0, attached.indexOf("porelo-uc-shell-1")))
                .containsSubsequence("--env", "BASH_ENV=", "--env", "ENV=");
    }

    @Test
    void keepsTheProgramAndItsArgumentsApart(@TempDir Path dir) throws java.io.IOException {

        // Joined into one string, podman looks for an executable called "tmux new-session -A -s
        // porelo" and fails with "no such file" - which reads as a broken container rather than as
        // a mistake here.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed("porelo-uc-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));

        run(dir, "task", "attach", "porelo-uc-shell-1");

        assertThat(
                        attached.subList(
                                0, attached.indexOf(org.fuin.porelo.runtime.Podman.SESSION_START)))
                .noneMatch(argument -> argument.contains(" "));
        assertThat(attached).contains(org.fuin.porelo.runtime.Podman.SESSION_START);
    }

    @Test
    void aStoppedTaskIsSentToStartRatherThanToTheList(@TempDir Path dir)
            throws java.io.IOException {

        // Two different things a person has to do next. "No such task" would send somebody looking
        // at the list for something that is right there, stopped.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed(
                        "porelo-uc-shell-1\tExited (0) 2 minutes ago\n", "a1b2c3d4e5f6"));

        assertThat(run(dir, "task", "attach", "porelo-uc-shell-1")).isEqualTo(69);
        assertThat(err.toString()).contains("is not running").contains("porelo task start");
        assertThat(attached).as("nothing was attached to").isEmpty();
    }

    @Test
    void aTaskThatIsNotThereNamesTheOnesThereAre(@TempDir Path dir) throws java.io.IOException {

        // It named 'porelo task list' to type; the list is what was wanted, so it is said instead.
        runner.answering("ps", LegacyTaskFixture.listed("", "a1b2c3d4e5f6"));

        assertThat(run(dir, "task", "attach", "porelo-uc-shell-1")).isEqualTo(69);
        assertThat(err.toString()).contains("no task called").contains("there is no task here");
    }

    @Test
    void doesNotAskAScriptWhetherToStartAStoppedTask() {

        // The gate that matters. This command is also how a script gets into a task, and one that
        // finds it stopped must fail rather than wait for an answer nobody will type.
        assertThat(new TaskAttachCommand().offerToStart(new PrintWriter(new StringWriter()), null))
                .isFalse();
    }

    @Test
    void takesSilenceAsYesButEndOfInputAsNo() {

        // Yes is the default because the question only exists after somebody asked to attach - the
        // answer is implied and the prompt is there to say what it costs. Ctrl-D is not that: end
        // of input answers nothing, and nothing is not consent.
        assertThat(TaskAttachCommand.consented("")).isTrue();
        assertThat(TaskAttachCommand.consented("y")).isTrue();
        assertThat(TaskAttachCommand.consented("YES")).isTrue();
        assertThat(TaskAttachCommand.consented(" y ")).isTrue();
        assertThat(TaskAttachCommand.consented("n")).isFalse();
        assertThat(TaskAttachCommand.consented("no")).isFalse();
        assertThat(TaskAttachCommand.consented(null)).as("end of input is not consent").isFalse();
        assertThat(TaskAttachCommand.consented("maybe")).as("only yes is yes").isFalse();
    }

    @Test
    void somethingThatIsNotATaskIsRefusedOnItsNameAlone(@TempDir Path dir)
            throws java.io.IOException {

        // The refusal is decided by the name, before anything about the machine is consulted. What
        // follows it is a listing, offered because somebody who typed a name that is not a task is
        // exactly who needs to see the ones that are - and it is read-only. A podman that cannot
        // answer costs nothing here: the offer swallows it and the refusal stands.
        assertThat(run(dir, "task", "attach", "some-other-container")).isEqualTo(69);
        assertThat(attached).as("nothing is attached to a name that is not a task").isEmpty();
        assertThat(runner.lines())
                .as("only ever asked to list, never to act")
                .allMatch(line -> line.startsWith("podman ps"));
    }

    @Test
    void aTaskOfProjectPoreloUnderItsNewNameIsAttached(@TempDir Path dir)
            throws java.io.IOException {
        // Refused by its beginning as no task, porelo_pi could not be gone into at all.
        runner.answering(
                "ps",
                "porelo_pi\tUp 4 minutes\t1700000000\t0\tporelo\tguarded\tporelo\t\t"
                        + "a1b2c3d4e5f6\tpi\n");
        TaskIdentityFixture.running(
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin")),
                "porelo_pi",
                "porelo",
                "pi",
                "a1b2c3d4e5f6");

        assertThat(run(dir, "task", "attach", "porelo_pi")).as("%s", err).isZero();
        assertThat(attached)
                .as("the task's session")
                .contains("porelo_pi", org.fuin.porelo.runtime.Podman.SESSION_START);
    }

    @Test
    void anOfflineProjectIsNotRefused(@TempDir Path dir) throws java.io.IOException {

        // The class governs egress - what resolves and what leaves - and a person typing in a
        // container is neither. Offline is precisely the case where somebody has to work by hand,
        // because the agent reaches nothing; refusing would remove the only way in.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed(
                        "porelo-offlineproj-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));

        assertThat(run(dir, "task", "attach", "porelo-offlineproj-shell-1")).isZero();
        assertThat(attached).contains(org.fuin.porelo.runtime.Podman.SESSION_START);
    }

    @Test
    void readsThePinnedConfigurationRatherThanWhateverIsAround(@TempDir Path dir)
            throws java.io.IOException {

        // What a session remembers IS the answer to "what may re-entering claim". Left to tmux's
        // own search it would come from the image, or a dotfile, or a default nobody chose - and
        // then the one process that has to state the figure does not know it.
        runner.answering(
                "ps",
                LegacyTaskFixture.listed("porelo-uc-shell-1\tUp 4 minutes\n", "a1b2c3d4e5f6"));

        run(dir, "task", "attach", "porelo-uc-shell-1");

        assertThat(attached).contains(org.fuin.porelo.runtime.Podman.SESSION_START);
        assertThat(org.fuin.porelo.runtime.Podman.SESSION_START)
                .contains(" -f /etc/porelo/tmux.conf ");
    }
}
