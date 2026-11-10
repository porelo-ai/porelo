package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.Timeout(5)
class HostFollowNowTest {
    private static final String INVOCATION = "a".repeat(32);
    private static final String STATE = "/run/porelo-host/follow-now/";
    @TempDir Path directory;
    private HostReadFixture fixture;
    private volatile boolean running;
    private boolean autoComplete;
    private Runnable beforePerson = () -> {};
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch observed = new CountDownLatch(1);

    @BeforeEach
    void admitAPerson() throws Exception {
        fixture = new HostReadFixture(directory);
        fixture.person("alice", "member=alpha developer\n", "");
    }

    private HostPrograms.Result program(
            List<String> argv,
            byte[] input,
            Map<String, String> environment,
            java.time.Duration timeout)
            throws java.io.IOException {
        assertThat(input).as("no caller input reaches systemd").isEmpty();
        assertThat(environment)
                .as("no caller environment reaches systemd")
                .isEqualTo(ProjectRuntime.SAFE_ENV);
        assertThat(timeout).as("each systemd call is bounded").isPositive();
        if (argv.getFirst().equals("busctl")) {
            assertThat(argv)
                    .as("only the fixed service start is authorized")
                    .containsExactly(
                            "busctl",
                            "--system",
                            "call",
                            "org.freedesktop.systemd1",
                            "/org/freedesktop/systemd1",
                            "org.freedesktop.systemd1.Manager",
                            "StartUnit",
                            "ss",
                            "porelo-host-follow.service",
                            "fail");
            starts.incrementAndGet();
            fixture.put(STATE + "jobs/42", INVOCATION + "\n");
            running = true;
            if (autoComplete) {
                fixture.put(STATE + "invocations/" + INVOCATION, "65\n");
                running = false;
            }
            started.countDown();
            return new HostPrograms.Result(0, "o \"/org/freedesktop/systemd1/job/42\"\n");
        }
        assertThat(argv)
                .as("status reads cannot choose another unit")
                .containsExactly(
                        "systemctl",
                        "--system",
                        "show",
                        "--all",
                        "--property=LoadState,Type,ActiveState,InvocationID,Job",
                        "porelo-host-follow.service");
        final boolean active = running;
        if (active && Thread.currentThread().getName().equals("follow-2")) observed.countDown();
        return new HostPrograms.Result(
                0,
                "LoadState=loaded\nType=oneshot\nActiveState="
                        + (active ? "activating" : "inactive")
                        + "\nInvocationID="
                        + (active ? INVOCATION : "")
                        + "\nJob="
                        + (active ? "42" : "0")
                        + "\n");
    }

