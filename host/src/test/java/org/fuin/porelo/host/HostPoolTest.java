package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostPoolTest {
    @TempDir Path root;
    static final String MAC = "52:54:00:aa:bb:cc";
    static final String REVERT = "snapshot-revert pool-1 base --paused";
    static final String DOWN = "domif-setlink pool-1 " + MAC + " down";
    static final String UP = "domif-setlink pool-1 " + MAC + " up";
    static final String CLOCK = "domtime pool-1 --now";

    final class Pool {
        final List<String> calls = new ArrayList<>();
        final Set<String> fail = new java.util.HashSet<>();
        final StringWriter output = new StringWriter();
        final StringWriter errors = new StringWriter();
        final Path run;
        final Path list;
        final ProjectFiles files;
        final ProjectRuntime runtime;
        String state = "shut off";
        String link = "up";
        int failedClocks;
        int clockAttempts;
        int rounds;
        boolean lateAgent;
        String timedOutCommand = "";
        boolean failStateOnce;
        boolean failedSnapshotPaused;
        boolean failFirstSnapshot;
        boolean markerAbsentAtUp = true;
        Consumer<Pool> afterScan = pool -> {};
        HostPool.Lock lock;

        Pool() throws Exception {
            run = root.resolve("run/porelo-pool-revert");
            Files.createDirectories(run);
            Files.setAttribute(run.getParent(), "unix:mode", 0700);
            Files.setAttribute(run, "unix:mode", 0700);
            list = root.resolve("vms");
            Files.writeString(list, "pool-1\n");
            Files.setAttribute(list, "unix:mode", 0600);
            files = new ProjectFiles(root, (int) Files.getAttribute(root, "unix:uid"));
            lock = () -> HostConfigurationLock.acquire(root, false, Duration.ofMillis(500));
            runtime =
                    new ProjectRuntime(
                            files,
                            (argv, input, environment, timeout) -> {
                                assertThat(environment)
                                        .as("libvirt has a fixed URI and untranslated answers")
                                        .containsAllEntriesOf(HostPool.ENVIRONMENT);
                                final String command = argv.get(1);
                                calls.add(String.join(" ", argv.subList(1, argv.size())));
                                if (String.join(" ", argv.subList(1, argv.size()))
                                        .equals(timedOutCommand))
                                    throw new IOException("virsh timed out");
                                if (command.equals("domtime")) {
                                    assertThat(timeout)
                                            .as("an unanswered agent cannot hold the pool")
                                            .isEqualTo(Duration.ofSeconds(10));
                                    clockAttempts++;
                                    if (lateAgent) throw new IOException("silent agent");
                                    if (clockAttempts <= failedClocks) return result(1, "");
                                }
                                if (command.equals("domstate") && failStateOnce) {
                                    failStateOnce = false;
                                    return result(1, "");
                                }
                                if (command.equals("snapshot-revert") && failFirstSnapshot) {
                                    failFirstSnapshot = false;
                                    state = "paused";
                                    return result(1, "");
                                }
                                if (fail.contains(command)) {
                                    if (command.equals("snapshot-revert") && failedSnapshotPaused)
                                        state = "paused";
                                    return result(1, "");
                                }
                                switch (command) {
                                    case "domstate":
                                        return result(0, state + "\n");
                                    case "domiflist":
                                        return result(
                                                0,
                                                " Interface Type Source Model MAC\n---\n vnet0 network default virtio "
                                                        + MAC
                                                        + "\n");
                                    case "domif-getlink":
                                        return result(0, MAC + " " + link + "\n");
                                    case "destroy":
                                        state = "shut off";
                                        break;
                                    case "snapshot-revert":
                                        state = "paused";
                                        break;
                                    case "resume":
                                        state = "running";
                                        break;
                                    case "domif-setlink":
                                        link = argv.get(4);
                                        if (link.equals("up"))
                                            markerAbsentAtUp &=
                                                    !Files.exists(run.resolve("pool-1.off"));
                                        break;
                                    default:
                                        break;
                                }
                                return result(0, "");
                            },
                            Path::of);
        }

        HostPrograms.Result result(int code, String text) {
            return new HostPrograms.Result(code, text);
        }

        HostPool pool() {
            return new HostPool(
                    files,
                    runtime,
                    () -> {},
                    lock,
                    (started, lines) -> {
                        calls.add("event --loop --timeout 30 --event lifecycle");
                        rounds++;
                        started.run();
                        afterScan.accept(this);
                        for (String line : events) lines.accept(line);
                    },
                    new PrintWriter(output, true),
                    new PrintWriter(errors, true));
        }

        List<String> events = List.of();

        int service() throws IOException {
            return pool().service(list);
        }

        int one() throws IOException {
            return pool().one("pool-1");
        }

        void midway() throws IOException {
            Files.writeString(run.resolve("pool-1.midway"), "");
        }

        boolean midwayExists() {
            return Files.exists(run.resolve("pool-1.midway"));
        }

        boolean off() {
            return Files.exists(run.resolve("pool-1.off"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"domiflist pool-1", DOWN, "resume pool-1", UP})
    void aTimedOutRevertCommandStillDestroysTheVm(String command) throws Exception {
        final Pool pool = new Pool();
        pool.timedOutCommand = command;
        assertThatCode(() -> assertThat(pool.one()).isOne()).doesNotThrowAnyException();
        assertThat(pool.calls).contains("destroy pool-1");
        assertThat(pool.off()).isTrue();
        assertThat(pool.errors.toString()).contains("could not set the clock of pool-1");
    }

    @Test
    void aMissingVmListUsesTheServicesDiagnosticAndExitCode() throws Exception {
        final Pool pool = new Pool();
        Files.delete(pool.list);
        assertThat(pool.service()).isOne();
        assertThat(pool.errors.toString()).isEqualTo("no list of pool VMs at " + pool.list + "\n");
        assertThat(pool.calls).isEmpty();
    }

    @Test
    void oneExplicitDomainDoesNotNeedToAppearInTheServiceList() throws Exception {
        final Pool pool = new Pool();
        Files.delete(pool.list);
        pool.state = "running";
        assertThat(pool.pool().one("other_domain")).isZero();
        assertThat(pool.calls).containsExactly("domstate other_domain");
    }

    @Test
    void aRevertedVmCannotBeReachedUntilItsClockIsTheHosts() throws Exception {
        final Pool pool = new Pool();
        pool.service();
        assertThat(pool.calls)
                .as("the network is isolated before resume and the clock precedes link up")
                .containsSubsequence(REVERT, DOWN, "resume pool-1", CLOCK, UP)
                .doesNotContain("destroy pool-1");
        assertThat(pool.output.toString())
                .as("a completed revert is said")
                .contains("reverted pool-1 to its base snapshot");
    }

    @Test
    void aClockThatStaysLateAMomentIsAskedAgain() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 1;
        pool.service();
        assertThat(pool.clockAttempts)
                .as("a transient guest-agent failure is retried")
                .isEqualTo(2);
        assertThat(pool.calls)
                .as("only a corrected clock is handed out")
                .contains(UP)
                .doesNotContain("destroy pool-1");
    }

    @Test
    void aClockThatCannotBeSetSaysSoAndTheVmIsNotHandedOut() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 100;
        pool.service();
        assertThat(pool.calls)
                .as("the failed VM is stopped without raising its link")
                .contains("destroy pool-1")
                .doesNotContain(UP);
        assertThat(pool.errors.toString())
                .as("failure is visible")
                .contains("could not set the clock of pool-1", "not handed out");
        assertThat(pool.clockAttempts).as("the retry budget is bounded").isEqualTo(20);
    }

    @Test
    void aVmThatCannotBeStoppedEitherSaysItsLinkStaysDown() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 100;
        pool.fail.add("destroy");
        pool.service();
        assertThat(pool.calls).as("a failed stop never permits link up").doesNotContain(UP);
        assertThat(pool.errors.toString())
                .as("running is never claimed stopped")
                .contains("could not stop pool-1", "link stays down")
                .doesNotContain("stays off");
        assertThat(pool.midwayExists())
                .as("a failed destroy retains ownership of recovery")
                .isTrue();
    }

    @Test
    void aLinkThatCannotGoDownGivesNoVmReachableWithTheOldClock() throws Exception {
        final Pool pool = new Pool();
        pool.fail.add("domif-setlink");
        pool.service();
        assertThat(pool.calls)
                .as("failure to isolate never resumes the VM")
                .contains("destroy pool-1")
                .doesNotContain("resume pool-1");
    }

    @Test
    void aVmWhoseClockCannotBeSetIsNotRevertedOverAndOver() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 100;
        pool.events = List.of("event 'lifecycle' for domain 'pool-1': Stopped Destroyed");
        pool.service();
        assertThat(pool.calls.stream().filter(REVERT::equals))
                .as("its own stop does not restart the failed revert")
                .hasSize(1);
    }

    @Test
    void aRestartedServiceTriesAVmLeftOffAgain() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 100;
        pool.service();
        pool.service();
        assertThat(pool.calls.stream().filter(REVERT::equals))
                .as("a service restart resets the off budget")
                .hasSize(2);
    }

    @Test
    void aStoppedVmIsRevertedWhenTheWatchSaysSo() throws Exception {
        final Pool pool = new Pool();
        pool.state = "running";
        pool.afterScan = p -> p.state = "shut off";
        pool.events = List.of("event 'lifecycle' for domain 'pool-1': Stopped Shutdown");
        pool.service();
        assertThat(pool.calls)
                .as("the stop event is acted on after the initial census")
                .contains(REVERT);
        assertThat(pool.rounds).as("one live watch is used").isOne();
    }

    @Test
    void aRetryClearsTheOldFailureBeforeItsLinkComesUp() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 20;
        pool.service();
        pool.one();
        assertThat(pool.markerAbsentAtUp)
                .as("a renter must never inherit the previous failure's marker")
                .isTrue();
        assertThat(pool.off()).as("successful retry clears the old failure").isFalse();
    }

    @Test
    void aFailedRevertStillSaysSoAndAsksNoClock() throws Exception {
        final Pool pool = new Pool();
        pool.fail.add("snapshot-revert");
        pool.service();
        assertThat(pool.errors.toString())
                .as("snapshot failure is explicit")
                .contains("could not revert pool-1");
        assertThat(pool.calls).as("a failed restore never asks the clock").doesNotContain(CLOCK);
        assertThat(pool.midwayExists())
                .as("libvirt may have restored despite the client failure")
                .isTrue();
    }

    @Test
    void aRevertLeftMidwayByAStoppedServiceIsDoneAgain() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "running";
        pool.link = "down";
        pool.service();
        assertThat(pool.calls)
                .as("only the marked incomplete revert is stopped and restored")
                .containsSubsequence("destroy pool-1", REVERT, UP);
    }

    @Test
    void aPausedVmLeftByAStoppedServiceIsDoneAgain() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "paused";
        pool.service();
        assertThat(pool.calls)
                .as("a restore paused by the previous service is recovered")
                .containsSubsequence("destroy pool-1", REVERT);
    }

    @Test
    void aPausedVmNoRevertLeftIsNotDestroyed() throws Exception {
        final Pool pool = new Pool();
        pool.state = "paused";
        pool.service();
        assertThat(pool.calls)
                .as("libvirt may have paused a renter on a disk error")
                .doesNotContain("destroy pool-1", REVERT);
    }

    @Test
    void aVmWithItsLinkDownNoRevertLeftIsNotDestroyed() throws Exception {
        final Pool pool = new Pool();
        pool.state = "running";
        pool.link = "down";
        pool.service();
        assertThat(pool.calls)
                .as("unmarked renter work is never the service's to discard")
                .doesNotContain("destroy pool-1");
    }

    @Test
    void aRunningVmWithItsLinkUpIsLeftAlone() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "running";
        pool.service();
        assertThat(pool.calls)
                .as("a completed restore is no cause to destroy a rented VM")
                .doesNotContain("destroy pool-1", REVERT);
        assertThat(pool.midwayExists()).as("the stale note is cleared").isFalse();
    }

    @Test
    void aRevertThatCannotRecordItselfDoesNotBegin() throws Exception {
        final Pool pool = new Pool();
        Files.delete(pool.run);
        pool.pool().settle("pool-1", false);
        assertThat(pool.calls)
                .as("recovery ownership is durable before libvirt changes")
                .doesNotContain(REVERT);
        assertThat(pool.errors.toString())
                .as("the refusal names the affected VM")
                .contains("could not note the revert of pool-1");
    }

    @Test
    void aLinkThatCannotBeReadKeepsTheNoteOfARevertLeftMidway() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "running";
        pool.fail.add("domif-getlink");
        pool.service();
        assertThat(pool.midwayExists())
                .as("an unanswered link question preserves the recovery note")
                .isTrue();
        assertThat(pool.calls)
                .as("an unknown link cannot justify destroying a VM")
                .doesNotContain("destroy pool-1");
        assertThat(pool.errors.toString())
                .as("unknown state is said")
                .contains("could not read how pool-1 stands");
    }

    @Test
    void aNicThatCannotBeReadKeepsTheNoteOfARevertLeftMidway() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "running";
        pool.fail.add("domiflist");
        assertThat(pool.one()).as("up refuses an unreadable NIC").isOne();
        assertThat(pool.midwayExists()).as("the next pass still owns recovery").isTrue();
    }

    @Test
    void aRevertThatFailedButLeftTheVmPausedIsDoneAgain() throws Exception {
        final Pool pool = new Pool();
        pool.failFirstSnapshot = true;
        pool.afterScan =
                p -> p.events = p.rounds == 1 ? List.of("event loop timed out") : List.of();
        pool.service();
        assertThat(pool.calls)
                .as("the next round recovers an ambiguous libvirt restore")
                .containsSubsequence(REVERT, "destroy pool-1", REVERT, UP);
    }

    @Test
    void aStateReadThatFailsOnceKeepsTheNoteOfARevertLeftMidway() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "paused";
        pool.failStateOnce = true;
        assertThat(pool.one()).as("up refuses an unknown state").isOne();
        assertThat(pool.midwayExists())
                .as("one failed read does not erase recovery ownership")
                .isTrue();
        assertThat(pool.calls)
                .as("an unanswered question never destroys work")
                .doesNotContain("destroy pool-1");
    }

    @Test
    void aStateThatCannotBeReadFailsUp() throws Exception {
        final Pool pool = new Pool();
        pool.fail.add("domstate");
        assertThat(pool.one()).as("no state answer cannot mean nothing to do").isOne();
        assertThat(pool.errors.toString())
                .as("the failed read is explicit")
                .contains("could not read the state of pool-1");
    }

    @Test
    void aLockHeldTooLongIsSaidAndFailsUp() throws Exception {
        final Pool pool = new Pool();
        pool.lock =
                () -> {
                    throw new IOException("busy lock");
                };
        assertThat(pool.one()).as("up does not wait forever").isOne();
        assertThat(pool.errors.toString())
                .as("lock contention names the next waiting VM")
                .contains("another revert", "pool-1 waits");
    }

    @Test
    void aStopTheWatchMissedIsFoundByItsNextRound() throws Exception {
        final Pool pool = new Pool();
        pool.state = "running";
        pool.afterScan =
                p -> {
                    p.state = "shut off";
                    p.events = p.rounds == 1 ? List.of("event loop timed out") : List.of();
                };
        pool.service();
        assertThat(pool.calls.stream().filter(REVERT::equals))
                .as("the next census recovers a stop without an event")
                .hasSize(1);
        assertThat(pool.errors.toString())
                .as("a subsequently dead watch is not hidden")
                .contains("virsh event ended");
    }

    @Test
    void aWatchThatFailsEndsTheService() throws Exception {
        final Pool pool = new Pool();
        assertThat(pool.service()).as("systemd must restart a dead watch").isOne();
        assertThat(pool.rounds).as("a dead watch never spins").isOne();
    }

    @Test
    void anAgentThatNeverAnswersDoesNotHoldUpThePool() throws Exception {
        final Pool pool = new Pool();
        pool.lateAgent = true;
        pool.service();
        assertThat(pool.clockAttempts)
                .as("every unanswered agent call has the ten-second bound")
                .isEqualTo(20);
        assertThat(pool.errors.toString())
                .as("the pool reports the isolated failed VM")
                .contains("could not set the clock of pool-1");
    }

    @Test
    void twoRevertsOfThePoolTakeTurns() throws Exception {
        final Pool pool = new Pool();
        final CountDownLatch attempted = new CountDownLatch(1);
        pool.lock =
                () -> {
                    attempted.countDown();
                    return HostConfigurationLock.acquire(root, false, Duration.ofSeconds(2));
                };
        final FutureTask<Integer> running = new FutureTask<>(pool::one);
        try (var held = HostConfigurationLock.acquire(root, false)) {
            Thread.ofPlatform().start(running);
            assertThat(attempted.await(1, TimeUnit.SECONDS))
                    .as("the second caller has reached the shared lock")
                    .isTrue();
            assertThat(running.isDone())
                    .as("the same directory inode serializes both callers")
                    .isFalse();
            assertThat(pool.calls)
                    .as("no libvirt operation runs before acquiring the lock")
                    .isEmpty();
        }
        assertThat(running.get(5, TimeUnit.SECONDS))
                .as("the waiting revert succeeds after release")
                .isZero();
        assertThat(pool.calls).as("the revert eventually runs").contains(REVERT);
    }

    @Test
    void oneVmForUpFailsLoudAndWatchesNothing() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 100;
        assertThat(pool.one()).as("up must see a failed correction").isOne();
        assertThat(pool.errors.toString())
                .as("up receives the clock diagnostic")
                .contains("could not set the clock of pool-1");
        assertThat(pool.rounds).as("--one never starts a watch").isZero();
    }

    @Test
    void oneVmForUpDoesAgainWhatAStoppedServiceLeftMidway() throws Exception {
        final Pool pool = new Pool();
        pool.midway();
        pool.state = "paused";
        assertThat(pool.one()).as("up recovers a marked paused restore").isZero();
        assertThat(pool.calls)
                .as("ownership note permits recovery")
                .containsSubsequence("destroy pool-1", REVERT);
    }

    @Test
    void oneVmForUpLeavesARunningVmAlone() throws Exception {
        final Pool pool = new Pool();
        pool.state = "running";
        assertThat(pool.one()).as("a VM already handed out needs no restore").isZero();
        assertThat(pool.calls).as("up never destroys renter work").doesNotContain(REVERT);
        assertThat(pool.output.toString())
                .as("no act is falsely claimed")
                .doesNotContain("reverted");
    }

    @Test
    void oneVmForUpTriesAVmLeftOffAgainAndClearsIt() throws Exception {
        final Pool pool = new Pool();
        pool.failedClocks = 20;
        pool.service();
        assertThat(pool.off()).as("the first failure is marked").isTrue();
        assertThat(pool.one()).as("up explicitly retries a failed VM").isZero();
        assertThat(pool.off()).as("a recovered VM is eligible for its next return").isFalse();
        assertThat(pool.calls).as("the recovered VM is reachable").contains(UP);
    }
}
