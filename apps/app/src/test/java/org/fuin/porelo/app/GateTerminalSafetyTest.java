package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Terminal decisions with scripted git output; no child process or signing key is used. */
class GateTerminalSafetyTest {

    private static final String COMMIT = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String SUBJECT = "hidden\u001b[8m\r\b\u007f\u009b";
    private static final String PATH = "hidden\u001b[8m\r\b\u007f\u009b.txt";
    @TempDir Path dir;
    private final FakeCommandRunner runner = new FakeCommandRunner();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private PoreloContext context;

    @BeforeEach
    void aGateWithOneWaitingCommit() throws Exception {
        final XdgPaths xdg = XdgPaths.of(variable -> null, dir);
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0)
                        .reading(new StandardInput(java.io.InputStream.nullInputStream(), true));
        new DefaultProject(context)
                .add(
                        dir.resolve("checkout").toString(),
                        "app",
                        dir.resolve("checkout").toString(),
                        "");
        final var project = GateSupport.byName(context, "default");
        final var gate = GateSupport.gate(context, project, project.repository("app"), null, null);
        Files.createDirectories(gate.mirror().resolve("objects"));
        runner.answering("--show-toplevel", dir.resolve("checkout") + "\n")
                .answering(
                        "--sort=committerdate",
                        "refs/porelo/incoming/writer\t"
                                + COMMIT
                                + "\t1700000000\tcommit\t"
                                + SUBJECT
                                + "\n")
                .answering("for-each-ref", "refs/porelo/incoming/writer\n")
                .answering("--format=%H%n%s", COMMIT + "\n" + SUBJECT + "\n")
                .answering("--format=%H %s", COMMIT + " " + SUBJECT + "\n")
                .answering("--numstat", "1\t0\t" + PATH + "\0")
                .answering(
                        "--raw",
                        ":000000 100644 " + "0".repeat(40) + " " + COMMIT + " A\0" + PATH + "\0")
                .answering(
                        "--patch", "diff --git a/file.txt b/file.txt\n" + "+captured\n".repeat(130))
                .answering("--end-of-options HEAD^{commit}", BASE + "\n")
                .answering("--end-of-options " + BASE + "^{commit}", BASE + "\n")
                .answering("symbolic-ref", "refs/heads/main\n")
                .failing("rev-parse --verify --quiet refs/heads/porelo/writer", 1, "");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void evenAnInteractiveYesCannotApproveWithoutNamingTheReviewedCommit(boolean signed) {
        final List<String> questions = new ArrayList<>();
        final GateApproveCommand command = new GateApproveCommand();
        command.setContext(context);
        command.asker =
                question -> {
                    questions.add(question);
                    return "yes";
                };
        final var cli = new picocli.CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> arguments = new ArrayList<>(List.of("-p", "default", "-r", "app"));
        if (signed) arguments.add("--signed");
        arguments.add("writer");

        final int code = cli.execute(arguments.toArray(String[]::new));

        assertThat(questions).as("a yes cannot replace reviewing a patch").isEmpty();
        assertThat(code).as(err.toString()).isEqualTo(70);
        assertThat(err.toString())
                .contains(
                        COMMIT,
                        "hidden\\x1b[8m\\x0d\\x08\\x7f\\x9b",
                        "porelo gate approve --project default --repository app --commit "
                                + COMMIT);
        if (signed) assertThat(err.toString()).contains("--signed");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" push ") || line.contains(" fetch "));
        assertThat(out.toString() + err).doesNotContain("\u001b", "\r", "\b", "\u007f", "\u009b");
    }

    @Test
    void rankingAndLogEscapeFileNamesAndSubjects() {
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        assertThat(cli.execute("gate", "review", "-p", "default", "-r", "app", "writer"))
                .as(err.toString())
                .isZero();

        assertThat(out.toString())
                .contains(
                        "hidden\\x1b[8m\\x0d\\x08\\x7f\\x9b.txt",
                        // The log keeps its line breaks, a carriage return among them.
                        COMMIT + " hidden\\x1b[8m\n\\x08\\x7f\\x9b")
                .doesNotContain("\u001b", "\r", "\b", "\u007f", "\u009b");
    }

    @Test
    void aCutCheckoutReviewNamesItsCapturedCommitBaseProjectAndTask() {
        final int code =
                new CheckoutApproval(context, new PrintWriter(out), new PrintWriter(err))
                        .run(dir.resolve("checkout"), "writer", null, false, question -> "n");

        assertThat(code).as(err.toString()).isEqualTo(CheckoutApproval.NOT_MOVED);
        assertThat(out.toString())
                .contains(
                        "more lines;",
                        "porelo gate review",
                        "--project default",
                        "--repository app",
                        "--commit " + COMMIT,
                        "--against " + BASE,
                        "-- writer");
    }

    @Test
    void aCapturedReviewStillShowsItsPatchButRefusesApprovalAfterAnotherPush() {
        final String moved = "c".repeat(40);
        runner.answering("--format=%H%n%s", moved + "\nunseen\n");
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        final int code =
                cli.execute(
                        "gate",
                        "review",
                        "-p",
                        "default",
                        "-r",
                        "app",
                        "--commit",
                        COMMIT,
                        "--against",
                        BASE,
                        "writer");

        assertThat(code)
                .as("the captured patch cannot authorize a newer waiting commit")
                .isEqualTo(70);
        assertThat(out.toString())
                .contains("+captured", "reviewed  " + COMMIT, "none for this commit", moved)
                .doesNotContain("porelo gate approve", "+unseen");
        assertThat(
                        runner.invocations().stream()
                                .filter(command -> command.arguments().contains("--patch")))
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.arguments())
                                        .as("the patch is the captured range, not the new tip")
                                        .contains(BASE + "..." + COMMIT));
    }

    @Test
    void aCheckoutApprovalQuestionEscapesTheTaskAndItsBranch() {
        final String name = "writer\u001b[8m\b\u009b";
        runner.answering(
                "--sort=committerdate",
                "refs/porelo/incoming/"
                        + name
                        + "\t"
                        + COMMIT
                        + "\t1700000000\tcommit\treviewed\n");
        final List<String> questions = new ArrayList<>();

        final int code =
                new CheckoutApproval(context, new PrintWriter(out), new PrintWriter(err))
                        .run(
                                dir.resolve("checkout"),
                                name,
                                null,
                                false,
                                question -> {
                                    questions.add(question);
                                    return "n";
                                });

        assertThat(code).as(err.toString()).isEqualTo(CheckoutApproval.NOT_MOVED);
        assertThat(questions)
                .singleElement()
                .asString()
                .contains("porelo/writer\\x1b[8m\\x08\\x9b")
                .doesNotContain("\u001b", "\b", "\u009b");
    }
}