    private HostReadFixture.Answer run(Map<String, String> environment, String... args) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final var files = fixture.files();
        final var runtime = new ProjectRuntime(files.projects(), this::program, Path::of);
        final int code =
                PoreloHost.run(
                        args,
                        environment,
                        () -> 0,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files,
                        env -> {
                            beforePerson.run();
                            return HostPerson.from(
                                    env, login -> login + ":x:1000:1000::/nonexistent:/bin/sh\n");
                        },
                        new HostSweep(files),
                        runtime,
                        new ByteArrayInputStream(new byte[0]),
                        Clock.systemUTC());
        return new HostReadFixture.Answer(code, out.toString(), err.toString());
    }

    private FutureTask<HostReadFixture.Answer> request() {
        final var result = new FutureTask<>(() -> run(HostReadFixture.SUDO, "org", "follow-now"));
        Thread.ofPlatform().daemon().name("follow-" + requests.incrementAndGet()).start(result);
        return result;
    }

    private void complete(int code) throws Exception {
        fixture.put(STATE + "invocations/" + INVOCATION, Integer.toString(code) + "\n");
        running = false;
    }

    // Duplicate privileged passes can repeat project mutations and hide a shared failure.
    @Test
    void twoPeopleWaitForOneFailedPass() throws Exception {
        final var first = request();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS))
                    .as("the first request starts a pass")
                    .isTrue();
            final var second = request();
            try {
                assertThat(observed.await(2, TimeUnit.SECONDS))
                        .as("the running invocation is observed")
                        .isTrue();
                complete(65);
                final var answer = first.get(3, TimeUnit.SECONDS);
                assertThat(answer.code()).as(answer.err()).isEqualTo(65);
                assertThat(second.get(3, TimeUnit.SECONDS))
                        .as("both callers receive the same completion")
                        .isEqualTo(answer);
                assertThat(starts.get()).as("joining never queues a second pass").isOne();
                final var metadata = HostJson.object(HostJson.parse(answer.out()));
                assertThat(metadata)
                        .as("no organization details cross to the person")
                        .containsOnlyKeys("invocation", "completed", "exitCode")
                        .containsEntry("invocation", INVOCATION)
                        .containsEntry("completed", true);
                assertThat(((Number) metadata.get("exitCode")).intValue())
                        .as("the answer carries the failed exit code")
                        .isEqualTo(65);
            } finally {
                second.cancel(true);
            }
        } finally {
            first.cancel(true);
        }
    }

    // Slow account lookup must not turn a join into another privileged pass.
    @Test
    void aPassEndingDuringAccountLookupIsStillTheRequestsTarget() throws Exception {
        autoComplete = true;
        running = true;
        fixture.put(STATE + "jobs/42", INVOCATION + "\n");
        fixture.put(STATE + "latest", "42\n");
        final var identifying = new CountDownLatch(1);
        final var resume = new CountDownLatch(1);
        beforePerson =
                () -> {
                    identifying.countDown();
                    try {
                        assertThat(resume.await(2, TimeUnit.SECONDS))
                                .as("the synthetic account lookup is bounded")
                                .isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                };
        final var request = request();
        try {
            assertThat(identifying.await(2, TimeUnit.SECONDS))
                    .as("account lookup is in progress")
                    .isTrue();
            complete(65);
            resume.countDown();
            assertThat(request.get(3, TimeUnit.SECONDS).code())
                    .as("the entry pass's failure is retained")
                    .isEqualTo(65);
            assertThat(starts.get()).as("lookup latency cannot cause a replacement pass").isZero();
        } finally {
            resume.countDown();
            request.cancel(true);
        }
    }

    // An unadmitted account must not cause a privileged organization update.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "task",
                "unknown",
                "locked",
                "pending",
                "revoked",
                "uid",
                "missing",
                "root",
                "argument"
            })
    void refusedPeopleStartNothing(String state) throws Exception {
        autoComplete = true;
        Map<String, String> environment = HostReadFixture.SUDO;
        if (state.equals("task"))
            environment = Map.of("SUDO_USER", "porelo-task-1", "SUDO_UID", "1000");
        if (state.equals("unknown"))
            environment = Map.of("SUDO_USER", "nobody", "SUDO_UID", "1000");
        if (state.equals("uid")) environment = Map.of("SUDO_USER", "alice", "SUDO_UID", "999");
        if (state.equals("missing")) environment = Map.of();
        if (state.equals("root")) environment = Map.of("SUDO_USER", "root", "SUDO_UID", "0");
        if (state.equals("locked"))
            fixture.put(
                    "/etc/porelo-host/people/alice.conf",
                    "member=alpha developer\nlocked=true\nssh="
                            + HostReadFixture.KEY
                            + "\nexpires=\n");
        if (state.equals("pending"))
            fixture.person("alice", "member=alpha developer\n", "lock=pending\n");
        if (state.equals("revoked"))
            fixture.put(
                    "/etc/porelo-host/organization/applied.json",
                    "{\"commit\":\""
                            + "b".repeat(40)
                            + "\",\"projects\":[],\"persons\":[],\"revoked\":[\"alice\"]}");
        final var answer =
                state.equals("argument")
                        ? run(environment, "org", "follow-now", "alice")
                        : run(environment, "org", "follow-now");
        assertThat(answer.code())
                .as(state + " is refused at its boundary: " + answer.err())
                .isEqualTo(state.equals("missing") || state.equals("argument") ? 64 : 65);
        assertThat(starts.get()).as("refused people issue no start").isZero();
        assertThat(answer.out()).as("refused people receive no completion details").isEmpty();
    }
}
