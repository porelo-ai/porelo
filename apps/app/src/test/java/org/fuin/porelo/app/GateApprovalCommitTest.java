package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against forwarding a push the person has not reviewed. */
class GateApprovalCommitTest {

    private final CommandRunner runner = new ProcessCommandRunner();
    @TempDir Path dir;
    private PoreloContext context;
    private Path upstream;
    private Path work;
    private GitGate gate;
    private String base;
    private String reviewed;

    @BeforeEach
    void aPushWaitsAtTheGate() throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        upstream = dir.resolve("upstream.git");
        work = dir.resolve("work");
        git(dir, "init", "-q", "--bare", "-b", "main", upstream.toString());
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "commit", "-q", "--allow-empty", "-m", "base");
        git(work, "push", "-q", "origin", "main");
        base = git(work, "rev-parse", "HEAD");
        new DefaultProject(context).add(upstream.toString(), "app", upstream.toString(), "");
        final Project project = GateSupport.byName(context, "default");
        gate = GateSupport.gate(context, project, project.repository("app"), null, null);
        gate.initialize();
        reviewed = push("reviewed");
    }

    @Test
    void aScriptCannotApproveAnUnseenPushWithoutNamingACommit() {
        final Run review = run("review");
        assertThat(review.code()).as(review.err()).isZero();
        assertThat(review.out()).as("the reviewed revision was displayed").contains(reviewed);
        final String moved = push("unseen");

        final Run approval = run("approve");

        assertThat(approval.code()).as("a script must name the reviewed commit").isEqualTo(70);
        assertThat(git(upstream, "rev-parse", "refs/heads/main"))
                .as("no unseen commit is forwarded")
                .isEqualTo(base);
        assertThat(approval.err())
                .as("the retry binds the currently waiting revision")
                .contains("--commit " + moved);
        assertThat(gate.pending()).as("the unseen push still waits").contains("writer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "n"})
    void aTerminalNoLeavesTheRevisionWaiting(String answer) {
        final Run approval = approve(question -> answer);
        assertThat(approval.code()).as("no approval was given").isEqualTo(70);
        assertThat(git(upstream, "rev-parse", "main")).as("no work left the gate").isEqualTo(base);
        assertThat(gate.waiting("writer").commit()).as("the work still waits").isEqualTo(reviewed);
    }

    @Test
    void aTerminalYesForwardsExactlyTheRevisionInTheQuestion() {
        final Run approval =
                approve(
                        question -> {
                            assertThat(question)
                                    .as("the person sees the full revision and subject")
                                    .contains(reviewed, "reviewed", "[y/N]");
                            return "y";
                        });
        assertThat(approval.code()).as(approval.err()).isZero();
        assertThat(git(upstream, "rev-parse", "main"))
                .as("the confirmed commit arrived")
                .isEqualTo(reviewed);
        assertThat(gate.waiting("writer")).as("that approval clears the pending ref").isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPushDuringTheQuestionCannotUseTheAnswerForADifferentCommit(boolean signed) {
        final String[] moved = {null};
        final Run approval =
                approve(
                        question -> {
                            assertThat(question)
                                    .as("the question binds the original revision")
                                    .contains(reviewed);
                            moved[0] = push("unseen during question");
                            return "yes";
                        },
                        signed ? new String[] {"--signed"} : new String[0]);

        assertThat(approval.code()).as("both signing paths refuse a moved commit").isEqualTo(70);
        assertThat(approval.err()).as("the movement is explained").contains(reviewed, moved[0]);
        assertThat(git(upstream, "rev-parse", "main")).as("no unseen work leaves").isEqualTo(base);
        assertThat(gate.waiting("writer").commit())
                .as("the newer push remains")
                .isEqualTo(moved[0]);
    }

    @Test
    void aStoppedTasksMatchingFullIdForwardsAndAMovedIdFails() {
        final String moved = push("later work");
        final Run stale = run("approve", "--commit", reviewed);
        assertThat(stale.code()).as("a stopped task still rejects a stale review").isEqualTo(70);
        assertThat(git(upstream, "rev-parse", "main"))
                .as("the old review forwards nothing")
                .isEqualTo(base);
        final Run matching = run("approve", "--commit", moved);
        assertThat(matching.code()).as(matching.err()).isZero();
        assertThat(git(upstream, "rev-parse", "main"))
                .as("the named revision arrived")
                .isEqualTo(moved);
    }

    @Test
    void noInputRefusesEvenWhenATerminalCouldAnswer() {
        final Run approval =
                approve(
                        question -> {
                            throw new AssertionError("--no-input must not ask");
                        },
                        "--no-input",
                        "--signed");
        assertThat(approval.code()).as("no commit was named").isEqualTo(70);
        assertThat(approval.err())
                .as("the retry remains signed and bound")
                .contains("--commit " + reviewed, "--signed", "--branch main", "--repository app");
        assertThat(git(upstream, "rev-parse", "main")).as("nothing left").isEqualTo(base);
    }

    private Run approve(Offer.Asker asker, String... extra) {
        final GateApproveCommand command = new GateApproveCommand();
        command.setContext(
                new PoreloContext(
                        runner,
                        context.paths(),
                        args -> 0,
                        name -> null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(java.io.InputStream.nullInputStream(), true)));
        command.asker = asker;
        final picocli.CommandLine cli = new picocli.CommandLine(command);
        return execute(cli, List.of("-p", "default", "-r", "app"), extra);
    }

    private String push(String subject) {
        git(work, "commit", "-q", "--allow-empty", "-m", subject);
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + "writer");
        return git(work, "rev-parse", "HEAD");
    }

    private Run run(String verb, String... extra) {
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        return execute(cli, List.of("gate", verb, "-p", "default", "-r", "app"), extra);
    }

    private Run execute(picocli.CommandLine cli, List<String> arguments, String... extra) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args = new ArrayList<>(arguments);
        args.addAll(List.of(extra));
        args.add("writer");
        return new Run(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }

    private record Run(int code, String out, String err) {}

    private String git(Path in, String... args) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-C",
                                in.toString(),
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com"));
        all.addAll(List.of(args));
        return runner.runOrFail(
                        Command.of(all)
                                .withEnvironment(
                                        java.util.Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .standardOutput()
                .strip();
    }
}
