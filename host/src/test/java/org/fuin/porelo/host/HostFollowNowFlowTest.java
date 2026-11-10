package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.Timeout(5)
class HostFollowNowFlowTest {
    private static final String STATE = "/run/porelo-host/follow-now/";
    private static final String FIRST = "a".repeat(32);
    private static final String SECOND = "b".repeat(32);
    @TempDir Path directory;
    private HostReadFixture fixture;
    private HostFiles files;
    private ProjectRuntime runtime;
    private String activeJob = "0";
    private String activeInvocation = "";
    private int starts;
    private boolean finishOnObservation;
    private boolean interruptOnObservation;

    @BeforeEach
    void admitAPerson() throws Exception {
        fixture = new HostReadFixture(directory);
        fixture.person("alice", "member=alpha developer\n", "");
        files = fixture.files();
        runtime = new ProjectRuntime(files.projects(), this::program, Path::of);
    }

    private HostPrograms.Result program(
            List<String> argv, byte[] input, Map<String, String> env, Duration timeout)
            throws IOException {
        if (argv.getFirst().equals("busctl")) {
            // A follow must be able to acquire its configuration lock while the requester selects
            // it.
            try (var lock =
                    HostConfigurationLock.acquire(
                            fixture.path("/etc/porelo-host/forge.conf"), false, Duration.ZERO)) {
                starts++;
                activate("42", FIRST);
                complete("42", FIRST, 65);
                return new HostPrograms.Result(0, "o \"/org/freedesktop/systemd1/job/42\"\n");
            }
        }
        if (finishOnObservation) {
            finishOnObservation = false;
            complete("42", FIRST, 65);
        }
        if (interruptOnObservation) Thread.currentThread().interrupt();
        return new HostPrograms.Result(
                0,
                "LoadState=loaded\nType=oneshot\nActiveState="
                        + (activeJob.equals("0") ? "inactive" : "activating")
                        + "\nInvocationID="
                        + activeInvocation
                        + "\nJob="
                        + activeJob
                        + "\n");
    }

    private void activate(String job, String invocation) throws IOException {
        activeJob = job;
        activeInvocation = invocation;
        fixture.put(STATE + "jobs/" + job, invocation + "\n");
        fixture.put(STATE + "latest", job + "\n");
    }

    private void complete(String job, String invocation, int code) throws IOException {
        fixture.put(STATE + "invocations/" + invocation, code + "\n");
        if (activeJob.equals(job)) activeJob = "0";
    }

    private HostFollowNow flow() {
        return new HostFollowNow(files, runtime);
    }

    private static HostPerson person() {
        return new HostPerson("alice", 1000);
    }

    // A short pass must not be repeated by another caller that observed idle before it started.
    @Test
    void twoIdleCallersShareAPassThatFinishesDuringStart() throws Exception {
        final var flow = flow();
        final var first = flow.capture();
        final var second = flow.capture();
        assertThat(flow.follow(person(), first).exitCode())
                .as("first failed completion")
                .isEqualTo(65);
        assertThat(flow.follow(person(), second).exitCode())
                .as("overlapping idle caller joins the selected job")
                .isEqualTo(65);
        assertThat(starts).as("a short pass is not started twice").isOne();
    }

    // A timer pass during admission must satisfy the waiting person without a second update.
    @Test
    void aTimerPassDuringAuthorizationIsJoinedEvenAfterItEnds() throws Exception {
        final var flow = flow();
        final var entry = flow.capture();
        activate("42", FIRST);
        complete("42", FIRST, 65);
        assertThat(flow.follow(person(), entry).exitCode())
                .as("the timer's failure is retained")
                .isEqualTo(65);
        assertThat(starts).as("the timer already did the requested work").isZero();
    }

    // A newer success must not erase the failure selected before admission waited.
    @Test
    void theEntryInvocationSurvivesANewerSuccessfulPass() throws Exception {
        activate("42", FIRST);
        final var flow = flow();
        final var entry = flow.capture();
        complete("42", FIRST, 65);
        activate("43", SECOND);
        complete("43", SECOND, 0);
        final var completion = flow.follow(person(), entry);
        assertThat(completion.invocation())
                .as("the entry invocation remains the target")
                .isEqualTo(FIRST);
        assertThat(completion.exitCode()).as("later success cannot conceal failure").isEqualTo(65);
        assertThat(starts).as("authorization did not queue another pass").isZero();
    }

    // Completion between the receipt read and the service read must not become unavailable.
    @Test
    void aPassEndingDuringTheCompletionCheckStillReturnsItsResult() throws Exception {
        activate("42", FIRST);
        final var flow = flow();
        final var entry = flow.capture();
        finishOnObservation = true;
        assertThat(flow.follow(person(), entry).exitCode())
                .as("the final receipt is read after observing termination")
                .isEqualTo(65);
    }

