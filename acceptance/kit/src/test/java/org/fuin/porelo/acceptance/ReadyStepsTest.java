package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReadyStepsTest {

    @Test
    void readsTheMarkerAndItsBoundFromWhatTheAgentDescribes() {
        assertThat(
                        ReadySteps.ready(
                                "{\"version\":1,\"definition\":{\"name\":\"a\",\"readyMarker\":\"at work\","
                                        + "\"readyWithinSeconds\":30}}"))
                .contains(new ReadySteps.Ready("at work", Duration.ofSeconds(30)));
    }

    @Test
    void refusesABoundThatIsNoWholeNumberNamingIt() {
        assertThatThrownBy(
                        () ->
                                ReadySteps.ready(
                                        "{\"definition\":{\"readyMarker\":\"at work\","
                                                + "\"readyWithinSeconds\":30.5}}"))
                .as("a fraction is never the default bound")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("readyWithinSeconds")
                .hasMessageContaining("30.5");
    }

    @Test
    void takesTheDefaultBoundWhenTheAgentStatesNone() {
        assertThat(ReadySteps.ready("{\"definition\":{\"readyMarker\":\"at work\"}}"))
                .contains(new ReadySteps.Ready("at work", ReadySteps.DEFAULT_BOUND));
    }

    @Test
    void findsNothingForAnAgentThatDeclaresNoMarker() {
        // Which makes the step say it cannot tell, rather than pass on nothing.
        assertThat(ReadySteps.ready("{\"definition\":{\"name\":\"a\"}}")).isEmpty();
    }

    @Test
    void passesOnlyOnceTheMarkerHasStayedOnTheScreenLongEnough() {
        final ReadySteps.Steady steady =
                new ReadySteps.Steady("Welcome back!", Duration.ofSeconds(3));
        final java.time.Instant start = java.time.Instant.parse("2026-09-28T14:00:00Z");

        assertThat(steady.seen("Welcome back!", start)).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(2))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(3))).isTrue();
    }

    @Test
    void startsOverWhenADialogCoversTheMarker() {
        // Measured with a real agent: its welcome drawn first, a setup wizard over it a moment
        // later.
        final ReadySteps.Steady steady =
                new ReadySteps.Steady("Welcome back!", Duration.ofSeconds(3));
        final java.time.Instant start = java.time.Instant.parse("2026-09-28T14:00:00Z");

        steady.seen("Welcome back!", start);
        assertThat(steady.seen("Setup step 1 of 5", start.plusSeconds(1))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(3))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(5))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(6))).isTrue();
    }

    @Test
    void namesTheTasksContainerAsPoreloDoes() {
        assertThat(ReadySteps.container("ready", "attended")).isEqualTo("porelo-ready_attended");
        assertThat(ReadySteps.container("porelo", "pi")).isEqualTo("porelo_pi");
        assertThat(ReadySteps.container("web-app", "fix-now")).isEqualTo("porelo-web-app_fix-now");
    }

    @Test
    void aRunThatEndsAtOnceOnARefusalHasNotEndedWithinItsBound() {
        // Only the bound was asserted, so a start refused at once - a missing key, a shut vault -
        // passed every "ends within", and a repository using the kit that asserted nothing after it
        // saw green on a run that never ran.
        assertThat(ReadySteps.whyNotEnded(0, 60)).isNull();
        assertThat(ReadySteps.whyNotEnded(ReadySteps.TIMED_OUT, 60))
                .contains("did not end within 60s");
        assertThat(ReadySteps.whyNotEnded(1, 60)).contains("exit 1");
        assertThat(ReadySteps.whyNotEnded(78, 60)).contains("exit 78");
    }

    @Test
    void boundsAnUnattendedRunWithTimeout() {
        assertThat(ReadySteps.boundedCommand("live", "pi", 90))
                .startsWith("timeout -k 10 90 porelo task start --project 'live'");
    }

    @Test
    void aRunThatIgnoresTheStopIsKilledAtItsBound() {

        // The bound sent TERM and then waited: a start that did not end on it held the step until
        // the kit's 30-minute limit, and a leg lost its time with no word of why (found on
        // 2026-10-17).
        final String bounded =
                ReadySteps.bounded("sh -c " + Shell.quote("trap '' TERM; sleep 60"), 1);

        final int exit =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(25),
                        () ->
                                new ProcessBuilder("sh", "-c", bounded)
                                        .redirectErrorStream(true)
                                        .start()
                                        .waitFor());

        assertThat(ReadySteps.whyNotEnded(exit, 1)).contains("did not end within 1s");
    }

    @Test
    void refusesABoundOfNothing() {
        assertThatThrownBy(() -> ReadySteps.boundedCommand("live", "pi", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAProviderThatIsNotAName() {
        // Quoted where it goes; refused before, so a scenario naming nonsense fails in the kit, not
        // the shell.
        assertThatThrownBy(() -> TaskSteps.aName("open router; rm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A bound that grew lets a stuck run hold its leg: 300 s let five stuck scenarios fill a
    // 30-minute step. So a bound above the cap is refused at once, by name.
    @Test
    void refusesAReadyBoundAboveTheCap() {
        assertThatThrownBy(
                        () ->
                                ReadySteps.ready(
                                        "{\"definition\":{\"readyMarker\":\"at work\","
                                                + "\"readyWithinSeconds\":300}}"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("120");
    }

    @Test
    void refusesAnUnattendedBoundAboveTheCap() {
        assertThatThrownBy(() -> ReadySteps.boundedCommand("live", "pi", 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
    }

    // A named run that does not end on the stop held the step until the kit's own limit.
    @Test
    void aNamedUnattendedRunIsKilledTenSecondsAfterItsBound() {
        assertThat(WaitingSteps.namedCommand("talk", "recall", "pi", 120))
                .startsWith("timeout -k 10 120 porelo task start 'talk' ");
    }

    @Test
    void aSessionThatNeverAppearsFailsWithWhatTheTerminalShowedThroughItsRedaction()
            throws java.io.IOException {
        // A start refused at once left a 900-second wait that ended with nothing to read.
        final Terminal terminal =
                new Terminal(
                                new java.io.ByteArrayInputStream(
                                        ("$ porelo task start --project 'live' --agent stub\r\n"
                                                        + "The task was not started: the vault holds"
                                                        + " no key s3cr3t-value for the provider\r\n$ ")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                new java.io.ByteArrayOutputStream(),
                                () -> {})
                        .showing(text -> text.replace("s3cr3t-value", "<value of KEY>"));

        final String failure = ReadySteps.noSession("porelo-live_attended", terminal);

        assertThat(failure)
                .as("the failure names the container and the wait")
                .startsWith("No session to read appeared in porelo-live_attended within 900s");
        assertThat(failure)
                .as("the failure carries the refusal task start printed")
                .contains("The task was not started: the vault holds no key");
        assertThat(failure)
                .as("the failure passes through the terminal's redaction")
                .contains("<value of KEY>")
                .doesNotContain("s3cr3t-value");
    }

    @Test
    void aSessionThatNeverAppearsWithNoTerminalOpenSaysSo() throws java.io.IOException {
        assertThat(ReadySteps.noSession("porelo-live_attended", null))
                .as("the failure says there was no terminal to show")
                .startsWith("No session to read appeared in porelo-live_attended within 900s")
                .contains("no terminal was open");
    }

    private static final ReadySteps.Ready AT_WORK =
            new ReadySteps.Ready("stub: at work", Duration.ofSeconds(30));

    private static final String START =
            "porelo task start attended --project ready --repository ready --agent stub --clearance deny";

    /** A scenario's world whose terminal shows what a typed start printed, and its end. */
    private static World startTypedAndEnded(String shown) throws java.io.IOException {
        final World world = new World();
        world.terminal(
                new Terminal(
                                new java.io.ByteArrayInputStream(
                                        shown.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                new java.io.ByteArrayOutputStream(),
                                () -> {})
                        .showing(world::redact));
        new TerminalSteps(world).iRun(START);
        return world;
    }

    @Test
    void aStartThatEndedWithoutASessionFailsAtOnceNamingItsExitAndWhatItSaid()
            throws java.io.IOException {
        // A start refused after a failed image build left no pane, and the wait ran its 900 s out.
        final World world =
                startTypedAndEnded(
                        "Error: the image could not be built: mirror.example unreachable\r\n"
                                + "\r\nporelo-kit-start-1-ended 1\r\nready$ ");
        final long began = System.nanoTime();

        final AssertionError failure =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () ->
                                org.junit.jupiter.api.Assertions.assertThrows(
                                        AssertionError.class,
                                        () ->
                                                new ReadySteps(world)
                                                        .reachesWork(
                                                                "an-agent",
                                                                "porelo-ready_attended",
                                                                AT_WORK,
                                                                java.util.Optional::empty)));

        assertThat(failure.getMessage())
                .as("the failure names the container and the start's exit")
                .contains("porelo-ready_attended")
                .contains("exit 1");
        assertThat(failure.getMessage())
                .as("the failure carries what the start printed")
                .contains("mirror.example unreachable");
        assertThat(Duration.ofNanos(System.nanoTime() - began))
                .as("the failure came within seconds of the start ending")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aSessionMadeJustBeforeTheStartEndedBadlyIsNotMissed() throws Exception {
        // The first read found no session; the start then made one and ended with exit 1.
        final World world = startTypedAndEnded("\r\nporelo-kit-start-1-ended 1\r\nready$ ");
        final java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        final ReadySteps.Pane pane =
                () ->
                        reads.getAndIncrement() == 0
                                ? java.util.Optional.empty()
                                : java.util.Optional.of("stub: at work");

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () ->
                        new ReadySteps(world)
                                .reachesWork("an-agent", "porelo-ready_attended", AT_WORK, pane));

        assertThat(reads.get())
                .as("the pane was read again after the start's end was seen")
                .isGreaterThan(1);
    }

    @Test
    void aStartThatHasNotEndedIsStillWaitedFor() throws Exception {
        final World world = startTypedAndEnded("Building the image\r\n");

        assertThat(stillWaitingAfterTwoSeconds(world))
                .as("a start still running is waited for")
                .isTrue();
    }

    @Test
    void aStartThatEndedWellIsStillWaitedForItsSession() throws Exception {
        // A detached start ends at once with exit 0, and its session may come a moment later.
        final World world = startTypedAndEnded("\r\nporelo-kit-start-1-ended 0\r\nready$ ");

        assertThat(stillWaitingAfterTwoSeconds(world))
                .as("a start that ended with exit 0 is waited for")
                .isTrue();
    }

    private static boolean stillWaitingAfterTwoSeconds(World world) throws Exception {
        final java.util.concurrent.ExecutorService runner =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> waiting =
                    runner.submit(
                            () -> {
                                new ReadySteps(world)
                                        .reachesWork(
                                                "an-agent",
                                                "porelo-ready_attended",
                                                AT_WORK,
                                                java.util.Optional::empty);
                                return null;
                            });
            try {
                waiting.get(2, java.util.concurrent.TimeUnit.SECONDS);
                return false;
            } catch (java.util.concurrent.TimeoutException stillWaiting) {
                return true;
            } catch (java.util.concurrent.ExecutionException ended) {
                return false;
            } finally {
                waiting.cancel(true);
            }
        } finally {
            runner.shutdownNow();
        }
    }

    @Test
    void aSessionThatNeverAppearsShowsOnlyTheTerminalsLastLines() throws java.io.IOException {
        final StringBuilder shown = new StringBuilder();
        for (int line = 1; line <= 100; line++) {
            shown.append("line-").append(line).append("-end\r\n");
        }
        final Terminal terminal =
                new Terminal(
                        new java.io.ByteArrayInputStream(
                                shown.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        new java.io.ByteArrayOutputStream(),
                        () -> {});

        final String failure = ReadySteps.noSession("porelo-live_attended", terminal);

        assertThat(failure).as("the last line is shown").contains("line-100-end");
        assertThat(failure).as("the fortieth line from the end is shown").contains("line-61-end");
        assertThat(failure)
                .as("a line before the last forty is left out")
                .doesNotContain("line-60-end");
    }
}
