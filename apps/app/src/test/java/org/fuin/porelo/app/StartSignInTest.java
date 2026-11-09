package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The model credential gate runs without a container runtime or an agent process. */
class StartSignInTest {
    private static final String AGENT = FixtureAgent.name();

    private static final class SignIn implements AgentSignInRuns.Machine {
        final CountDownLatch end = new CountDownLatch(1);
        final AtomicReference<StartCredentials.State> credential =
                new AtomicReference<>(StartCredentials.State.MISSING);
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch checked = new CountDownLatch(1);
        boolean storedElsewhere;

        StartCredentials.State read() {
            final StartCredentials.State state = credential.get();
            reads.incrementAndGet();
            checked.countDown();
            return state;
        }

        @Override
        public AgentLogin.Result signIn(
                String agent, boolean force, boolean remote, Consumer<String> started) {
            started.accept("porelo-login-1000000000001");
            try {
                if (!end.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not finish its sign-in");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new AgentLogin.Result(
                    storedElsewhere
                            ? AgentLogin.Outcome.STORED
                            : AgentLogin.Outcome.NOTHING_TO_COLLECT,
                    storedElsewhere ? "beta" : "",
                    "",
                    0,
                    "");
        }

        @Override
        public Optional<String> running(String agent) {
            return Optional.empty();
        }

        @Override
        public AgentSignInRuns.State state(String agent, String provider) {
            return storedElsewhere && "beta".equals(provider)
                            || credential.get() == StartCredentials.State.PRESENT
                    ? AgentSignInRuns.State.SIGNED_IN
                    : AgentSignInRuns.State.NOT_SIGNED_IN;
        }

        AgentSignInRuns runs() {
            return new AgentSignInRuns(this, Duration.ofSeconds(2), Duration.ofMillis(5));
        }
    }

    @Test
    void aStartWaitsAndGoesOnOnlyAfterItsCredentialWasStored() throws Exception {
        // Starting before sign-in completes wastes a task that cannot authenticate.
        final SignIn stand = new SignIn();
        final AgentSignInRuns runs = stand.runs();
        runs.start(AGENT, false, false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var start =
                    executor.submit(() -> StartCredentials.check(AGENT, stand::read, runs, true));
            try {
                assertThat(stand.checked.await(2, TimeUnit.SECONDS))
                        .as("the start checked its missing credential")
                        .isTrue();
                assertThatThrownBy(() -> start.get(100, TimeUnit.MILLISECONDS))
                        .as("the start must stay behind the running sign-in")
                        .isInstanceOf(TimeoutException.class);
                stand.credential.set(StartCredentials.State.PRESENT);
            } finally {
                stand.end.countDown();
            }
            assertThat(start.get(3, TimeUnit.SECONDS))
                    .as("the stored credential lets the start continue")
                    .isNull();
            assertThat(stand.reads.get())
                    .as("the exact credential is read again after waiting")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            stand.end.countDown();
        }
    }

    private StartCredentials.Refusal afterSignIn(SignIn stand) throws Exception {
        final AgentSignInRuns runs = stand.runs();
        runs.start(AGENT, false, false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var start =
                    executor.submit(() -> StartCredentials.check(AGENT, stand::read, runs, true));
            try {
                assertThat(stand.checked.await(2, TimeUnit.SECONDS))
                        .as("the start checked its missing credential")
                        .isTrue();
                assertThatThrownBy(() -> start.get(100, TimeUnit.MILLISECONDS))
                        .as("the refusal must await the running sign-in")
                        .isInstanceOf(TimeoutException.class);
            } finally {
                stand.end.countDown();
            }
            return start.get(3, TimeUnit.SECONDS);
        } finally {
            stand.end.countDown();
        }
    }

    @Test
    void aSignInThatStoredNothingLeavesTheStartRefused() throws Exception {
        // A login exit code cannot authorize a task with no model credential.
        assertThat(afterSignIn(new SignIn()))
                .as("a sign-in that left no credential must refuse the start")
                .isEqualTo(
                        new StartCredentials.Refusal(AGENT, StartCredentials.State.MISSING, null));
    }

    @Test
    void aLockedVaultIsRefusedAsLocked() {
        // Calling a locked credential missing sends a client into another sign-in unnecessarily.
        assertThat(
                        StartCredentials.check(
                                AGENT, () -> StartCredentials.State.VAULT_LOCKED, null, true))
                .as("a locked vault must answer VaultLocked")
                .isEqualTo(
                        new StartCredentials.Refusal(
                                AGENT, StartCredentials.State.VAULT_LOCKED, null));
    }

    @Test
    void withoutTheWaitTheRefusalNamesTheRunningSignIn() {
        // A caller must learn which sign-in to attach to without creating another task.
        final SignIn stand = new SignIn();
        final AgentSignInRuns runs = stand.runs();
        runs.start(AGENT, false, false);
        try {
            assertThat(StartCredentials.check(AGENT, stand::read, runs, false))
                    .as("the refusal carries the agent and running sign-in")
                    .isEqualTo(
                            new StartCredentials.Refusal(
                                    AGENT,
                                    StartCredentials.State.MISSING,
                                    "porelo-login-1000000000001"));
        } finally {
            stand.end.countDown();
        }
    }

    @Test
    void aSignInEndingDuringTheFirstReadDoesNotLeaveAStaleRefusal() {
        // A completed sign-in must not strand a start with a stale missing-credential answer.
        final SignIn stand = new SignIn();
        final AgentSignInRuns runs = stand.runs();
        runs.start(AGENT, false, false);
        final AtomicInteger reads = new AtomicInteger();
        try {
            assertThat(
                            StartCredentials.check(
                                    AGENT,
                                    () -> {
                                        final StartCredentials.State captured =
                                                stand.credential.get();
                                        if (reads.getAndIncrement() == 0) {
                                            stand.credential.set(StartCredentials.State.PRESENT);
                                            stand.end.countDown();
                                            runs.await(AGENT);
                                        }
                                        return captured;
                                    },
                                    runs,
                                    true))
                    .as("a sign-in that ended during the read has supplied the credential")
                    .isNull();
        } finally {
            stand.end.countDown();
        }
    }

    @Test
    void aSignInForAnotherProviderDoesNotSupplyTheRequestedCredential() throws Exception {
        // An unrelated stored credential must never authorize the selected provider's task.
        final SignIn stand = new SignIn();
        stand.storedElsewhere = true;
        assertThat(afterSignIn(stand))
                .as("the exact requested credential stays missing even if another was stored")
                .isEqualTo(
                        new StartCredentials.Refusal(AGENT, StartCredentials.State.MISSING, null));
    }
}
