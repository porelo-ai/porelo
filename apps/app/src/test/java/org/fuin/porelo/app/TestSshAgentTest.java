package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Guards against a failed test leaving its SSH agent running on the host. */
@Timeout(20)
class TestSshAgentTest {
    @TempDir Path dir;

    @Test
    void aFailingBodyStillStopsItsAgent() throws Exception {
        final AtomicReference<Process> started = new AtomicReference<>();
        try {
            assertThatThrownBy(
                            () -> {
                                try (TestSshAgent agents = new TestSshAgent()) {
                                    started.set(agents.start(dir.resolve("agent.sock")));
                                    throw new AssertionError("intentional test body failure");
                                }
                            })
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("intentional test body failure");
            assertThat(started.get().isAlive())
                    .as("a failing test must leave no SSH agent running")
                    .isFalse();
        } finally {
            if (started.get() != null) {
                started.get().destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void afterEachStopsAnAgentEvenOnAnInterruptedThread() throws Exception {
        try (TestSshAgent agents = new TestSshAgent()) {
            final Process agent = agents.start(dir.resolve("agent.sock"));
            Thread.currentThread().interrupt();
            try {
                agents.afterEach(null);
                assertThat(agent.isAlive())
                        .as("an interrupt cannot leave the test agent running")
                        .isFalse();
                assertThat(Thread.currentThread().isInterrupted())
                        .as("cleanup preserves the interrupt")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void shuttingDownTheJvmStopsAnAgentWithoutTeardown() throws Exception {
        final Path pid = dir.resolve("pid");
        final Process jvm =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                AbandonedAgent.class.getName(),
                                dir.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        ProcessHandle agent = null;
        try {
            for (int i = 0; i < 250 && !Files.exists(pid) && jvm.isAlive(); i++) {
                Thread.sleep(20);
            }
            assertThat(pid).as("the child JVM started its test agent").exists();
            agent = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
            assertThat(agent.isAlive()).as("the agent runs before JVM shutdown").isTrue();
            jvm.destroy();
            assertThat(jvm.waitFor(10, TimeUnit.SECONDS))
                    .as("the child JVM exits on termination")
                    .isTrue();
            assertThat(agent.onExit())
                    .as("the shutdown hook stops the abandoned agent")
                    .succeedsWithin(Duration.ofSeconds(5));
        } finally {
            final var descendants = jvm.descendants().toList();
            jvm.destroy();
            try {
                jvm.waitFor(5, TimeUnit.SECONDS);
            } finally {
                descendants.forEach(ProcessHandle::destroyForcibly);
                if (agent != null) {
                    agent.destroyForcibly();
                }
                jvm.destroyForcibly().onExit().join();
            }
        }
    }

    /** A separate JVM whose test never reaches teardown. */
    public static final class AbandonedAgent {
        /** Starts an agent and waits until the test terminates this JVM. */
        public static void main(String[] args) throws Exception {
            final Path dir = Path.of(args[0]);
            final TestSshAgent agents = new TestSshAgent();
            final Process agent = agents.start(dir.resolve("agent.sock"));
            // Moved in whole, so the test never reads the file before its number is in it.
            final Path written =
                    Files.writeString(dir.resolve("pid.tmp"), Long.toString(agent.pid()));
            Files.move(written, dir.resolve("pid"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            System.in.read();
        }
    }
}
