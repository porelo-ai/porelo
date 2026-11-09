package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostProjectCancellationTest {
    @TempDir Path root;

    @Test
    void cancellationAfterOrganizationCreationRollsBackBeforeTheClientCloses() throws Exception {
        try (ProjectFixture host = new ProjectFixture(root);
                ProjectCancellation cancellation = new ProjectCancellation()) {
            final java.util.concurrent.atomic.AtomicReference<Thread> hook =
                    new java.util.concurrent.atomic.AtomicReference<>();
            host.beforeCall =
                    call -> {
                        if (call.method().equals("POST") && call.path().endsWith("/orgs")) {
                            final Thread worker =
                                    Thread.ofPlatform().start(cancellation::cancelAndWait);
                            hook.set(worker);
                            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                            while (worker.getState() != Thread.State.BLOCKED
                                    && System.nanoTime() < end) {
                                Thread.onSpinWait();
                            }
                            assertThat(worker.getState())
                                    .as(
                                            "the hook waits for the in-flight create to record its result")
                                    .isEqualTo(Thread.State.BLOCKED);
                        }
                    };
            try (var forge =
                    host.runtime.forge(
                            host.records.values(host.records.conf(), 69), host.records.conf())) {
                final var registration =
                        new ProjectRegistration(host.files, host.runtime, () -> cancellation);
                assertThatThrownBy(
                                () ->
                                        registration.add(
                                                forge,
                                                "forge",
                                                "alpha",
                                                "alice",
                                                ProjectFixture.KEY,
                                                host.records.admitted("alice")))
                        .as("canceled creation unwinds through the normal rollback")
                        .isInstanceOf(HostRefusal.class)
                        .hasMessage("input interrupted");
            }
            assertThat(host.organizations)
                    .as("the just-created organization was deleted")
                    .isEmpty();
            assertThat(java.nio.file.Files.exists(host.records.record("alpha")))
                    .as("canceled registration publishes no pin")
                    .isFalse();
            final Thread worker = hook.get();
            assertThat(worker).as("the shutdown path was exercised").isNotNull();
            worker.join(2000);
            assertThat(worker.isAlive()).as("rollback releases the shutdown waiter").isFalse();
        }
    }

    @Test
    void cancellationWaitsForTheInflightMutationAndItsRollback() throws Exception {
        try (ProjectCancellation cancellation = new ProjectCancellation()) {
            final CountDownLatch started = new CountDownLatch(1);
            final FutureTask<Void> pending =
                    new FutureTask<>(
                            () -> {
                                started.countDown();
                                cancellation.cancelAndWait();
                                return null;
                            });
            final Thread hook;
            synchronized (cancellation) {
                hook = Thread.ofPlatform().start(pending);
                assertThat(started.await(2, TimeUnit.SECONDS))
                        .as("shutdown was requested")
                        .isTrue();
                final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (hook.getState() != Thread.State.BLOCKED && System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                assertThat(hook.getState())
                        .as("shutdown cannot outlive the registration's rollback monitor")
                        .isEqualTo(Thread.State.BLOCKED);
                assertThatThrownBy(cancellation::check)
                        .as("the owner refuses the next mutation after finishing the current one")
                        .isInstanceOfSatisfying(
                                HostRefusal.class,
                                error ->
                                        assertThat(error.exitCode())
                                                .as("interrupted input is EX_USAGE")
                                                .isEqualTo(64));
                assertThat(pending.isDone())
                        .as("the hook still waits for rollback and staging cleanup")
                        .isFalse();
            }
            pending.get(2, TimeUnit.SECONDS);
            hook.join(2000);
        }
    }
}
