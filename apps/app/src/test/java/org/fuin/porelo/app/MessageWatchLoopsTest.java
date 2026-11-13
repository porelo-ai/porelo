package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which tasks the daemon keeps a loop waiting on a transport for: a thread each, woken every few
 * seconds, so a loop for a task that cannot be reached costs CPU and a thread for as long as the
 * daemon runs. Exited tasks that were never removed each kept one.
 */
class MessageWatchLoopsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    /** Far shorter than {@link MessageWatch#IDLE}, so a loop's end is not waited out. */
    private static final Duration SHORT_REST = Duration.ofMillis(100);

    /** How long a loop with the production rest is watched for asking its transport again. */
    private static final long WATCHED_MILLIS = 1_500;

    private static final String PROJECT =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            mail:
              transports:
                room:
                  server: https://example.org
            """;

    @TempDir Path dir;

    private final FakeCommandRunner runner = new TransportTestRunner();

    private PoreloContext context;

    private KernelKeyring keyring;

    private MessageWatch watch;

    private void machine(final String containers) throws IOException {
        machine(containers, MessageWatch.IDLE);
    }

    private void machine(final String containers, final Duration rest) throws IOException {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
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
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path adapter =
                Files.createDirectories(context.paths().xdg().data().resolve("transports"))
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"poll\":true,\"waits\":true,\"direct\":true,"
                        + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
        runner.answering(
                "setup",
                "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                        + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
        runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
        runner.answering("podman ps", LegacyTaskFixture.listed(containers, "a1b2c3d4e5f6"));
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, PROJECT);
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("p", file);
        keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), PASSPHRASE);
        keyring.store(PASSPHRASE);
        watch =
                rest.equals(MessageWatch.IDLE)
                        ? new MessageWatch(context, Duration.ofSeconds(60))
                        : new MessageWatch(context, Duration.ofSeconds(60), rest);
    }

    private void task(final String container, final boolean account) throws IOException {
        LegacyTaskFixture.record(context, container, "p", "a1b2c3d4e5f6");
        new Mailbox(context.paths().messaging().mailbox(container)).create();
        if (account) {
            assertThat(
                            new TaskConversations(context)
                                    .enroll(
                                            ProjectReader.read(dir.resolve("project.yml")),
                                            container))
                    .as("taken into the project's conversation")
                    .isNull();
        }
    }

    private static List<Thread> loops(final String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().equals(name))
                .toList();
    }

    private long asksOfDirectChats() {
        return runner.invocations().stream()
                .filter(each -> each.arguments().contains("--direct"))
                .count();
    }

    private static boolean ended(final String name) throws InterruptedException {
        for (final Thread thread : loops(name)) {
            // A loop ends when it wakes from its rest; the bound stays twice the production rest.
            thread.join(2 * MessageWatch.IDLE.toMillis());
        }
        return loops(name).isEmpty();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (watch != null) {
            watch.close();
        }
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("porelo-direct-porelo-p-")
                    || thread.getName().equals("porelo-conversation-p")) {
                thread.interrupt();
                thread.join(5000);
            }
        }
        if (keyring != null) {
            keyring.forget();
        }
    }

    @Test
    void aRunningTaskWithAnAccountHasALoopOnItsDirectChatsAndOneOnItsConversation()
            throws IOException, InterruptedException {
        machine("porelo-p-up\tUp 4 minutes\t1700000000\t0\tp\tguarded\n");
        task("porelo-p-up", true);

        watch.passOnce();

        assertThat(loops("porelo-direct-porelo-p-up"))
                .as("the loop a direct chat is read by")
                .hasSize(1);
        assertThat(loops("porelo-conversation-p"))
                .as("the loop the project's conversation is read by")
                .hasSize(1);

        // A transport here comes back at once with nothing: asked again without a rest, two such
        // loops turned two cores for hours.
        final long bound = System.nanoTime() + MessageWatch.IDLE.toNanos();
        while (asksOfDirectChats() == 0 && System.nanoTime() < bound) {
            Thread.sleep(20);
        }
        Thread.sleep(WATCHED_MILLIS);
        assertThat(asksOfDirectChats())
                .as("the daemon's watch rests between asks of a transport that did not wait")
                .isOne();
    }

    @Test
    void anExitedTaskWithAnAccountHasNoLoop() throws IOException {
        machine("porelo-p-down\tExited (0) 2 days ago\t1700000000\t1700000100\tp\tguarded\n");
        task("porelo-p-down", true);

        watch.passOnce();

        assertThat(loops("porelo-direct-porelo-p-down"))
                .as("its container exited two days ago; the timed pass still delivers to it")
                .isEmpty();
        assertThat(loops("porelo-conversation-p"))
                .as("no task of the project runs to be handed what it waits for")
                .isEmpty();
    }

    @Test
    void aRunningTaskWithNoAccountHasNoLoop() throws IOException {
        machine("porelo-p-bare\tUp 4 minutes\t1700000000\t0\tp\tguarded\n");
        task("porelo-p-bare", false);

        watch.passOnce();

        assertThat(loops("porelo-direct-porelo-p-bare"))
                .as("nothing it could be asked with")
                .isEmpty();
        assertThat(loops("porelo-conversation-p"))
                .as("no task of the project can be asked with an account")
                .isEmpty();
    }

    @Test
    void aDirectPollTheStopOfItsTaskCutShortIsNoFailureAndEndsItsLoop() throws Exception {
        // The task's token goes with its stop; the one poll already waiting comes back refused
        // (window on a rented host, 2026-11-11: "401 M_UNKNOWN_TOKEN" once per stopped task).
        machine("porelo-p-ends\tUp 4 minutes\t1700000000\t0\tp\tguarded\n", SHORT_REST);
        task("porelo-p-ends", true);
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream before = System.err;
        System.setErr(new java.io.PrintStream(said, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            watch.passOnce();
            assertThat(loops("porelo-direct-porelo-p-ends")).hasSize(1);

            runner.answering(
                    "podman ps",
                    LegacyTaskFixture.listed(
                            "porelo-p-ends\tExited (0) 1 second ago\t1700000000\t1700000100\tp"
                                    + "\tguarded\n",
                            "a1b2c3d4e5f6"));
            runner.failing("--direct", 77, "401 M_UNKNOWN_TOKEN: Unknown access token\n");

            assertThat(ended("porelo-direct-porelo-p-ends"))
                    .as("the loop of a task that stopped ends at its refused poll")
                    .isTrue();
        } finally {
            System.setErr(before);
        }
        assertThat(said.toString(java.nio.charset.StandardCharsets.UTF_8))
                .as("a refusal that only says the task stopped is said to no one")
                .doesNotContain("M_UNKNOWN_TOKEN");
    }

    @Test
    void theLoopOfATaskWhoseAccountCannotBeReadAnyLongerEnds() throws Exception {
        machine("porelo-p-shut\tUp 4 minutes\t1700000000\t0\tp\tguarded\n", SHORT_REST);
        task("porelo-p-shut", true);
        watch.passOnce();
        assertThat(loops("porelo-direct-porelo-p-shut")).hasSize(1);

        // The vault shut: its wait comes back at once with nothing.
        final long shut = System.nanoTime();
        keyring.forget();

        assertThat(ended("porelo-direct-porelo-p-shut")).as("without waiting for a pass").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - shut))
                .as("it woke from the rest it was given, not the production one")
                .isLessThan(MessageWatch.IDLE);
    }

    @Test
    void aTaskOfAProjectWhoseAccessFileIsNotAppliedIsNeitherPolledNorWaitedFor() throws Exception {
        // A transport a pass asks for what arrived, besides the conversation's: answered first.
        runner.answering("transport-spool describe", "{\"scheme\":\"spool\",\"poll\":true}");
        machine("porelo-p-held\tUp 4 minutes\t1700000000\t0\tp\tguarded\n");
        final Path spool =
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "spool");
        Files.writeString(spool, "#!/bin/sh\n");
        assertThat(spool.toFile().setExecutable(true)).as("the transport is executable").isTrue();
        task("porelo-p-held", true);
        // The project now names an access file, and none beside it is in force: the account was
        // set up from settings that no longer hold.
        Files.writeString(
                dir.resolve("project.yml"),
                """
                project:
                  name: "p"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                mail:
                  peers:
                    reviewer: { address: "room:", trust: vouched }
                    bob: { address: "spool:bob", trust: vouched }
                access:
                  file: "access.yml"
                """);
        final int before = runner.invocations().size();

        watch.passOnce();

        assertThat(runner.invocations().subList(before, runner.invocations().size()))
                .as("no poll and no send reaches the transport")
                .noneMatch(
                        each ->
                                each.arguments().contains("poll")
                                        || each.arguments().contains("send"));
        assertThat(loops("porelo-direct-porelo-p-held"))
                .as("no loop on its direct chats")
                .isEmpty();
        assertThat(loops("porelo-conversation-p")).as("none on its conversation").isEmpty();
    }

    @Test
    void theLoopsOfARemovedTaskEndAtTheNextPassEvenWithNoMailboxLeft() throws Exception {
        machine("porelo-p-gone\tUp 4 minutes\t1700000000\t0\tp\tguarded\n", SHORT_REST);
        task("porelo-p-gone", true);
        watch.passOnce();
        assertThat(loops("porelo-direct-porelo-p-gone")).hasSize(1);

        new Mailbox(context.paths().messaging().mailbox("porelo-p-gone")).delete();
        watch.passOnce();

        assertThat(ended("porelo-direct-porelo-p-gone")).as("its direct chats' loop").isTrue();
        assertThat(ended("porelo-conversation-p"))
                .as("its project's conversation, with nobody here to hand anything to")
                .isTrue();
    }
}
