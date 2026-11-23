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
    private final java.util.function.BooleanSupplier open;
    private final org.fuin.porelo.wire.LimitFactors limits;

    /** How long a command of the host's runs at most, at the machines factor 1.0. */
    private static final Duration SYSTEM_LIMIT = Duration.ofMinutes(10);

    /** How long the output is read after the process ended, at the machines factor 1.0. */
    private static final Duration READ_END = Duration.ofSeconds(2);

    /** How long a closed transfer's processes have to end before they are killed, at 1.0. */
    private static final Duration END = Duration.ofSeconds(3);

    HostGatherRunner(org.fuin.porelo.wire.LimitFactors limits) {
        this(Duration.ZERO, () -> true, limits);
    }

    /**
     * Constructor.
     *
     * @param executionLimit How long one command may run at the machines factor 1.0; zero for no
     *     limit.
     * @param open Whether the transfer is still wanted.
     * @param limits The factors every wait of the runner follows.
     */
    HostGatherRunner(
            Duration executionLimit,
            java.util.function.BooleanSupplier open,
            org.fuin.porelo.wire.LimitFactors limits) {
        this.executionLimit = limits.machines(executionLimit);
        this.open = open;
        this.limits = limits;
    }

    static CommandResult system(PoreloContext context, Command command) {
        return system(context, command, SYSTEM_LIMIT);
    }

    static CommandResult system(PoreloContext context, Command command, Duration limit) {
        if (!(context.runner() instanceof org.fuin.porelo.core.process.ProcessCommandRunner))
            return context.runner().run(command);
        try (HostGatherRunner bounded = new HostGatherRunner(limit, () -> true, context.limits())) {
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
        final FutureTask<Void> input =
                new FutureTask<>(
                        () -> {
                            try (var pipe = process.getOutputStream()) {
                                final byte[] binary = command.binaryInput();
                                final String text = command.input();
                                if (binary != null) pipe.write(binary);
                                else if (text != null)
                                    pipe.write(text.getBytes(StandardCharsets.UTF_8));
                            }
                            return null;
                        });
        final Thread writer = Thread.ofVirtual().name("porelo-gather-input").start(input);
        try {
            final long deadline =
                    executionLimit.isZero() ? 0 : System.nanoTime() + executionLimit.toNanos();
            while (process.isAlive()) {
                if (!open.getAsBoolean()) {
                    close();
                    throw new HostGatherRefusal(
                            HostGatherRefusal.Kind.CANCELLED,
                            1,
                            "gathered transfer stopped; a started push may have reached central",
                            java.util.Map.of());
                }
                if (deadline != 0 && System.nanoTime() >= deadline) throw new TimeoutException();
                synchronized (this) {
                    process.descendants().forEach(observed::add);
                }
                if (output.isDone()) output.get();
                if (errors.isDone()) errors.get();
                if (input.isDone()) input.get();
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
            if (closing()) throw new HostGatherRefusal(130, "");
            final long readEnd = System.nanoTime() + limits.machines(READ_END).toNanos();
            input.get(Math.max(1, readEnd - System.nanoTime()), TimeUnit.NANOSECONDS);
            final String stdout =
                    output.get(Math.max(1, readEnd - System.nanoTime()), TimeUnit.NANOSECONDS);
            final String stderr =
                    errors.get(Math.max(1, readEnd - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (closing()) throw new HostGatherRefusal(130, "");
            return new CommandResult(command, process.exitValue(), stdout, stderr);
        } catch (ExecutionException | TimeoutException ex) {
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
            input.cancel(true);
            output.cancel(true);
            errors.cancel(true);
            closePipe(process.getInputStream());
            closePipe(process.getErrorStream());
            reader.interrupt();
            errorReader.interrupt();
            writer.interrupt();
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
        final long deadline = System.nanoTime() + limits.machines(END).toNanos();
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
