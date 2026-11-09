package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.daemon.PoreloDaemon;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A window's sign-in over the socket, beside one a person started at a terminal. */
class AgentSignInMethodsTest {

    private static final String CONTAINER = "porelo-login-1000000000007";

    private static PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    /** A 'porelo vault login' at a terminal: its lock held, its container said beside it. */
    private static final class Terminal implements AutoCloseable {

        private final CountDownLatch taken = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private final Thread thread;

        Terminal(final PoreloContext context, final String agent) throws Exception {
            thread =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            FileLocks.holding(
                                                    AgentLogin.lockFile(context, agent),
                                                    () -> {
                                                        Files.writeString(
                                                                AgentLogin.containerFile(
                                                                        context, agent),
                                                                CONTAINER);
                                                        taken.countDown();
                                                        try {
                                                            release.await();
                                                        } catch (InterruptedException ex) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                        return "";
                                                    });
                                        } catch (java.io.IOException ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    });
            assertThat(taken.await(10, TimeUnit.SECONDS))
                    .as("the terminal's sign-in holds its lock")
                    .isTrue();
        }

        void end() {
            release.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            end();
            thread.join();
        }
    }

    @Test
    void aStartWhileATerminalsSignInRunsAnswersThatOneToAttachTo(@TempDir Path dir)
            throws Exception {

        // Started beside it, a second login would authorize the account a second time and its
        // sweep removed the first one's container.
        final PoreloContext context = context(dir);
        try (Terminal terminal = new Terminal(context, "example");
                VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> started =
                        client.call(
                                PoreloDaemon.INTERFACE + ".AgentSignInStart",
                                Map.of("agent", "example"));

                assertThat(started.get("started")).as("nothing was started").isEqualTo(false);
                assertThat(started.get("container"))
                        .as("the running sign-in's container")
                        .isEqualTo(CONTAINER);
                assertThat(started.get("attach"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .as("the window attaches to the login itself")
                        .endsWith("attach", "--sig-proxy=false", CONTAINER);
            }
        }
    }

    @Test
    void aWaitOnATerminalsSignInEndsWhenItsLockIsFree(@TempDir Path dir) throws Exception {

        // A start that waits must go on when the terminal's sign-in ends, not at the limit.
        final PoreloContext context = context(dir);
        final java.util.concurrent.atomic.AtomicBoolean over =
                new java.util.concurrent.atomic.AtomicBoolean();
        try (Terminal terminal = new Terminal(context, "example");
                VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                                over.set(true);
                                terminal.end();
                            });
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> ended =
                        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                                java.time.Duration.ofSeconds(60),
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".AgentSignInWait",
                                                Map.of("agent", "example")));

                assertThat(over.get())
                        .as("the wait answered only once the terminal's sign-in had ended")
                        .isTrue();
                assertThat(ended.get("stored"))
                        .as("no agent is installed here, so nothing it needs is stored")
                        .isEqualTo(false);
            }
        }
    }
}
