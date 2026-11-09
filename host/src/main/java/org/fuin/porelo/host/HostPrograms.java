package org.fuin.porelo.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.fuin.porelo.wire.SystemProgram;

/** Runs checked programs without a shell or an inherited environment. */
final class HostPrograms {
    static final String HOST_PATH = "/usr/sbin:/usr/bin:/sbin:/bin";
    static final Map<String, String> ENVIRONMENT = Map.of("PATH", HOST_PATH, "LANG", "C.UTF-8");
    private final Duration timeout;
    private final int maximumBytes;
    private final Function<String, Path> programs;

    HostPrograms() {
        this(Duration.ofSeconds(10), 65536);
    }

    HostPrograms(Duration timeout, int maximumBytes) {
        this(timeout, maximumBytes, HostPrograms::program);
    }

    HostPrograms(Duration timeout, int maximumBytes, Function<String, Path> programs) {
        if (timeout.isNegative() || maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Expected a nonnegative timeout and bounded output limit");
        }
        this.timeout = timeout;
        this.maximumBytes = maximumBytes;
        this.programs = programs;
    }

    record Result(int exitCode, String output, String error) {
        Result(int exitCode, String output) {
            this(exitCode, output, "");
        }

        @Override
        public String toString() {
            return "Result[exitCode=" + exitCode + ", output withheld]";
        }
    }

    Result run(List<String> argv) throws IOException {
        return run(argv, new ByteArrayInputStream(new byte[0]), null, Map.of(), true);
    }

    Result run(List<String> argv, byte[] input, Map<String, String> environment)
            throws IOException {
        return run(argv, new ByteArrayInputStream(input), null, environment, false);
    }

    Result transfer(
            List<String> argv,
            InputStream input,
            OutputStream output,
            Map<String, String> environment)
            throws IOException {
        return run(argv, input, output, environment, false);
    }

    private Result run(
            List<String> argv,
            InputStream input,
            @org.jspecify.annotations.Nullable OutputStream outputStream,
            Map<String, String> environment,
            boolean fixedEnvironment)
            throws IOException {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("Expected a program and its arguments");
        }
        final String name = argv.getFirst();
        final Path program;
        try {
            program = programs.apply(name);
        } catch (UncheckedIOException ex) {
            if (ex.getCause() instanceof SystemProgram.Missing) {
                throw new IOException(ex.getCause().getMessage(), ex.getCause());
            }
            throw ex.getCause();
        }
        final List<String> command = new ArrayList<>(argv);
        command.set(0, program.toString());
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(Path.of("/").toFile())
                        // Read stderr apart and bounded: an NSS warning is no part of its answer.
                        .redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(fixedEnvironment ? ENVIRONMENT : environment);
        final Process process = builder.start();
        final FutureTask<byte[]> output =
                new FutureTask<>(
                        () -> {
                            if (outputStream == null) {
                                return read(process.getInputStream());
                            }
                            try (var pipe = process.getInputStream()) {
                                pipe.transferTo(outputStream);
                            }
                            return new byte[0];
                        });
        final Thread reader = Thread.ofVirtual().name("porelo-host-output").start(output);
        final FutureTask<byte[]> errors = new FutureTask<>(() -> read(process.getErrorStream()));
        final Thread errorReader = Thread.ofVirtual().name("porelo-host-errors").start(errors);
        final FutureTask<Void> feed =
                new FutureTask<>(
                        () -> {
                            try (var pipe = process.getOutputStream()) {
                                input.transferTo(pipe);
                            }
                            return null;
                        });
        final Thread writer = Thread.ofVirtual().name("porelo-host-input").start(feed);
        // Zero preserves a caller's lifetime where the predecessor imposes no deadline.
        final long deadline = timeout.isZero() ? 0 : System.nanoTime() + timeout.toNanos();
        final Set<ProcessHandle> descendants = new HashSet<>();
        try {
            while (process.isAlive() && !output.isDone()) {
                process.descendants().forEach(descendants::add);
                if (deadline != 0 && System.nanoTime() >= deadline) {
                    throw new TimeoutException();
                }
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
            final byte[] bytes = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            try {
                feed.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (ExecutionException ex) {
                if (process.exitValue() == 0 || !(ex.getCause() instanceof IOException)) {
                    throw ex;
                }
                // A child may refuse before reading stdin; its refusal remains the verdict.
            }
            final byte[] stderr = errors.get(remaining(deadline), TimeUnit.NANOSECONDS);
            try {
                return new Result(
                        process.exitValue(),
                        new String(bytes, StandardCharsets.UTF_8),
                        new String(stderr, StandardCharsets.UTF_8));
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
                java.util.Arrays.fill(stderr, (byte) 0);
            }
        } catch (TimeoutException ex) {
            throw new IOException("Host program exceeded its timeout", ex);
        } catch (ExecutionException ex) {
            throw new IOException("Host program output could not be read within its limit", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Host program interrupted", ex);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            descendants.forEach(
                    child -> {
                        if (child.isAlive()) {
                            child.destroyForcibly();
                        }
                    });
            process.destroyForcibly();
            output.cancel(true);
            errors.cancel(true);
            feed.cancel(true);
            reader.interrupt();
            errorReader.interrupt();
            writer.interrupt();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    static Path program(String name) {
        return Path.of(name).isAbsolute()
                ? SystemProgram.at(Path.of(name))
                : SystemProgram.find(
                        name, java.util.Arrays.stream(HOST_PATH.split(":")).map(Path::of).toList());
    }

    static String failureKind(IOException failure) {
        return failure.getCause() instanceof TimeoutException ? "TimeoutExpired" : "OSError";
    }

    private byte[] read(InputStream stream) throws IOException {
        try (stream) {
            final byte[] bytes = stream.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IOException("Host program exceeded its output limit");
            }
            return bytes;
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        if (deadline == 0) {
            return Long.MAX_VALUE;
        }
        final long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new TimeoutException();
        }
        return nanos;
    }
}
