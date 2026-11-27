package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task that reads another task's work, started at a gate push: another task's push that waits at
 * the gate of its own project is taken as its place, and every other push is refused before
 * anything is made, with real mirrors.
 */
class AnotherTasksPushCheckTest {

    private static final String READER = "reader";

    private static final String WAITING = "foo-1";

    /** What a start that reads another task's work names: its rights, nothing else. */
    private static final TaskParts.Asked READS =
            new TaskParts.Asked(
                    Set.of(Right.REPLY, Right.OPEN_THREADS), null, null, null, false, false, false);

    /** The parts such a start records there. */
    private static final TaskParts READING = READS.atAnothersWork();

    @TempDir Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private PoreloContext context;

    private Path projectFile;

    private String head;

    @BeforeEach
    void aProjectWhosePushWaitsAndAnotherProjectWithAPushOfTheSameName() throws IOException {
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("config"),
                                        dir.resolve("data"),
                                        dir.resolve("state"),
                                        dir.resolve("run"),
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        projectFile = context.paths().projects().defaultProjectFile("demo");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(
                projectFile,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  web:
                    upstream: "https://forge.example.org/uc/web-app.git"
                """);
        final Path mirrors = dir.resolve("data").resolve("mirrors");
        head = waiting(mirrors.resolve("demo.git"), WAITING, "the work under review");
        // Another project's push under the same name: it waits, but not in this project.
        waiting(mirrors.resolve("shop.git"), WAITING, "another project's work");
        // A push named as the reading task itself, in another repository of the project.
        waiting(mirrors.resolve("demo").resolve("web.git"), READER, "work in another repository");
        // The tasks that pushed them, as their records keep them: a push no task is recorded for
        // is none a run's place reads.
        pushedBy(WAITING, "fedcba9876543210");
        pushedBy(READER, "0123456789abcdef");
    }

    /** Leaves the durable record of the task of that name that pushed, gone since. */
    private void pushedBy(final String task, final String id) throws IOException {
        final String container =
                context.tasks()
                        .containerName(
                                org.fuin.porelo.core.project.ProjectReader.read(projectFile),
                                task,
                                null);
        final Path runtime = context.paths().tasks().containerState(container);
        final Path durable = context.paths().tasks().taskRecord(container);
        org.fuin.porelo.wire.Sidecar.recordId(durable, id);
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "demo",
                        "guarded",
                        runtime.resolve("ruleset.nft").toString(),
                        runtime.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        runtime.toString(),
                        task,
                        null,
                        null,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT)
                .writeTo(durable.resolve("sidecar.json"));
    }

    @Test
    void anotherTasksPushThatWaitsInTheTasksOwnProjectIsTaken() {
        final RunPlace taken = taken(null, WAITING, READS);

        assertThat(taken)
                .as("the place of a task that reads another task's work")
                .isEqualTo(new RunPlace(READING, null, WAITING));
        assertThat(java.util.Objects.requireNonNull(taken).beginsRun())
                .as("whether its run begins there once it is made")
                .isTrue();
        assertThat(waitingHead()).as("the head that waits there").isEqualTo(head);
    }

    @Test
    void anotherProjectsPushIsRefusedAsNotInProject() {
        final RunPlaceCheck.Refusal refused = refused("shop", WAITING, READS);

        assertThat(refused.name()).as("the refusal").isEqualTo("PlaceUnreadable");
        assertThat(refused.fields().get("reason"))
                .as("why another project's waiting push is not read")
                .isEqualTo("NotInProject");
    }

    @Test
    void aNameNothingWaitsUnderIsRefusedAsNoSuchPush() {
        final RunPlaceCheck.Refusal refused = refused(null, "foo-9", READS);

        assertThat(refused.name()).as("the refusal").isEqualTo("PlaceUnreadable");
        assertThat(refused.fields().get("reason"))
                .as("why a name nothing waits under is not read")
                .isEqualTo("NoSuchPush");
    }

    @Test
    void aNameThatCouldLeaveTheMirrorsIsAParameterOfTheWrongShape() {
        final RunPlaceCheck.Refusal refused = refused(null, "../shop", READS);

        assertThat(refused.error())
                .as("a name that is no push's")
                .isEqualTo(RunPlaceCheck.INVALID_PARAMETER);
    }

    @Test
    void thePushOfTheTasksOwnNameInAnotherRepositoryIsAnotherTasksPush() {
        assertThat(taken("web", READER, READS))
                .as("read from the mirror of the repository the place names")
                .isEqualTo(new RunPlace(READING, "web", READER));
    }

    @Test
    void theTasksOwnPushIsItsOwnWorkWhateverItsRightsAndAJudgmentThereIsRefused() {
        final RunPlace own = taken(null, READER, READS);

        assertThat(java.util.Objects.requireNonNull(own).parts().readsAnothersWork())
                .as("the place decides: its own push is its own work")
                .isFalse();
        assertThat(
                        refused(
                                        null,
                                        READER,
                                        new TaskParts.Asked(
                                                Set.of(Right.REJECT),
                                                null,
                                                null,
                                                null,
                                                false,
                                                false,
                                                false))
                                .fields())
                .as("a judgment over its own work")
                .containsEntry("rule", "rejectOverOwnWork");
    }

    @Test
    void anotherTasksPushNamingNoRightsIsRefusedByTheRuleBeforeTheGateIsAsked() {
        final RunPlaceCheck.Refusal refused = refused(null, "foo-9", TaskParts.Asked.nothing());

        assertThat(refused.name()).as("the refusal").isEqualTo("StartRuleBroken");
        assertThat(refused.fields())
                .as("no default rights at another task's work, nothing waits there either")
                .containsEntry("rule", "foreignWorkNamesNoRights");
    }

    @Test
    void aTaskWithoutAGateAtAnotherTasksPushIsStillRefusedWithItsHead() {
        final RunPlaceCheck.Refusal refused =
                refused(
                        null,
                        WAITING,
                        new TaskParts.Asked(null, null, null, null, true, false, false));

        assertThat(refused.name()).as("the refusal").isEqualTo("NotTheTasksPush");
        assertThat(refused.fields().get("head")).as("the head that waits").isEqualTo(head);
    }

    @Test
    void aProjectFileThatDoesNotReadRefusesAReaderAtOnce() throws IOException {
        Files.writeString(projectFile, "project: [not, a, project\n");

        final RunPlaceCheck.Refusal refused = refused(null, WAITING, READS);

        assertThat(refused.name()).as("the refusal").isEqualTo("PlaceUnreadable");
        assertThat(refused.fields().get("reason"))
                .as("a project that does not read knows no waiting push")
                .isEqualTo("NotInProject");
    }

    private String waitingHead() {
        return RunPlace.waitingHead(context, "demo", null, WAITING);
    }

    private @Nullable RunPlace taken(
            final @Nullable String repository, final String name, final TaskParts.Asked asked) {
        return check().taken(
                        projectFile,
                        READER,
                        null,
                        asked,
                        false,
                        null,
                        new RunPlaceCheck.Place.GatePush(repository, name),
                        () -> null,
                        () -> null)
                .place();
    }

    private RunPlaceCheck.Refusal refused(
            final @Nullable String repository, final String name, final TaskParts.Asked asked) {
        final RunPlaceCheck.Refused refused =
                catchThrowableOfType(
                        RunPlaceCheck.Refused.class, () -> taken(repository, name, asked));
        assertThat(refused).as("the start at %s/%s", repository, name).isNotNull();
        return refused.refusal();
    }

    private RunPlaceCheck check() {
        return new RunPlaceCheck(
                context,
                new RunPlaceCheck.Forges() {
                    @Override
                    public Optional<ForgeInstance> of(final URI url) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean installed(final String adapter) {
                        return false;
                    }

                    @Override
                    public Optional<Boolean> holds(final String entry) {
                        return Optional.of(false);
                    }
                },
                RunPlaceCheck.PullRequestStarts.NONE);
    }

    /** A bare mirror with one commit waiting under a push's name; its commit id. */
    private String waiting(final Path mirror, final String name, final String subject)
            throws IOException {
        Files.createDirectories(mirror.getParent());
        git(dir, "init", "--bare", "-q", mirror.toString());
        final Path work = dir.resolve("work-" + System.nanoTime());
        git(dir, "init", "-q", work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        git(work, "commit", "--allow-empty", "-q", "-m", subject);
        git(work, "push", "-q", mirror.toString(), "HEAD:" + GitGate.INCOMING + name);
        return git(work, "rev-parse", "HEAD");
    }

    private String git(final Path in, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }
}
