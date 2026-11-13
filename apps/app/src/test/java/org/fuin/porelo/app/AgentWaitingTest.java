package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentWaiting}: whether a task waits for a person, read from outside by its
 * agent's own declaration - and every way of saying "cannot tell" kept apart from "not waiting".
 */
class AgentWaitingTest {

    private static final String TASK = "porelo-p-t";

    private static final AgentDefinition DECLARED = asker("");

    /** The asker, declaring its rest as well. */
    private static final AgentDefinition RESTING =
            asker(
                    """
              at_rest:
                shows: ["ready for the next turn"]
                lacks: ["working on it"]
            """);

    private static AgentDefinition asker(final String more) {
        return AgentDefinitionReader.read(
                new StringReader(
                        """
            name: asker
            binary: asker
            git_identity: { name: A, email: a@example.com }
            headless: { prompt_flag: "-p" }
            session:
              waiting:
                screen:
                  - { contains: "Trust this folder?", in_last_lines: 3, for: "whether to trust the folder" }
                not_its_screen:
                  - { contains: "(END)", in_last_lines: 1 }
                last_message: { record: { type: result }, text: result }
            """
                                + more),
                "asker.yaml");
    }

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final MovingClock clock = new MovingClock();

    private final AtomicInteger loaded = new AtomicInteger();

