package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReadyStepsTest {

    @Test
    void readsTheMarkerAndItsBoundFromWhatTheAgentDescribes() {
        assertThat(ReadySteps.ready("{\"version\":1,\"definition\":{\"name\":\"a\",\"readyMarker\":\"at work\","
                + "\"readyWithinSeconds\":30}}"))
                .contains(new ReadySteps.Ready("at work", Duration.ofSeconds(30)));
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
        final ReadySteps.Steady steady = new ReadySteps.Steady("Welcome back!", Duration.ofSeconds(3));
        final java.time.Instant start = java.time.Instant.parse("2026-09-28T14:00:00Z");

        assertThat(steady.seen("Welcome back!", start)).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(2))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(3))).isTrue();
    }

    @Test
    void startsOverWhenADialogCoversTheMarker() {
        // Measured with a real agent: its welcome drawn first, a setup wizard over it a moment later.
        final ReadySteps.Steady steady = new ReadySteps.Steady("Welcome back!", Duration.ofSeconds(3));
        final java.time.Instant start = java.time.Instant.parse("2026-09-28T14:00:00Z");

        steady.seen("Welcome back!", start);
        assertThat(steady.seen("Setup step 1 of 5", start.plusSeconds(1))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(3))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(5))).isFalse();
        assertThat(steady.seen("Welcome back!", start.plusSeconds(6))).isTrue();
    }

    @Test
    void namesTheTasksContainerAsPoreloDoes() {
        assertThat(ReadySteps.container("ready", "attended")).isEqualTo("porelo-ready-attended");
    }

    @Test
    void aRunThatEndsAtOnceOnARefusalHasNotEndedWithinItsBound() {
        // Only the bound was asserted, so a start refused at once - a missing key, a shut vault - passed every
        // "ends within", and a repository using the kit that asserted nothing after it saw green on a run that never ran.
        assertThat(ReadySteps.whyNotEnded(0, 60)).isNull();
        assertThat(ReadySteps.whyNotEnded(ReadySteps.TIMED_OUT, 60)).contains("did not end within 60s");
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

        // The bound sent TERM and then waited: a start that did not end on it held the step until the kit's
        // 30-minute limit, and a leg lost its time with no word of why (found on 2026-10-17).
        final String bounded = ReadySteps.bounded("sh -c " + Shell.quote("trap '' TERM; sleep 60"), 1);

        final int exit = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(25),
                () -> new ProcessBuilder("sh", "-c", bounded).redirectErrorStream(true).start().waitFor());

        assertThat(ReadySteps.whyNotEnded(exit, 1)).contains("did not end within 1s");
    }

    @Test
    void refusesABoundOfNothing() {
        assertThatThrownBy(() -> ReadySteps.boundedCommand("live", "pi", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAProviderThatIsNotAName() {
        // Quoted where it goes; refused before, so a scenario naming nonsense fails in the kit, not the shell.
        assertThatThrownBy(() -> TaskSteps.aName("open router; rm")).isInstanceOf(IllegalArgumentException.class);
    }
}
