package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a task's push stopped waiting is kept for the task, on the command line's paths and the
 * daemon's, with real git: {@code gate approve} and {@code gate reject}, a landing, and the next
 * push that waits.
 */
class GateEndsTest {

    private static final String RECORDED = "a".repeat(64);

    private static final String LATER = "b".repeat(64);

    private final CommandRunner gitRunner = new ProcessCommandRunner();

    // Git for real, so the gate reads a real mirror; podman lists no container.
    private final CommandRunner runner =
            command ->
                    command.arguments().getFirst().endsWith("podman")
                            ? new CommandResult(command, 0, "", "")
                            : gitRunner.run(command);

    @TempDir Path dir;

    private PoreloContext context;

    private Path upstream;

    private Path work;

    private Project project;

    private GitGate gate;

    private String waiting;

    @BeforeEach
    void aRecordedTasksPushWaitsAtTheGate() throws Exception {
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
                new PoreloContext(
                        runner,
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0,
                        variable -> null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(java.io.InputStream.nullInputStream(), true));
        upstream = dir.resolve("upstream.git");
        work = dir.resolve("work");
        git(dir, "init", "-q", "--bare", "-b", "main", upstream.toString());
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "commit", "-q", "--allow-empty", "-m", "base");
        git(work, "push", "-q", "origin", "main");
        new DefaultProject(context).add(upstream.toString(), "app", upstream.toString(), "");
        project = GateSupport.byName(context, "default");
        gate = GateSupport.gate(context, project, project.repository("app"), null, null);
        gate.initialize();
        record(RECORDED);
        waiting = push("the work");
    }

    @Test
    void anApprovalOnTheCommandLineKeepsTheCommitItForwarded() throws Exception {
        final Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        final Run approval = run("approve", "--commit", waiting);

        assertThat(approval.code()).as(approval.err()).isZero();
        final PushEnd end = ends().read(RECORDED).orElseThrow();
        assertThat(end.end()).as("approved").isEqualTo(PushEnd.End.APPROVED);
        assertThat(end.commit()).as("the commit that waited").isEqualTo(waiting);
        assertThat(end.mergedCommit())
                .as("the commit the task's branch at the upstream took")
                .isEqualTo(git(upstream, "rev-parse", "refs/heads/porelo/writer"));
        assertThat(end.branch()).as("the task's branch, by default").isEqualTo("porelo/writer");
        assertThat(end.at()).as("when it was recorded").isBetween(before, Instant.now());
    }

    @Test
    void aRejectOnTheCommandLineWithNoOpenThreadIsKeptWithNone() throws Exception {
        final Run reject = run("reject");

        assertThat(reject.code()).as(reject.err()).isZero();
        final PushEnd end = ends().read(RECORDED).orElseThrow();
        assertThat(end.end()).as("rejected").isEqualTo(PushEnd.End.REJECTED);
        assertThat(end.openThreads()).as("no thread was open").isZero();
        assertThat(end.commit()).as("the commit that waited").isEqualTo(waiting);
    }

    @Test
    void aLandingKeepsTheCommitFoundOnTheBranch() throws Exception {
        // Merged elsewhere: the person pushed the work to the upstream themselves.
        git(work, "push", "-q", "origin", "HEAD:main");
        final GitGate.Landing landing = gate.landed("writer", "main");
        assertThat(landing.landed()).as(landing.detail()).isTrue();

        new GateEnds(context).landed("default", "writer", landing, "main");

        final PushEnd end = ends().read(RECORDED).orElseThrow();
        assertThat(end.end()).as("landed").isEqualTo(PushEnd.End.LANDED);
        assertThat(end.mergedCommit()).as("the commit found merged").isEqualTo(waiting);
        assertThat(end.branch()).as("on main").isEqualTo("main");
    }

    @Test
    void aLandingThatDidNotLandKeepsNothing() throws Exception {
        final GitGate.Landing landing = gate.landed("writer", "main");
        assertThat(landing.landed()).as("the upstream does not hold it").isFalse();

        new GateEnds(context).landed("default", "writer", landing, "main");

        assertThat(ends().read(RECORDED)).as("the push still waits; nothing ended").isEmpty();
    }

    @Test
    void theNextPushThatWaitsReplacesTheEndWithWhenItWasTakenAndIsStillTold() throws Exception {
        assertThat(run("reject").code()).as("rejected").isZero();
        assertThat(ends().read(RECORDED)).as("the reject is kept").isPresent();
        final String next = push("the work again");
        final List<String> told = new ArrayList<>();
        final Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        GateServeCommand.waits(context, "default", "writer", told::add).taken(next);

        final PushEnd waits = ends().read(RECORDED).orElseThrow();
        assertThat(waits.end())
                .as("the next push waits; no end stands")
                .isEqualTo(PushEnd.End.WAITING);
        assertThat(waits.commit()).as("the commit taken").isEqualTo(next);
        assertThat(waits.name()).as("the push's name").isEqualTo("writer");
        assertThat(waits.waitingSince())
                .as("when it was taken")
                .isNotNull()
                .isBetween(before, Instant.now());
        assertThat(told).as("the push is still told on").containsExactly(next);
    }

    @Test
    void anEndOfTheWaitingCommitCarriesWhenItWasTaken() throws Exception {
        GateServeCommand.waits(context, "default", "writer", commit -> {}).taken(waiting);
        final Instant taken = ends().read(RECORDED).orElseThrow().waitingSince();

        assertThat(run("reject").code()).as("rejected").isZero();

        final PushEnd end = ends().read(RECORDED).orElseThrow();
        assertThat(end.end()).as("rejected").isEqualTo(PushEnd.End.REJECTED);
        assertThat(end.waitingSince()).as("the waiting time carried along").isEqualTo(taken);
    }

    @Test
    void aTaskStartedLaterUnderTheSameNameFindsNoEndOfTheEarlierOne() throws Exception {
        assertThat(run("reject").code()).as("rejected").isZero();

        // The earlier task is removed and a new one of the same name runs in another container.
        record(LATER);
        final String next = push("the later task's work");
        GateServeCommand.waits(context, "default", "writer", commit -> {}).taken(next);

        assertThat(ends().read(LATER).orElseThrow().end())
                .as("the later container finds only its own push waiting")
                .isEqualTo(PushEnd.End.WAITING);
        assertThat(ends().read(RECORDED).orElseThrow().end())
                .as("the later task's push changes nothing of the earlier task's container")
                .isEqualTo(PushEnd.End.REJECTED);
    }

    private PushEnds ends() {
        return new GateEnds(context).store();
    }

    private void record(final String recordedId) throws Exception {
        final String container = context.tasks().containerName(project, "writer");
        final Path state = context.paths().tasks().taskRecord(container);
        Sidecar.recordId(state, recordedId);
        Files.setPosixFilePermissions(
                state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        new Sidecar(
                        Sidecar.VERSION,
                        "default",
                        "guarded",
                        "ruleset.nft",
                        "dns.conf",
                        "porelo",
                        context.paths().tasks().containerState(container).toString(),
                        "writer",
                        null,
                        null,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT)
                .writeTo(state.resolve("sidecar.json"));
    }

    private String push(final String subject) {
        git(work, "commit", "-q", "--allow-empty", "-m", subject);
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + "writer");
        return git(work, "rev-parse", "HEAD");
    }

    private Run run(final String verb, final String... extra) {
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args =
                new ArrayList<>(List.of("gate", verb, "-p", "default", "-r", "app"));
        args.addAll(List.of(extra));
        args.add("writer");
        return new Run(cli.execute(args.toArray(String[]::new)), out.toString(), err.toString());
    }

    private record Run(int code, String out, String err) {}

    private String git(final Path in, final String... args) {
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
        return gitRunner
                .runOrFail(
                        Command.of(all)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .standardOutput()
                .strip();
    }
}