    private AgentWaiting waiting(Map<String, Optional<AgentDefinition>> declarations) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_DATA_HOME".equals(name)
                                        ? dir.resolve("data").toString()
                                        : null,
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        return new AgentWaiting(
                context,
                clock,
                () -> {
                    loaded.incrementAndGet();
                    return declarations;
                });
    }

    private AgentWaiting declared() {
        return waiting(Map.of("asker", Optional.of(DECLARED), "quiet", Optional.empty()));
    }

    private AgentWaiting.Derived attached(AgentWaiting waiting) {
        return waiting.about(TASK, true, "asker", "AGENT", dir);
    }

    private long captures() {
        return runner.invocations().stream()
                .filter(command -> command.toString().contains("capture-pane"))
                .count();
    }

    @Test
    void manyTasksScreensAreReadAtOnceSoAListingWaitsForTheSlowestFewNotForAllInTurn()
            throws Exception {

        // A listing read each task's screen in turn, about 100 ms each - 3.8 s at 30 tasks.
        final java.util.concurrent.atomic.AtomicInteger now =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger most =
                new java.util.concurrent.atomic.AtomicInteger();
        final org.fuin.porelo.core.process.CommandRunner slow =
                command -> {
                    most.accumulateAndGet(now.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(200);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        now.decrementAndGet();
                    }
                    return new org.fuin.porelo.core.process.CommandResult(
                            command, 0, "stub: at work, nothing asked\n", "");
                };
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_DATA_HOME".equals(name)
                                        ? dir.resolve("data").toString()
                                        : null,
                        dir);
        final AgentWaiting waiting =
                new AgentWaiting(
                        new PoreloContext(
                                slow, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0),
                        clock,
                        () -> Map.of("asker", Optional.of(DECLARED)));
        final List<AgentWaiting.Wanted> tasks =
                java.util.stream.IntStream.range(0, 16)
                        .mapToObj(
                                i ->
                                        new AgentWaiting.Wanted(
                                                "porelo-p-" + i, true, "asker", "AGENT"))
                        .toList();

        final long started = System.nanoTime();
        waiting.readScreens(tasks);
        final java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - started);

        assertThat(most.get())
                .as("several at once, never more than the bound")
                .isGreaterThan(1)
                .isLessThanOrEqualTo(AgentWaiting.AT_ONCE);
        assertThat(took).as("16 reads of 200 ms").isLessThan(java.time.Duration.ofMillis(1600));
        final int before = now.get();
        for (final AgentWaiting.Wanted task : tasks) {
            assertThat(waiting.about(task.container(), true, "asker", "AGENT", dir).screen())
                    .isEqualTo(AgentWaiting.Screen.NOT_WAITING);
        }
        assertThat(most.get())
                .as("answered from what was read")
                .isLessThanOrEqualTo(AgentWaiting.AT_ONCE);
        assertThat(now.get()).isEqualTo(before);
    }

    // --- the screen

    @Test
    void anAttachedAgentShowingADeclaredQuestionIsWaitingAndSaysWhatFor() {
        runner.answering("capture-pane", "stub: starting\nTrust this folder? [y/N]\n\n\n");

        final AgentWaiting.Derived derived = attached(declared());

        assertThat(derived.screen()).isEqualTo(AgentWaiting.Screen.WAITING);
        assertThat(derived.waitingFor()).isEqualTo("whether to trust the folder");
    }

    @Test
    void aScreenTheTaskWroteIsReadWithoutAskingTheTask() throws Exception {

        // One read was a process in the container, 100 ms and 400-600 ms under load.
        final Path written = ScreenFile.prepare(dir);
        Files.writeString(
                written.resolve(ScreenFile.SCREEN),
                "stub: starting\nTrust this folder? [y/N]\n\n\n");
        Files.writeString(written.resolve(ScreenFile.ALIVE), "");
        Files.setLastModifiedTime(
                written.resolve(ScreenFile.ALIVE),
                java.nio.file.attribute.FileTime.from(clock.instant()));

        final AgentWaiting.Derived derived = attached(declared());

        assertThat(derived.screen()).isEqualTo(AgentWaiting.Screen.WAITING);
        assertThat(derived.waitingFor()).isEqualTo("whether to trust the folder");
        assertThat(captures()).as("no process in the task").isZero();
    }

    @Test
    void anAttachedAgentAtWorkIsNotWaiting() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.NOT_WAITING);
    }

    @Test
    void anAgentThatDeclaresNothingCannotBeToldRatherThanReadAsNotWaitingAndIsNotLookedAt() {
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");

        final AgentWaiting.Derived derived = declared().about(TASK, true, "quiet", "AGENT", dir);

        assertThat(derived.screen())
                .isEqualTo(AgentWaiting.Screen.UNDECLARED)
                .isNotEqualTo(AgentWaiting.Screen.NOT_WAITING);
        assertThat(captures()).isZero();
    }

    @Test
    void onlyAnAttachedRunningAgentHasAScreenToRead() {
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        final AgentWaiting waiting = declared();

        assertThat(waiting.about(TASK, true, "asker", "UNATTENDED", dir).screen())
                .isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(waiting.about(TASK, true, "asker", "SHELL", dir).screen())
                .isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(waiting.about(TASK, false, "asker", "AGENT", dir).screen())
                .isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(captures()).isZero();
    }

    @Test
    void aSessionThatCannotBeReadIsUnseen() {
        runner.failing("capture-pane", 1, "no server running");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
    }

    @Test
    void aPagerOverTheAgentKeepsWhatWasLastReadOfTheAgent() {
        final AgentWaiting waiting = declared();
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        assertThat(attached(waiting).screen()).isEqualTo(AgentWaiting.Screen.WAITING);

        runner.answering("capture-pane", "Trust this folder? [y/N]\nsome transcript\n(END)\n");
        clock.move(AgentWaiting.SCREEN);

        assertThat(attached(waiting).screen())
                .as("reporting on the pager would be wrong either way")
                .isEqualTo(AgentWaiting.Screen.WAITING);
    }

    @Test
    void aPagerBeforeAnythingWasReadOfTheAgentSaysNothing() {
        runner.answering("capture-pane", "some transcript\n(END)\n");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
    }

    @Test
    void readsAScreenAtMostOnceInItsIntervalWhateverAWatchAsks() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        attached(waiting);
        clock.move(Duration.ofMillis(500));
        attached(waiting);
        clock.move(Duration.ofMillis(500));
        attached(waiting);
        assertThat(captures()).isEqualTo(1);

        clock.move(AgentWaiting.SCREEN);
        attached(waiting);
        assertThat(captures()).isEqualTo(2);
    }

    @Test
    void readsTheDeclarationsOnceInTheirIntervalNotOncePerTask() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        attached(waiting);
        waiting.about("porelo-p-other", true, "asker", "AGENT", dir);
        assertThat(loaded).hasValue(1);

        clock.move(AgentWaiting.DECLARATIONS);
        attached(waiting);
        assertThat(loaded).as("an agent updated under a running daemon is read again").hasValue(2);
    }

    // --- rest, on two looks at the agent

    private static final String AT_REST = "ready for the next turn\n";

    private static final String AT_WORK = "ready for the next turn\nworking on it\n";

    /** The resting asker, its screen captured by {@code runner}. */
    private AgentWaiting resting(final org.fuin.porelo.core.process.CommandRunner captures) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_DATA_HOME".equals(name)
                                        ? dir.resolve("data").toString()
                                        : null,
                        dir);
        return new AgentWaiting(
                new PoreloContext(captures, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0),
                clock,
                () -> Map.of("asker", Optional.of(RESTING)));
    }

    private static org.fuin.porelo.core.process.CommandResult drawn(
            final org.fuin.porelo.core.process.Command command, final String screen) {
        return new org.fuin.porelo.core.process.CommandResult(command, 0, screen, "");
    }

    @Test
    void aPagerOverARestingAgentIsNoSecondLookAtIt() {

        // One look at rest, then a pager: rest was said without the agent being seen again.
        final AgentWaiting waiting = resting(runner);
        runner.answering("capture-pane", AT_REST);
        assertThat(attached(waiting).turn()).isEqualTo(AgentWaiting.Turn.WORKING);

        runner.answering("capture-pane", "some transcript\n(END)\n");
        clock.move(AgentWaiting.SCREEN);
        assertThat(attached(waiting).turn())
                .as("a pager is no look at the agent, so it cannot confirm its rest")
                .isEqualTo(AgentWaiting.Turn.WORKING);

        runner.answering("capture-pane", AT_REST);
        clock.move(AgentWaiting.SCREEN);
        assertThat(attached(waiting).turn())
                .as("a second look at the agent itself does")
                .isEqualTo(AgentWaiting.Turn.AT_REST);
    }

    @Test
    void aSlowCaptureDoesNotLetTwoLooksCloserThanTheWakesPauseConfirmRest() {

        // A capture that took three seconds was timed at its start, so the next look, right after
        // it, counted as one taken three seconds later.
        final AgentWaiting waiting =
                resting(
                        command -> {
                            if (command.toString().contains("capture-pane")) {
                                clock.move(Duration.ofSeconds(3));
                            }
                            return drawn(command, AT_REST);
                        });
        assertThat(attached(waiting).turn()).isEqualTo(AgentWaiting.Turn.WORKING);

        assertThat(attached(waiting).turn())
                .as("the second look began as the first ended, not a pause later")
                .isEqualTo(AgentWaiting.Turn.WORKING);
    }

    @Test
    void anOlderRestingCaptureFinishingLateNeverUndoesANewerLookAtWork() throws Exception {

        // Two reads of one task overlapped: the older, at rest, finished after the newer, at work,
        // and put back the rest the work had broken.
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch slowStarted =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch slowMayEnd =
                new java.util.concurrent.CountDownLatch(1);
        final AgentWaiting waiting =
                resting(
                        command -> {
                            if (!command.toString().contains("capture-pane")) {
                                return drawn(command, "");
                            }
                            final int call = calls.incrementAndGet();
                            if (call == 2) {
                                slowStarted.countDown();
                                try {
                                    slowMayEnd.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (final InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return drawn(command, call == 3 ? AT_WORK : AT_REST);
                        });
        assertThat(attached(waiting).turn()).isEqualTo(AgentWaiting.Turn.WORKING);

        clock.move(AgentWaiting.SCREEN);
        final Thread older = Thread.ofPlatform().start(() -> attached(waiting));
        assertThat(slowStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        clock.move(AgentWaiting.SCREEN);
        final Thread newer = Thread.ofPlatform().start(() -> attached(waiting));
        newer.join(500);
        slowMayEnd.countDown();
        older.join(5000);
        newer.join(5000);

        clock.move(AgentWaiting.SCREEN);
        assertThat(attached(waiting).turn())
                .as("rest begins again after the work the newer look saw")
                .isEqualTo(AgentWaiting.Turn.WORKING);
        assertThat(calls).as("every read looked once").hasValue(4);
    }

    @Test
    void aHungCaptureIsGivenUpAndNotAskedAgainByTheSameListing() {

        // One capture hung in its exec held the whole listing for the runner's ten minutes.
        final java.util.concurrent.atomic.AtomicInteger asked =
                new java.util.concurrent.atomic.AtomicInteger();
        final AgentWaiting waiting =
                resting(
                        command -> {
                            if (command.toString().contains("capture-pane")) {
                                asked.incrementAndGet();
                                try {
                                    Thread.sleep(Duration.ofSeconds(15));
                                } catch (final InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return drawn(command, AT_REST);
                        });
        final List<AgentWaiting.Wanted> tasks =
                List.of(
                        new AgentWaiting.Wanted("porelo-p-1", true, "asker", "AGENT"),
                        new AgentWaiting.Wanted("porelo-p-2", true, "asker", "AGENT"));

        final long started = System.nanoTime();
        waiting.readScreens(tasks);
        for (final AgentWaiting.Wanted task : tasks) {
            assertThat(waiting.about(task.container(), true, "asker", "AGENT", dir).turn())
                    .as("a capture given up says nothing of the agent")
                    .isEqualTo(AgentWaiting.Turn.CANNOT_SAY);
        }
        final Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took)
                .as("the listing waits one capture's limit, not the hung exec")
                .isLessThan(AgentWaiting.CAPTURE.plusSeconds(2));
        assertThat(asked).as("each task asked once, and not again while describing").hasValue(2);
    }

    @Test
    void aTaskMadeAgainUnderItsNameInheritsNoRestFromTheOneBefore() throws IOException {

        // Removed and made again between two reads, the new task was said at rest by one look.
        final AgentWaiting waiting = resting(runner);
        runner.answering("capture-pane", AT_REST);
        org.fuin.porelo.wire.Sidecar.recordId(dir, "aaaaaaaaaaaa");
        assertThat(attached(waiting).turn()).isEqualTo(AgentWaiting.Turn.WORKING);

        org.fuin.porelo.wire.Sidecar.recordId(dir, "bbbbbbbbbbbb");
        clock.move(AgentWaiting.SCREEN);
        assertThat(attached(waiting).turn())
                .as("one look at the new container")
                .isEqualTo(AgentWaiting.Turn.WORKING);
    }

    // --- a declaration that stopped fitting

    @Test
    void aDeclarationThatNeverMatchedInADayIsReportedAsUnproven() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        assertThat(attached(waiting).unproven()).isFalse();
        clock.move(AgentWaiting.UNPROVEN_AFTER);

        final AgentWaiting.Derived derived = attached(waiting);
        assertThat(derived.unproven()).isTrue();
        assertThat(derived.screen())
                .as("still a reading of the screen, not a guess")
                .isEqualTo(AgentWaiting.Screen.NOT_WAITING);
    }

    @Test
    void oneMatchIsEnoughToProveTheDeclaration() {
        final AgentWaiting waiting = declared();
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        attached(waiting);
        runner.answering("capture-pane", "stub: at work, nothing asked\n");

        clock.move(AgentWaiting.UNPROVEN_AFTER);

        assertThat(attached(waiting).unproven()).isFalse();
    }

    // --- the last message

    @Test
    void theLastMessageOfARunIsReadFromItsRecordsAndAskedIsCannotSay() throws IOException {
        Files.writeString(
                dir.resolve("task.log"),
                """
                {"type":"system","session_id":"s-1"}
                not a record
                {"type":"assistant","message":"thinking"}
                {"type":"result","result":"Which branch should I use?"}
                """);

        final AgentWaiting.Derived derived =
                declared().about(TASK, false, "asker", "UNATTENDED", dir);

        assertThat(derived.lastMessage()).isEqualTo("Which branch should I use?");
        // No agent can say yet that it stopped to ask: three values, and this is the honest one.
        assertThat(derived.asked()).isEqualTo(AgentWaiting.Asked.CANNOT_SAY);
        assertThat(derived.askedFrom()).isEmpty();
    }

    @Test
    void theNextRunReplacesTheLastMessage() throws IOException {
        final AgentWaiting waiting = declared();
        final Path log = dir.resolve("task.log");
        Files.writeString(log, "{\"type\":\"result\",\"result\":\"Which branch?\"}\n");
        assertThat(waiting.about(TASK, false, "asker", "UNATTENDED", dir).lastMessage())
                .isEqualTo("Which branch?");

        Files.writeString(log, "{\"type\":\"result\",\"result\":\"Done on main.\"}\n");
        Files.setLastModifiedTime(
                log, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(waiting.about(TASK, false, "asker", "UNATTENDED", dir).lastMessage())
                .isEqualTo("Done on main.");
    }

    @Test
    void anAgentThatDoesNotSayWhereItsLastMessageIsHasNone() throws IOException {
        Files.writeString(dir.resolve("task.log"), "{\"type\":\"result\",\"result\":\"hello\"}\n");

        assertThat(declared().about(TASK, false, "quiet", "UNATTENDED", dir).lastMessage())
                .isEmpty();
    }

    /** A clock a test moves. */
    private static final class MovingClock extends Clock {

        private volatile Instant now = Instant.parse("2026-09-29T08:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