    // Admission withdrawn while a request waits cannot authorize a privileged service start.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentAdmissionIsCheckedAfterTheEntrySnapshot(boolean revoked) throws Exception {
        final var flow = flow();
        final var entry = flow.capture();
        if (revoked)
            fixture.put(
                    "/etc/porelo-host/organization/applied.json",
                    "{\"commit\":\""
                            + "b".repeat(40)
                            + "\",\"projects\":[],\"persons\":[],\"revoked\":[\"alice\"]}");
        else fixture.person("alice", "member=alpha developer\n", "lock=pending\n");
        assertThatThrownBy(() -> flow.follow(person(), entry))
                .as("current admission governs the start")
                .isInstanceOfSatisfying(
                        HostRefusal.class,
                        refusal ->
                                assertThat(refusal.exitCode())
                                        .as("admission refusal")
                                        .isEqualTo(65));
        assertThat(starts).as("a stale admission cannot start a pass").isZero();
    }

    // An absent or untrusted receipt cannot stand in for a completed privileged update.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "owner", "mode", "symlink", "identity", "status", "job"})
    void unavailableOrUntrustedCompletionsNeverBecomeSuccess(String fault) throws Exception {
        activate("42", FIRST);
        complete("42", FIRST, 65);
        final var target = new HostFollowNow.Target("42", FIRST);
        final Path completion = fixture.path(STATE + "invocations/" + FIRST);
        if (fault.equals("missing")) Files.delete(completion);
        if (fault.equals("owner")) fixture.overrides.put(completion, Map.of("uid", 1000));
        if (fault.equals("mode")) fixture.overrides.put(completion, Map.of("mode", 0100660));
        if (fault.equals("symlink")) {
            final Path replacement = fixture.put(STATE + "other", "0\n");
            Files.delete(completion);
            Files.createSymbolicLink(completion, replacement);
        }
        if (fault.equals("identity")) fixture.put(STATE + "jobs/42", SECOND + "\n");
        if (fault.equals("status")) fixture.put(STATE + "invocations/" + FIRST, "256\n");
        if (fault.equals("job")) fixture.put(STATE + "jobs/42", "../escape\n");
        assertThatThrownBy(() -> flow().follow(person(), new HostFollowNow.Entry(target, null)))
                .as("no unverified completion is returned for " + fault)
                .isInstanceOf(HostRefusal.class);
        assertThat(starts).as("receipt failure never triggers replacement work").isZero();
    }

    // A stuck pass must not hold a person's command indefinitely.
    @Test
    void anUnfinishedPassExhaustsOneMonotonicBudget() throws Exception {
        activate("42", FIRST);
        final var ticks = new AtomicLong();
        final var flow =
                new HostFollowNow(
                        files,
                        runtime,
                        () -> ticks.getAndAdd(Duration.ofSeconds(5).toNanos()),
                        Duration.ofSeconds(25));
        assertThatThrownBy(
                        () ->
                                flow.follow(
                                        person(),
                                        new HostFollowNow.Entry(
                                                new HostFollowNow.Target("42", FIRST), null)))
                .as("there is no fresh budget for each poll")
                .isInstanceOfSatisfying(
                        HostRefusal.class,
                        refusal ->
                                assertThat(refusal.getMessage())
                                        .as("expiry is distinct from completion")
                                        .contains("budget expired"));
        assertThat(activeJob).as("wait expiry leaves the shared pass running").isEqualTo("42");
    }

    // Canceling one waiter must preserve interruption and leave the shared pass running.
    @Test
    void interruptionDoesNotCancelTheSharedPass() throws Exception {
        activate("42", FIRST);
        interruptOnObservation = true;
        try {
            assertThatThrownBy(
                            () ->
                                    flow().follow(
                                                    person(),
                                                    new HostFollowNow.Entry(
                                                            new HostFollowNow.Target("42", FIRST),
                                                            null)))
                    .as("interruption is a missing result, not a completed pass")
                    .isInstanceOf(HostRefusal.class);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the wait preserves cancellation")
                    .isTrue();
            assertThat(activeJob).as("another waiter can still receive completion").isEqualTo("42");
        } finally {
            Thread.interrupted();
        }
    }

    // The root service must retain failures by invocation before another pass overwrites status.
    @Test
    void theRootVerbPublishesItsOwnFailedCompletion() throws Exception {
        activeJob = "42";
        activeInvocation = FIRST;
        Files.delete(fixture.path("/etc/porelo-host/forge.conf"));
        final var err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {"org", "follow"},
                        Map.of("INVOCATION_ID", FIRST),
                        () -> 0,
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(err),
                        files,
                        env -> person(),
                        new HostSweep(files),
                        runtime,
                        java.io.InputStream.nullInputStream(),
                        java.time.Clock.systemUTC());
        assertThat(code)
                .as("a missing host configuration fails before any real program runs")
                .isEqualTo(69);
        assertThat(Files.readString(fixture.path(STATE + "jobs/42")))
                .as("the root service binds its job to its invocation")
                .isEqualTo(FIRST + "\n");
        assertThat(Files.readString(fixture.path(STATE + "invocations/" + FIRST)))
                .as("the root service retains the actual failed exit")
                .isEqualTo("69\n");
        assertThatThrownBy(() -> flow().finish(new HostFollowNow.Target("42", FIRST), 0))
                .as("a retained failure cannot be replaced with success")
                .isInstanceOf(HostRefusal.class);
        assertThat(Files.readString(fixture.path(STATE + "invocations/" + FIRST)))
                .as("conflicting publication preserves the original result")
                .isEqualTo("69\n");
    }
}
