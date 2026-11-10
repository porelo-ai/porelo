package org.fuin.porelo.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/** A gathered transfer owns its Git processes and accepts only complete captured output. */
class HostGatherRunner implements CommandRunner, AutoCloseable {
    private static final int MAXIMUM_OUTPUT = 16 * 1024 * 1024;
    private @Nullable Process active;
    private final Set<ProcessHandle> observed = new LinkedHashSet<>();
    private boolean closed;
    private final Duration executionLimit;

    HostGatherRunner() {
        this(Duration.ZERO);
    }

    private HostGatherRunner(Duration executionLimit) {
        this.executionLimit = executionLimit;
    }

    static CommandResult system(
            org.fuin.porelo.core.process.CommandRunner runner, Command command) {
        if (!(runner instanceof org.fuin.porelo.core.process.ProcessCommandRunner))
            return runner.run(command);
        try (HostGatherRunner bounded = new HostGatherRunner(Duration.ofMinutes(10))) {
            return bounded.run(command);
        }
    }

    private synchronized boolean closing() {
        return closed;
    }

    @Override
    public CommandResult run(Command command) {
        final Process process;
        try {
            final var builder =
                    new ProcessBuilder(command.arguments()).directory(Path.of("/").toFile());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            process = start(builder);
        } catch (IOException ex) {
            throw new HostGatherRefusal(
                    1, "Git could not run; check the installed git and forge connection");
        }
        final FutureTask<String> output = new FutureTask<>(() -> read(process.getInputStream()));
        final FutureTask<String> errors = new FutureTask<>(() -> read(process.getErrorStream()));
        final Thread reader = Thread.ofVirtual().name("porelo-gather-output").start(output);
        final Thread errorReader = Thread.ofVirtual().name("porelo-gather-errors").start(errors);
        try {
            process.getOutputStream().close();
            final long deadline =
                    executionLimit.isZero() ? 0 : System.nanoTime() + executionLimit.toNanos();
            while (process.isAlive()) {
                if (deadline != 0 && System.nanoTime() >= deadline) throw new TimeoutException();
                synchronized (this) {
                    process.descendants().forEach(observed::add);
                }
                if (output.isDone()) output.get();
                if (errors.isDone()) errors.get();
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
            if (closing()) throw new HostGatherRefusal(130, "");
            final long readEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            final String stdout =
                    output.get(Math.max(1, readEnd - System.nanoTime()), TimeUnit.NANOSECONDS);
            final String stderr =
                    errors.get(Math.max(1, readEnd - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (closing()) throw new HostGatherRefusal(130, "");
            return new CommandResult(command, process.exitValue(), stdout, stderr);
        } catch (ExecutionException | IOException | TimeoutException ex) {
            if (closing()) throw new HostGatherRefusal(130, "");
            close();
            throw new HostGatherRefusal(
                    1,
                    "Git output could not be read completely within 16 MiB; inspect the forge's response and retry");
        } catch (InterruptedException ex) {
            if (closing()) {
                Thread.currentThread().interrupt();
                throw new HostGatherRefusal(130, "");
            }
            close();
            Thread.currentThread().interrupt();
            throw new HostGatherRefusal(1, "Git was interrupted; retry the gathered transfer");
        } finally {
            output.cancel(true);
            errors.cancel(true);
            closePipe(process.getInputStream());
            closePipe(process.getErrorStream());
            reader.interrupt();
            errorReader.interrupt();
        }
    }

    private static String read(InputStream stream) throws IOException {
        final byte[] buffer = new byte[8192];
        final var collected = new Captured();
        try (stream) {
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count > MAXIMUM_OUTPUT - collected.size())
                    throw new IOException("Git output exceeded its limit");
                collected.write(buffer, 0, count);
            }
            return collected.toString(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(buffer, (byte) 0);
            collected.erase();
        }
    }

    private static final class Captured extends ByteArrayOutputStream {
        void erase() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    private static void closePipe(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ex) {
            /* The ended process's pipe cannot change the transfer's verdict. */
        }
    }

    @Override
    public void close() {
        final Process process;
        synchronized (this) {
            closed = true;
            process = active;
        }
        if (process == null) return;
        final List<ProcessHandle> children;
        synchronized (this) {
            process.descendants().forEach(observed::add);
            children = new ArrayList<>(observed).reversed();
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            // Descendants end first so their parents can reap them before they end too.
            for (ProcessHandle child : children) {
                child.destroy();
                while (child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                if (child.isAlive()) child.destroyForcibly();
            }
            process.destroy();
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                process.destroyForcibly();
            process.waitFor();
        } catch (InterruptedException ex) {
            children.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    protected synchronized Process start(ProcessBuilder builder) throws IOException {
        if (closed) throw new IOException("Gathered transfer has stopped");
        final Process process = builder.start();
        active = process;
        observed.removeIf(child -> !child.isAlive());
        return process;
    }
}
