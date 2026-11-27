package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskConversations}: what is set up for the project {@code default}. */
class TaskConversationsTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new TransportTestRunner();

    @Test
    void aConversationForDefaultIsRefusedBeforeAnyTransportRuns() throws Exception {
        // A room for 'default' gave work that nobody configures a way to reach and be reached.
        final PoreloContext context = context();
        final Project named = namedDefault();

        assertThatThrownBy(() -> new TaskConversations(context).setUp(named))
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        ex -> {
                            assertThat(ex.code()).as("refused as a setting is").isEqualTo(78);
                            assertThat(ex.getMessage())
                                    .as("the way that gives work a conversation")
                                    .contains("project repository", "project.yml");
                        });
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aStartInDefaultSaysOnceThatItHasNoRoomAndGoesOn() throws Exception {
        // Skipped without a word, a start in 'default' left a person waiting for a room it never
        // has.
        final PoreloContext context = context();
        final List<StartStep> events = new ArrayList<>();

        final String refused =
                new TaskConversations(context)
                        .enroll(
                                namedDefault(),
                                "porelo-default-fix",
                                null,
                                new StartSteps(events::add));

        assertThat(refused).as("the start goes on").isNull();
        assertThat(events)
                .as("said once, and not as a failure")
                .filteredOn(event -> event.state() == StartStep.State.STARTED)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.step()).isEqualTo(StartStep.Step.CONVERSATION);
                            assertThat(event.text()).contains(DefaultProject.NAME, "no room");
                        });
        assertThat(events).noneMatch(event -> event.state() == StartStep.State.FAILED);
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aStartOfAPlacedProjectWithNoHomeserverSaysItHasNoConversationAndGoesOn() throws Exception {
        // Refused with 78, a discussion of the organization's idea could not start at all: a
        // start without a homeserver goes on, and its agents only cannot talk to each other.
        final PoreloContext context = context();
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "demo",
                                "c0",
                                "https://forge.example/demo.git",
                                null,
                                List.of(),
                                null,
                                null,
                                null));
        final Path file = dir.resolve("demo.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                mail:
                  transports:
                    matrix: {}
                  peers:
                    people:
                      address: "matrix:"
                      trust: "external"
                """);
        final Project demo = ProjectReader.read(file);
        final List<StartStep> events = new ArrayList<>();

        final String refused =
                new TaskConversations(context)
                        .enroll(demo, "porelo-demo-idea", null, new StartSteps(events::add));

        assertThat(refused).as("the start goes on").isNull();
        assertThat(events)
                .as("said once, and not as a failure")
                .filteredOn(event -> event.state() == StartStep.State.STARTED)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.step()).isEqualTo(StartStep.Step.CONVERSATION);
                            assertThat(event.text())
                                    .contains("demo", "no homeserver", "No conversation")
                                    .as("it names the step that ends it")
                                    .contains(
                                            "a project admin",
                                            "mail.transports.matrix.homeserver",
                                            "project.yml",
                                            "if it wants one, a room",
                                            "signs it");
                        });
        assertThat(events).noneMatch(event -> event.state() == StartStep.State.FAILED);
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aNewStartThatReadsAnotherTasksWorkIsInNoConversationAndGoesOn() throws Exception {
        // Its record is written after the enrollment, so the start says what its parts are.
        final PoreloContext context = context();
        final List<StartStep> events = new ArrayList<>();

        final String refused =
                new TaskConversations(context)
                        .enroll(
                                placed(),
                                "porelo-uc-read",
                                null,
                                new StartSteps(events::add),
                                true);

        assertThat(refused).as("the start goes on").isNull();
        assertThat(events)
                .filteredOn(event -> event.state() == StartStep.State.STARTED)
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(event.text())
                                        .as("said why")
                                        .contains("another task's work", "no conversation"));
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aTaskWhoseRecordReadsAnotherTasksWorkIsInNoConversation() throws Exception {
        final PoreloContext context = context();
        RunPlace.recordParts(
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-read")),
                TaskParts.anothersWork(List.of(Right.REPLY)));

        final String refused = new TaskConversations(context).enroll(placed(), "porelo-uc-read");

        assertThat(refused).as("the start goes on").isNull();
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aTaskReadsAnotherTasksWorkByItsStateThenItsDurableRecordAndWhenNeitherReads()
            throws Exception {
        final PoreloContext context = context();
        final TaskParts reading = TaskParts.anothersWork(List.of(Right.REPLY));
        RunPlace.recordParts(
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-own")),
                TaskParts.ownWork());
        RunPlace.recordParts(
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-state")),
                reading);
        RunPlace.recordParts(
                Files.createDirectories(context.paths().tasks().taskRecord("porelo-uc-durable")),
                reading);
        final Path broken =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-broken"));
        RunPlace.recordParts(broken, reading);
        Files.writeString(broken.resolve(RunPlace.FILE), "{not json");

        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-own"))
                .as("at its own work")
                .isFalse();
        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-none"))
                .as("no record at all")
                .isFalse();
        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-state"))
                .as("by its state")
                .isTrue();
        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-durable"))
                .as("by its durable record while its state holds none")
                .isTrue();
        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-broken"))
                .as("a record that does not read, fail closed")
                .isTrue();
    }

    @Test
    void aReaderWhoseRecordsWentBeforeItsMailboxStillReadsAnotherTasksWork() throws Exception {
        // A removal forgets the records before it deletes the mailbox, and a mailbox it cannot
        // delete stays: a pass in between took what waited in it for a task at its own work.
        final PoreloContext context = context();
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-read"));
        RunPlace.recordParts(state, TaskParts.anothersWork(List.of(Right.REPLY)));
        new Mailbox(context.paths().messaging().mailbox("porelo-uc-read")).create();
        new Mailbox(context.paths().messaging().mailbox("porelo-uc-own")).create();
        ReadingTasks.mark(context, "porelo-uc-read");
        ReadingTasks.mark(context, "porelo-uc-own");
        try (java.util.stream.Stream<Path> walk = Files.walk(state)) {
            for (final Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }

        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-read"))
                .as("its mailbox says what its records said")
                .isTrue();
        assertThat(ReadingTasks.readsAnothersWork(context, "porelo-uc-own"))
                .as("a mailbox of no task that reads stays as it was")
                .isFalse();
    }

    /** A placed project with a room. */
    private Project placed() throws Exception {
        final Path file = dir.resolve("uc.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                mail:
                  transports:
                    room: {}
                  peers:
                    reviewer:
                      address: "room:"
                      trust: "vouched"
                """);
        final Project read = ProjectReader.read(file);
        assertThat(read.mail().conversations()).as("the file names a room").isNotEmpty();
        return read;
    }

    private Project namedDefault() throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "default"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                mail:
                  peers:
                    room:
                      address: "room:"
                """);
        final Project named = ProjectReader.read(file);
        assertThat(named.mail().conversations()).as("the file does name a room").isNotEmpty();
        return named;
    }

    private PoreloContext context() throws Exception {
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
        final Path adapter =
                Files.createDirectories(dir.resolve("data/porelo/transports"))
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the transport runs").isTrue();
        runner.answering(" setup ", "{\"conversation\":\"room\",\"reaches\":[]}");
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
