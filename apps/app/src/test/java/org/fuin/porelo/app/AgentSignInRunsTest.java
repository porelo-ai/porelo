package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** One sign-in per agent, started by the daemon, and the wait a client takes on it. */
class AgentSignInRunsTest {

    /** Long enough that a wait answering "at once" cannot have waited it out. */
    private static final Duration LONG = Duration.ofHours(1);

    /** A machine whose sign-in runs until the test lets it end, with the result the test sets. */
    private static final class Stand implements AgentSignInRuns.Machine {

        final AtomicInteger signIns = new AtomicInteger();

        final CountDownLatch end = new CountDownLatch(1);

        /** Let go before the container is named, as an image build ends. */
        volatile CountDownLatch building = new CountDownLatch(0);

        /** Thrown instead of signing in, as a refusal of the account does. */
        final AtomicReference<Error> throwing = new AtomicReference<>();

        final AtomicReference<AgentLogin.Outcome> outcome =
                new AtomicReference<>(AgentLogin.Outcome.STORED);

        final AtomicReference<AgentSignInRuns.State> state =
                new AtomicReference<>(AgentSignInRuns.State.NOT_SIGNED_IN);

        /** The provider the vault holds a sign-in for, other than the default. */
        final AtomicReference<String> storedFor = new AtomicReference<>("");

        final AtomicReference<Optional<String>> fromTerminal =
                new AtomicReference<>(Optional.empty());

        @Override
        public AgentLogin.Result signIn(
                final String agent,
                final boolean force,
                final boolean remote,
                final Consumer<String> started) {
            signIns.incrementAndGet();
            if (throwing.get() != null) {
                throw throwing.get();
            }
            if (outcome.get() == AgentLogin.Outcome.RUNNING) {
                try {
                    end.await(1, TimeUnit.MINUTES);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new AgentLogin.Result(outcome.get(), "", "", 0, "");
            }
            if (outcome.get() == AgentLogin.Outcome.UNSUPPORTED) {
                return new AgentLogin.Result(outcome.get(), "", "", 0, "no login declared");
            }
            try {
                building.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            started.accept("porelo-login-1000000000001");
            try {
                end.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new AgentLogin.Result(outcome.get(), storedFor.get(), "", 0, "");
        }

        @Override
        public Optional<String> running(final String agent) {
            return fromTerminal.get();
        }

        @Override
        public AgentSignInRuns.State state(final String agent, final String provider) {
            return provider != null && provider.equals(storedFor.get())
                    ? AgentSignInRuns.State.SIGNED_IN
                    : state.get();
        }
    }

    @Test
    void aSecondStartWhileOneRunsAnswersThatOneAndStartsNoOther() {
        final Stand stand = new Stand();
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        try {
            final AgentSignInRuns.Started first = signIns.start("example", false, false);
            final AgentSignInRuns.Started second = signIns.start("example", false, false);

            assertThat(first)
                    .as("the first start runs the login and names its container")
                    .isEqualTo(new AgentSignInRuns.Started("porelo-login-1000000000001", true));
            assertThat(second)
                    .as("a second start is told the running one, to attach to it")
                    .isEqualTo(new AgentSignInRuns.Started("porelo-login-1000000000001", false));
            assertThat(stand.signIns.get())
                    .as("a second sign-in would be a second authorization of the account")
                    .isEqualTo(1);
        } finally {
            stand.end.countDown();
        }
    }

    @Test
    void aStartWhileASignInFromATerminalRunsAnswersThatOne() {
        final Stand stand = new Stand();
        stand.fromTerminal.set(Optional.of("porelo-login-1000000000009"));
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));

        assertThat(signIns.start("example", false, false))
                .as("the terminal's sign-in, found by its lock")
                .isEqualTo(new AgentSignInRuns.Started("porelo-login-1000000000009", false));
        assertThat(stand.signIns.get()).as("nothing was started beside it").isZero();
    }

    @Test
    void aLoginThatRefusesIsAnsweredWithItsOutcome() {
        final Stand stand = new Stand();
        stand.outcome.set(AgentLogin.Outcome.UNSUPPORTED);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));

