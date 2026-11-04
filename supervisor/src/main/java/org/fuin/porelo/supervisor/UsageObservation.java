package org.fuin.porelo.supervisor;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Lossy, bounded observation keeps parsing and disk latency off the answer's path. */
final class UsageObservation {
    private final ArrayBlockingQueue<byte[]> pending = new ArrayBlockingQueue<>(256);
    private volatile boolean lost;
    private volatile boolean done;
    private volatile @Nullable String closedBy;
    private volatile boolean started;
    private volatile boolean withheld;
    private volatile boolean events;
    private volatile int status;
    private volatile String side = "provider";
    private final java.util.concurrent.CompletableFuture<Void> written =
            new java.util.concurrent.CompletableFuture<>();

    UsageObservation(
            UsageReading reading,
            VaultProxy.Usage usage,
            @Nullable String provider,
            String credential,
            String real,
            String phantom) {
        this(
                reading,
                line -> {
                    line.put("agent", usage.agent());
                    line.put("provider", provider);
                    line.put("credential", credential);
                    final String json = Json.write(line);
                    // Even a provider echoing a credential as a model name cannot put it on disk.
                    if (!real.isEmpty() && json.contains(real)
                            || !phantom.isEmpty() && json.contains(phantom)) {
                        line.put("model_asked", null);
                        line.put("model_answered", null);
                        line.put("finish_reason", null);
                        line.put("tools", Map.of());
                    }
                    append(usage, line);
                },
                command -> Thread.ofVirtual().name("usage-observer").start(command));
    }

    UsageObservation(
            UsageReading reading,
            java.util.function.Consumer<Map<String, Object>> writer,
            java.util.concurrent.Executor executor) {
        executor.execute(
                () -> {
                    try {
                        while (!done || !pending.isEmpty()) {
                            final byte[] part = pending.poll(100, TimeUnit.MILLISECONDS);
                            if (part != null) {
                                reading.start(events, status);
                                reading.accept(part, part.length);
                            }
                        }
                        if (lost || !started) reading.invalidate();
                        // No status arrived before a shutdown: that is an abort, not an error.
                        if (started) reading.start(events, status);
                        if (withheld) reading.withhold();
                        writer.accept(reading.finish(closedBy));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException | StackOverflowError ex) {
                        // Observation is disposable and exception text may contain private response
                        // bytes.
                    } finally {
                        written.complete(null);
                    }
                });
    }

    void start(boolean stream, int code) {
        events = stream;
        status = code;
        started = true;
    }

    void withheld() {
        withheld = true;
    }

    synchronized void accept(byte[] bytes, int length) {
        if (length > 0 && !done && !lost && !pending.offer(Arrays.copyOf(bytes, length)))
            lost = true;
    }

    void agent() {
        side = "agent";
    }

    void provider() {
        side = "provider";
    }

    synchronized void finish(boolean natural) {
        if (done) return;
        closedBy = natural ? null : side;
        done = true;
    }

    void whenDone(Runnable action) {
        written.thenRun(action);
    }

    void awaitUntil(long deadline) {
        try {
            written.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            // Shutdown has a bound even when the record's filesystem has stopped answering.
        }
    }

    private static boolean endsMidLine(java.nio.file.Path file) throws java.io.IOException {
        final long size = Files.size(file);
        if (size == 0) return false;
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
            final var last = java.nio.ByteBuffer.allocate(1);
            return channel.read(last, size - 1) == 1 && last.get(0) != '\n';
        }
    }

    private static synchronized void append(VaultProxy.Usage usage, Map<String, Object> line) {
        try {
            final var parent = usage.file().getParent();
            if (parent != null) {
                Directories.create(parent);
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            }
            if (!Files.exists(usage.file())) {
                try {
                    Files.createFile(
                            usage.file(),
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                } catch (java.nio.file.FileAlreadyExistsException ex) {
                    // Another completed request may have published the record first.
                }
            }
            Files.setPosixFilePermissions(
                    usage.file(), PosixFilePermissions.fromString("rw-------"));
            // After a killed broker's half line, so a valid request is not lost with it.
            Files.writeString(
                    usage.file(),
                    (endsMidLine(usage.file()) ? "\n" : "") + Json.write(line) + "\n",
                    StandardOpenOption.APPEND);
        } catch (java.io.IOException | RuntimeException ex) {
            // Disk failures cannot break the answer or disclose any part of it through a log.
        }
    }
}
