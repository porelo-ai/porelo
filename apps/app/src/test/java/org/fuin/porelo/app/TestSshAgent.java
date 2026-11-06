package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Owns only the SSH agents a test starts, including when setup or the test fails. */
final class TestSshAgent implements AutoCloseable, AfterEachCallback {
    private final List<Process> agents = new ArrayList<>();
    private final Thread shutdown = new Thread(this::close, "test-ssh-agent-cleanup");
    private boolean registered;

    synchronized Process start(Path socket) throws IOException, InterruptedException {
        if (!registered) {
            Runtime.getRuntime().addShutdownHook(shutdown);
            registered = true;
        }
        try {
            final Process agent =
                    new ProcessBuilder("ssh-agent", "-D", "-a", socket.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            agents.add(agent);
            for (int i = 0; i < 100 && !Files.exists(socket); i++) {
                if (!agent.isAlive()) {
                    throw new IOException("test SSH agent exited before creating " + socket);
                }
                Thread.sleep(20);
            }
            if (!Files.exists(socket)) {
                throw new IOException("test SSH agent did not create " + socket);
            }
            return agent;
        } catch (IOException | InterruptedException | RuntimeException | Error ex) {
            close();
            throw ex;
        }
    }

    /** Releases agents even when setup or the test body threw. */
    @Override
    public void afterEach(ExtensionContext context) {
        close();
    }

    /** Reaps agents without letting an interrupted test thread skip cleanup. */
    @Override
    public synchronized void close() {
        for (Process agent : agents) {
            final List<ProcessHandle> descendants = agent.descendants().toList();
            try {
                descendants.forEach(ProcessHandle::destroyForcibly);
                for (ProcessHandle descendant : descendants) {
                    descendant.onExit().orTimeout(5, TimeUnit.SECONDS).join();
                }
            } finally {
                agent.destroyForcibly();
            }
        }
        for (Process agent : agents) {
            agent.onExit().orTimeout(5, TimeUnit.SECONDS).join();
        }
        agents.clear();
        if (registered) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ex) {
                // During JVM shutdown this hook is already responsible for the cleanup.
            }
            registered = false;
        }
    }
}