        assertThatThrownBy(() -> signIns.start("example", false, false))
                .as("a refusal is a value a window renders, never a container to attach to")
                .isInstanceOfSatisfying(
                        AgentSignInRuns.Refused.class,
                        refused ->
                                assertThat(refused.outcome())
                                        .isEqualTo(AgentLogin.Outcome.UNSUPPORTED));
    }

    @Test
    void aWaitEndsWithTheSignInAndReadsTheVaultNotTheLogin() {
        final Stand stand = new Stand();
        stand.outcome.set(AgentLogin.Outcome.NOTHING_TO_COLLECT);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        signIns.start("example", false, false);
        // Stored by the watch while the agent's own session went on, which then exited badly.
        stand.state.set(AgentSignInRuns.State.SIGNED_IN);
        stand.end.countDown();

        final AgentSignInRuns.Ended ended =
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> signIns.await("example"));

        assertThat(ended)
                .as("stored is what the vault holds once the sign-in ended")
                .isEqualTo(new AgentSignInRuns.Ended(true, ""));
    }

    @Test
    void aWaitAfterASignInThatStoredNothingAnswersAtOnceWithItsOutcome() {
        final Stand stand = new Stand();
        stand.outcome.set(AgentLogin.Outcome.NOTHING_TO_COLLECT);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        signIns.start("example", false, false);
        stand.end.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> signIns.await("example"));

        final AgentSignInRuns.Ended again =
                assertTimeoutPreemptively(Duration.ofSeconds(30), () -> signIns.await("example"));

        assertThat(again)
                .as("a client that asks after the end hears it, instead of waiting out the limit")
                .isEqualTo(new AgentSignInRuns.Ended(false, "NOTHING_TO_COLLECT"));
    }

    @Test
    void aWaitWithNoneRunningAndNothingStoredAnswersAtOnce() {
        final AgentSignInRuns signIns =
                new AgentSignInRuns(new Stand(), LONG, Duration.ofMillis(10));

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30), () -> signIns.await("example")))
                .as("nothing to wait for is said, not waited out")
                .isEqualTo(new AgentSignInRuns.Ended(false, "NONE_RUNNING"));
    }

    @Test
    void aWaitOnASignInFromATerminalEndsWhenItsLockIsFree() {
        final Stand stand = new Stand();
        stand.fromTerminal.set(Optional.of("porelo-login-1000000000009"));
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            stand.state.set(AgentSignInRuns.State.SIGNED_IN);
                            stand.fromTerminal.set(Optional.empty());
                        });

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30), () -> signIns.await("example")))
                .as("a start waiting on a terminal's sign-in goes on once it stored")
                .isEqualTo(new AgentSignInRuns.Ended(true, ""));
    }

    @Test
    void aVaultLockedWhenTheSignInEndsIsSaidAsLockedNotAsMissing() {
        final Stand stand = new Stand();
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        signIns.start("example", false, false);
        stand.state.set(AgentSignInRuns.State.VAULT_LOCKED);
        stand.end.countDown();

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30), () -> signIns.await("example")))
                .as("a locked vault sends the person to unlock it, not to sign in again")
                .isEqualTo(new AgentSignInRuns.Ended(false, "VAULT_LOCKED"));
    }

    private static <T> java.util.concurrent.CompletableFuture<T> later(
            final java.util.function.Supplier<T> call) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                call, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    private static void until(final java.util.function.BooleanSupplier ready) {
        assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> {
                    while (!ready.getAsBoolean()) {
                        Thread.sleep(10);
                    }
                });
    }

    @Test
    void aWaitReadsTheProviderTheSignInStoredForNotTheDefault() {
        // Read under the default, a sign-in kept for another provider was said to store nothing.
        final Stand stand = new Stand();
        stand.storedFor.set("beta");
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        signIns.start("example", false, false);
        stand.end.countDown();

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30), () -> signIns.await("example")))
                .as("stored, under the provider the agent's sign-in named")
                .isEqualTo(new AgentSignInRuns.Ended(true, ""));
    }

    @Test
    void aSecondStartWhileTheImageIsBuiltIsGivenTheContainerOnceItRuns() throws Exception {
        // Answered before the container existed, a window had nothing to attach to.
        final Stand stand = new Stand();
        stand.building = new CountDownLatch(1);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        try {
            final var first = later(() -> signIns.start("example", false, false));
            until(() -> stand.signIns.get() == 1);
            final var second = later(() -> signIns.start("example", false, false));
            Thread.sleep(100);
            stand.building.countDown();

            assertThat(second.get(30, TimeUnit.SECONDS))
                    .as("the running one's container, once it runs")
                    .isEqualTo(new AgentSignInRuns.Started("porelo-login-1000000000001", false));
            assertThat(first.get(30, TimeUnit.SECONDS).started())
                    .as("the first started it")
                    .isTrue();
        } finally {
            stand.building.countDown();
            stand.end.countDown();
        }
    }

    @Test
    void aStartWhileATerminalPreparesItsSignInIsGivenItsContainerOnceNamed() {
        final Stand stand = new Stand();
        stand.fromTerminal.set(Optional.of(""));
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            stand.fromTerminal.set(Optional.of("porelo-login-1000000000009"));
                        });

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30),
                                () -> signIns.start("example", false, false)))
                .as("a container to attach to, not an empty name")
                .isEqualTo(new AgentSignInRuns.Started("porelo-login-1000000000009", false));
    }

    @Test
    void aRefusedStartLeavesTheLastSignInsEndAsItWas() {
        // A refusal was kept as the last sign-in's end, so a wait answered a sign-in never ran.
        final Stand stand = new Stand();
        stand.outcome.set(AgentLogin.Outcome.NOTHING_TO_COLLECT);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        signIns.start("example", false, false);
        stand.end.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> signIns.await("example"));
        stand.outcome.set(AgentLogin.Outcome.UNSUPPORTED);
        assertThatThrownBy(() -> signIns.start("example", false, true))
                .isInstanceOf(AgentSignInRuns.Refused.class);

        assertThat(
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(30), () -> signIns.await("example")))
                .as("the sign-in that ran is the one answered")
                .isEqualTo(new AgentSignInRuns.Ended(false, "NOTHING_TO_COLLECT"));
    }

    @Test
    void aWaitOnAStartThatFoundATerminalsSignInWaitsForThatOne() throws Exception {
        // Ended with the start that never ran, the wait let a start go on while the terminal's
        // sign-in was still under way.
        final Stand stand = new Stand();
        stand.outcome.set(AgentLogin.Outcome.RUNNING);
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));
        final var start = later(() -> signIns.start("example", false, false));
        until(() -> stand.signIns.get() == 1);
        final var waited = later(() -> signIns.await("example"));
        Thread.sleep(100);
        stand.fromTerminal.set(Optional.of("porelo-login-1000000000009"));
        stand.end.countDown();
        start.get(30, TimeUnit.SECONDS);
        Thread.sleep(100);
        stand.state.set(AgentSignInRuns.State.SIGNED_IN);
        stand.fromTerminal.set(Optional.empty());

        assertThat(waited.get(30, TimeUnit.SECONDS))
                .as("it ends with the terminal's sign-in, stored")
                .isEqualTo(new AgentSignInRuns.Ended(true, ""));
    }

    @Test
    void aSignInThatThrowsAnErrorEndsItsStartAndItsWait() {
        // The run's thread died, its start waited the whole limit and the agent's next one with it.
        final Stand stand = new Stand();
        stand.throwing.set(new AssertionError("an account the agent refuses"));
        final AgentSignInRuns signIns = new AgentSignInRuns(stand, LONG, Duration.ofMillis(10));

        assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () ->
                        assertThatThrownBy(() -> signIns.start("example", false, false))
                                .as("refused, not left to run out")
                                .isInstanceOf(AgentSignInRuns.Refused.class));
        assertThat(signIns.running("example")).as("nothing is left running").isEmpty();
    }
}
