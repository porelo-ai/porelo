package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
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
import org.fuin.porelo.gate.UpstreamDistance;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's reject on the one path a waiting push is rejected on, with real git: the ref goes only
 * at the commit the rejecting task's run was pinned to, the pushing task's end names the rejecting
 * task, the event log names it, and the pushing task is told in the rejecting task's words.
 */
class PushRejectionTest {

    /** The pushing task's recorded container id. */
    private static final String PUSHER = "a".repeat(64);

    private static final String READER_CONTAINER = "porelo-default_reader-1";

    private static final TaskIdentity READER =
            new TaskIdentity(
                    TaskIdentity.Format.ENCODED,
                    "default",
                    "reader-1",
                    READER_CONTAINER,
                    "c".repeat(64));

    private static final String REASON = "A blank password logs in; start over.";

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

    private Mailbox mailbox;

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
        final String container = context.tasks().containerName(project, "writer");
        TaskIdentityFixture.record(context.paths(), container, "default", "writer", PUSHER);
        mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        waiting = push("the work");
        // The push waits, as the gate keeps it when it takes one.
        GateServeCommand.waits(context, "default", "writer", commit -> {}).taken(waiting);
    }

    @Test
    void aReadersRejectDiscardsThePushAndNamesTheReaderInTheEndTheLogAndTheNote() throws Exception {
        PushRejection.byTask(
                context,
                pinned(waiting),
                READER,
                REASON,
                CommentIntake.Parts.of(context).store(),
                CommentIntake.Parts.of(context).writer());

        assertThat(gate.pending()).as("no waiting ref").doesNotContain("writer");
        final PushEnd end = ends().read(PUSHER).orElseThrow();
        assertThat(end.end()).as("rejected").isEqualTo(PushEnd.End.REJECTED);
        assertThat(end.commit()).as("the commit that waited").isEqualTo(waiting);
        assertThat(end.rejectedBy()).as("the rejecting task").isEqualTo(READER_CONTAINER);
        assertThat(EventLog.of(context).entries(null))
                .as("the event log's rejected entry names the rejecting task")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.get("kind")).isEqualTo(EventLog.REJECTED);
                            assertThat(entry.get("task")).isEqualTo("writer");
                            assertThat((String) entry.get("detail")).contains(READER_CONTAINER);
                        });
        final Map<?, ?> note = note();
        assertThat(note.get("role"))
                .as("under the agents' role, never a person's")
                .isEqualTo(MessageIntake.AGENT);
        assertThat(((Map<?, ?>) note.get("metadata")).get("from"))
                .as("from the rejecting task")
                .isEqualTo("task:default:reader-1");
        assertThat(Json.write(note.get("parts")))
                .as("the reason, as the reader's words")
                .contains(REASON)
                .contains("not by a person");
    }

    @Test
    void aPushTheUpstreamHasWhenTheRejectActsIsNotDiscardedAndItsEndStays() throws Exception {
        final PushEnd before = ends().read(PUSHER).orElseThrow();
        // Measured after the reject was judged, before it acts.
        new UpstreamRecords(context.paths().projects().upstreamRecords())
                .putMeasured(
                        GateSupport.recordKey("default", "app"),
                        new UpstreamDistance.Distance(
                                0,
                                Instant.now(),
                                UpstreamDistance.Reason.MEASURED,
                                null,
                                List.of("writer"),
                                null),
                        gate.mirror());

        final ProfileRefused refused = refused(pinned(waiting));

        assertThat(refused.rule()).as("the upstream has it").isEqualTo(TaskReject.UPSTREAM);
        assertThat(gate.pending()).as("the push still waits").contains("writer");
        assertThat(ends().read(PUSHER)).as("its end unchanged").contains(before);
        assertThat(inbox()).as("nobody told").isEmpty();
    }

    @Test
    void aPushThatMovedAfterTheJudgingStaysAndItsEndIsPutBack() throws Exception {
        final PushEnd before = ends().read(PUSHER).orElseThrow();
        final String since = push("pushed after the judging");

        final ProfileRefused refused = refused(pinned(waiting));

        assertThat(refused.rule()).as("the push moved").isEqualTo(TaskReject.MOVED);
        assertThat(git(gate.mirror(), "rev-parse", GitGate.INCOMING + "writer"))
                .as("the push still waits at the commit pushed since")
                .isEqualTo(since);
        assertThat(ends().read(PUSHER))
                .as("the end written before the delete is put back")
                .contains(before);
        assertThat(inbox()).as("nobody told").isEmpty();
    }

    @Test
    void anEndThatCannotBeWrittenLeavesThePushWaiting() throws Exception {
        final Path entry = context.paths().gate().reviewComments().resolve(PUSHER);
        Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("r-x------"));
        try {
            final ProfileRefused refused = refused(pinned(waiting));

            assertThat(refused.rule())
                    .as("the rejecting task cannot be kept")
                    .isEqualTo(TaskReject.UNREADABLE);
        } finally {
            Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(git(gate.mirror(), "rev-parse", GitGate.INCOMING + "writer"))
                .as("the push still waits")
                .isEqualTo(waiting);
        assertThat(ends().read(PUSHER).orElseThrow().end())
                .as("still waiting")
                .isEqualTo(PushEnd.End.WAITING);
    }

    private ProfileRefused refused(final Push pinned) {
        final ProfileRefused refused =
                catchThrowableOfType(
                        ProfileRefused.class,
                        () ->
                                PushRejection.byTask(
                                        context,
                                        pinned,
                                        READER,
                                        REASON,
                                        CommentIntake.Parts.of(context).store(),
                                        CommentIntake.Parts.of(context).writer()));
        assertThat(refused).as("refused").isNotNull();
        return refused;
    }

    /** The push as the reader's run was pinned to it. */
    private Push pinned(final String head) {
        return new Push("default", "app", "writer", head, null, PUSHER);
    }

    private PushEnds ends() {
        return new GateEnds(context).store();
    }

    private List<Path> inbox() throws Exception {
        try (var listed = Files.list(mailbox.inboxNew())) {
            return listed.toList();
        }
    }

    private Map<?, ?> note() throws Exception {
        final List<Path> notes = inbox();
        assertThat(notes).as("exactly one note to the pushing task").hasSize(1);
        return (Map<?, ?>) Json.parseNumberTypes(Files.readString(notes.get(0)));
    }

    private String push(final String subject) {
        git(work, "commit", "-q", "--allow-empty", "-m", subject);
        git(
                work,
                "push",
                "-q",
                "-f",
                gate.mirror().toString(),
                "HEAD:" + GitGate.INCOMING + "writer");
        return git(work, "rev-parse", "HEAD");
    }

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
