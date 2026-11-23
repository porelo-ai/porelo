package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link CheckoutApproval}, with real git: a checkout a task started from, its gate, and
 * work waiting there.
 */
class CheckoutApprovalTest {

    @TempDir Path dir;

    private final CommandRunner runner = NoTasks.over(new ProcessCommandRunner());

    private PoreloContext context;

    private Path checkout;

    private Path origin;

    private GitGate gate;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /** What the person answers, one line per question; empty: they answer nothing. */
    private final Deque<String> answers = new ArrayDeque<>();

    private final List<String> asked = new java.util.ArrayList<>();

    @BeforeEach
    void aCheckoutATaskStartedFrom() throws Exception {
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
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .reading(new StandardInput(java.io.InputStream.nullInputStream(), true));
        origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        checkout = dir.resolve("app");
        git(dir, "clone", "-q", origin.toString(), checkout.toString());
        identity(checkout);
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", "README.md");
        git(checkout, "commit", "-q", "-m", "start");
        git(checkout, "push", "-q", "origin", "main");
        // What starting a task in the checkout does: the repository goes into 'default', and its
        // gate is seeded.
        final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, checkout);
        final Project project = GateSupport.byName(context, choice.project());
        gate =
                GateSupport.gate(
                        context,
                        project,
                        project.repository(choice.repository()),
                        null,
                        checkout.toString());
        gate.initialize();
    }

    @Test
    void afterYesTheCheckoutHasTheBranchAndItsWorkingCopyAndOriginAreUntouched() throws Exception {
        final String work = agentPushes("writer", "the agent's change");
        answers.add("y");

        assertThat(approve(null, false)).as(err.toString()).isZero();

        assertThat(git(checkout, "rev-parse", "porelo/writer")).isEqualTo(work);
        assertThat(git(checkout, "symbolic-ref", "--short", "HEAD"))
                .as("the branch checked out")
                .isEqualTo("main");
        assertThat(git(checkout, "status", "--porcelain")).as("the working copy").isEmpty();
        assertThat(git(origin, "branch", "--list", "porelo/*"))
                .as("nothing reaches the forge")
                .isEmpty();
        assertThat(out.toString())
                .as("what came was shown before the question")
                .contains("the agent's change")
                .contains("+the agent's change");
        assertThat(asked).singleElement().asString().contains("porelo/writer");
        assertThat(gate.pending()).as("brought back, so no longer waiting").isEmpty();
    }

    @Test
    void afterNoNothingChangesAndTheWorkStillWaits() throws Exception {
        agentPushes("writer", "kept for later");
        answers.add("n");

        assertThat(approve(null, false)).isNotZero();

        assertThat(git(checkout, "branch", "--list", "porelo/*")).isEmpty();
        assertThat(gate.pending()).containsExactly("writer");
    }

    @Test
    void nothingWaitingIsSaidAndIsNoSuccess() {
        assertThat(approve(null, true)).isNotZero();
        assertThat(err.toString()).contains("nothing waits at the gate");
    }

    @Test
    void twoTasksAreNamedAndOneIsAskedForOrTakenByItsName() throws Exception {
        final String writer = agentPushes("writer", "one");
        final String review = agentPushes("review", "two");

        assertThat(approve(null, true)).as("a script that names none is refused").isNotZero();
        assertThat(err.toString()).contains("writer").contains("review");

        answers.add("review");
        answers.add("y");
        assertThat(approve(null, false)).as(err.toString()).isZero();
        assertThat(git(checkout, "rev-parse", "porelo/review")).isEqualTo(review);

        assertThat(approve("writer", writer, true)).as(err.toString()).isZero();
        assertThat(git(checkout, "rev-parse", "porelo/writer")).isEqualTo(writer);
    }

    @Test
    void aNameTypedWrongAtWhichOneIsRefusedAsNothingOfThatNameAndBringsNothingBack()
            throws Exception {

        // A script reads the exit: the sentence said no work of that name waits, and the exit said
        // the question was ambiguous (64), which a caller takes as "name one" and asks again with
        // the same wrong name.
        agentPushes("writer", "one");
        agentPushes("review", "two");

        answers.add("revue");
        assertThat(approve(null, false)).as(err.toString()).isEqualTo(CheckoutApproval.NOTHING);
        assertThat(err.toString())
                .contains("no work of 'revue' waits")
                .contains("writer")
                .contains("review");
        assertThat(git(checkout, "branch", "--list", "porelo/*"))
                .as("nothing was brought back")
                .isEmpty();
    }

    @Test
    void aBranchThatDoesNotFastForwardIsNotOverwritten() throws Exception {
        git(checkout, "branch", "porelo/writer");
        git(checkout, "checkout", "-q", "porelo/writer");
        Files.writeString(checkout.resolve("mine.txt"), "the person's own\n");
        git(checkout, "add", "mine.txt");
        git(checkout, "commit", "-q", "-m", "mine");
        final String mine = git(checkout, "rev-parse", "HEAD");
        git(checkout, "checkout", "-q", "main");
        agentPushes("writer", "the agent's");

        assertThat(approve("writer", false)).isNotZero();

        assertThat(git(checkout, "rev-parse", "porelo/writer")).isEqualTo(mine);
        assertThat(err.toString()).contains("fast-forward");
        assertThat(asked).as("refused before anybody is asked to say yes to it").isEmpty();
        assertThat(gate.pending()).containsExactly("writer");
    }

    @Test
    void aCommitTheRemoteNeverSawReachesTheGateFromTheCheckout() throws Exception {

        // The checkout is the source: what is committed here goes in, pushed to origin or not.
        Files.writeString(checkout.resolve("local.txt"), "never pushed\n");
        git(checkout, "add", "local.txt");
        git(checkout, "commit", "-q", "-m", "local only");
        final String local = git(checkout, "rev-parse", "HEAD");

        assertThat(gate.refresh()).isEmpty();

        assertThat(git(gate.mirror(), "rev-parse", "main"))
                .as("the mirror follows the checkout")
                .isEqualTo(local);
        assertThat(git(origin, "rev-parse", "main")).as("origin never saw it").isNotEqualTo(local);
    }

    @Test
    void workOfARepositoryWhoseSourceIsTheRemoteIsNotBroughtIntoTheCheckout() throws Exception {

        // Added by its address, as every entry from before the checkout was a source: its work goes
        // to the remote.
        final Path other = dir.resolve("other");
        git(dir, "clone", "-q", origin.toString(), other.toString());
        final DefaultProject.Entry entry =
                new DefaultProject(context).add(origin.toString(), "by-address", "");
        final Project project = GateSupport.byName(context, DefaultProject.NAME);
        final GitGate remote =
                GateSupport.gate(context, project, project.repository(entry.name()), null, null);
        remote.initialize();
        final Path work = dir.resolve("work-remote");
        git(dir, "clone", "-q", remote.mirror().toString(), work.toString());
        identity(work);
        Files.writeString(work.resolve("r.txt"), "for the remote\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "for the remote");
        git(
                work,
                "push",
                "-q",
                remote.mirror().toString(),
                "HEAD:" + GitGate.INCOMING + "remote-task");

        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final int code =
                new CheckoutApproval(
                                context, new PrintWriter(out, true), new PrintWriter(err, true))
                        .run(other, null, null, true, question -> "y");

        assertThat(code).isNotZero();
        assertThat(err.toString()).contains("goes to").contains("porelo gate approve");
        assertThat(git(other, "branch", "--list", "porelo/*")).isEmpty();
        assertThat(remote.pending()).containsExactly("remote-task");
    }

    @Test
    void nothingAtTheGateButWorkInTheTaskIsSaidWithHowToBringItThere() throws Exception {

        // The agent committed and never pushed; 'approve' said only that nothing waited.
        final String repository =
                new DefaultProject(context).withUpstream(checkout.toRealPath().toString()).name();
        final String task = "porelo-default-" + repository + "-writer";
        final CommandRunner podman =
                command -> {
                    final List<String> arguments = command.arguments();
                    if (arguments.size() > 1
                            && arguments.get(0).endsWith("podman")
                            && arguments.get(1).equals("ps")) {
                        return new CommandResult(
                                command,
                                0,
                                task
                                        + "\tUp 3 minutes\t\t\tdefault\tguarded\t"
                                        + repository
                                        + "\t\tid\n",
                                "");
                    }
                    if (arguments.size() > 1
                            && arguments.get(0).endsWith("podman")
                            && arguments.get(1).equals("exec")) {
                        return new CommandResult(command, 0, "1 2", "");
                    }
                    return runner.run(command);
                };
        final PoreloContext withTask = new PoreloContext(podman, context.paths(), arguments -> 0);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        final int code =
                new CheckoutApproval(
                                withTask, new PrintWriter(out, true), new PrintWriter(err, true))
                        .run(checkout, null, null, true, question -> null);

        assertThat(code).isNotZero();
        assertThat(err.toString())
                .contains("nothing waits at the gate")
                .contains(task)
                .contains("2 commits and 1 changed file")
                .contains("git push");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(5)
    void withoutATerminalApprovalNeverReadsAnOpenPipe(boolean inputTerminal) throws Exception {
        agentPushes("writer", "one");
        context =
                context.reading(
                        new StandardInput(
                                java.io.InputStream.nullInputStream(), inputTerminal, false));
        assertThat(approve(null, false)).isEqualTo(1);
        assertThat(err.toString()).contains("--yes");
        assertThat(asked).isEmpty();
        assertThat(gate.pending()).containsExactly("writer");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(5)
    void withoutATerminalSeveralTasksKeepTheEmptyInputRefusal(boolean inputTerminal)
            throws Exception {
        agentPushes("writer", "one");
        agentPushes("review", "two");
        context =
                context.reading(
                        new StandardInput(
                                java.io.InputStream.nullInputStream(), inputTerminal, false));
        assertThat(approve(null, false)).isEqualTo(64);
        assertThat(err.toString()).contains("writer", "review", "--yes");
        assertThat(asked).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Timeout(
            value = 5,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void theApproveCommandReadsItsContextsTerminalInput() throws Exception {
        agentPushes("writer", "one");
        assertThat(cli("y\n", null, "approve", "writer")).as(err.toString()).isZero();
        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void aScriptThatNamesNoCommitBringsNothingBackAndSaysWhichCommitToGive() throws Exception {

        // With --yes alone, what waited when the script ran came back: a push the task made after
        // the person looked included.
        final String work = agentPushes("writer", "seen");

        final int code = cli("", null, "approve", "writer", "--yes");

        assertThat(code).as("nothing is brought back without the commit").isNotZero();
        assertThat(git(checkout, "branch", "--list", "porelo/*")).as("no branch").isEmpty();
        assertThat(gate.pending()).as("the work still waits").containsExactly("writer");
        assertThat(err.toString())
                .as("the command that brings back the commit shown")
                .contains("porelo approve writer --yes --commit " + work);
    }

    @Test
    void aScriptBringsBackOnlyTheCommitItNamesAndRefusesOneTheTaskPushedPast() throws Exception {
        final String seen = agentPushes("writer", "seen");
        final String unseen = agentPushesAgain("writer", "unseen");

        assertThat(cli("", null, "approve", "writer", "--yes", "--commit", seen))
                .as("the work moved past the commit named")
                .isNotZero();
        assertThat(err.toString()).contains("moved since it was reviewed", seen, unseen);
        assertThat(git(checkout, "branch", "--list", "porelo/*")).as("no branch").isEmpty();

        assertThat(cli("", null, "approve", "writer", "--yes", "--commit", unseen))
                .as(err.toString())
                .isZero();
        assertThat(git(checkout, "rev-parse", "porelo/writer")).isEqualTo(unseen);
    }

    @Test
    void whatIsShownAndAskedAboutIsOneCommitEvenWhenTheTaskPushesWhileItIsShown() throws Exception {

        // The log and the diff each read the moving ref again: one push's log came with another's
        // diff, and the question named no commit.
        final String seen = agentPushes("writer", "seen");
        final String[] unseen = {null};

        final int code =
                cli(
                        "y\n",
                        () -> unseen[0] = agentPushesAgain("writer", "unseen"),
                        "approve",
                        "writer");

        assertThat(unseen[0]).as("the push really arrived while it was shown").isNotNull();
        assertThat(out.toString())
                .as("the commit, its log and its diff are the one captured")
                .contains("commit    " + seen, seen + " seen", "+seen")
                .doesNotContain("unseen");
        assertThat(code).as("the yes was for the commit shown, which no longer waits").isNotZero();
        assertThat(err.toString()).contains("moved since it was reviewed", seen, unseen[0]);
        assertThat(git(checkout, "branch", "--list", "porelo/*")).as("no branch").isEmpty();
        assertThat(gate.pending()).as("the newer push waits").containsExactly("writer");
    }

    /**
     * Runs {@code porelo} in the checkout, with the given terminal input; {@code beforeShown} runs
     * once, just before the gate's first log is read.
     */
    private int cli(final String input, final Runnable beforeShown, final String... argv) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final Runnable[] once = {beforeShown};
        final var machine =
                new PoreloContext(
                                command -> {
                                    if (once[0] != null
                                            && command.arguments().contains("--git-dir")
                                            && command.arguments().contains("log")) {
                                        final Runnable action = once[0];
                                        once[0] = null;
                                        action.run();
                                    }
                                    final List<String> arguments =
                                            new java.util.ArrayList<>(command.arguments());
                                    final int directory = arguments.indexOf("-C");
                                    if (directory >= 0
                                            && arguments.get(directory + 1).equals(".")) {
                                        arguments.set(directory + 1, checkout.toString());
                                    }
                                    return runner.run(
                                            new Command(
                                                    arguments,
                                                    command.workingDirectory(),
                                                    command.environment(),
                                                    command.input(),
                                                    command.inheritsEnvironment(),
                                                    command.binaryInput()));
                                },
                                context.paths(),
                                arguments -> 0)
                        .reading(
                                new StandardInput(
                                        new java.io.ByteArrayInputStream(
                                                input.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)),
                                        true));
        final var cli = PoreloCli.commandLine(machine);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(argv);
    }

    private int approve(final String task, final boolean yes) {
        return approve(task, null, yes);
    }

    private int approve(final String task, final String commit, final boolean yes) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        return new CheckoutApproval(context, new PrintWriter(out, true), new PrintWriter(err, true))
                .run(
                        checkout,
                        task,
                        commit,
                        yes,
                        question -> {
                            asked.add(question);
                            return answers.poll();
                        });
    }

    /**
     * What an agent does: commits on what the task started from and pushes it to its own ref at the
     * gate.
     */
    private String agentPushes(final String task, final String line) throws Exception {
        final Path work = dir.resolve("work-" + task);
        git(dir, "clone", "-q", checkout.toString(), work.toString());
        identity(work);
        Files.writeString(work.resolve(task + ".txt"), line + "\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", line);
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + task);
        return git(work, "rev-parse", "HEAD");
    }

    /** What an agent does next: one more commit on its work, pushed to the same ref. */
    private String agentPushesAgain(final String task, final String line) {
        final Path work = dir.resolve("work-" + task);
        try {
            Files.writeString(work.resolve(task + ".txt"), line + "\n");
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", line);
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + task);
        return git(work, "rev-parse", "HEAD");
    }

    private void identity(final Path repository) {
        git(repository, "config", "user.email", "t@example.com");
        git(repository, "config", "user.name", "T");
    }

    private String git(final Path in, final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(command));
        assertThat(result.successful())
                .as(String.join(" ", command) + ": " + result.standardError())
                .isTrue();
        return result.standardOutput().strip();
    }
}
