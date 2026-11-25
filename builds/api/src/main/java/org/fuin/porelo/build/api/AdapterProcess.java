package org.fuin.porelo.build.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * One adapter executable as Porelo runs it: started on an owner-only socket, handshaken, asked, and
 * started again at the next question once its process has ended.
 *
 * <p>Shared by the build and the review client, so both start, bound and end an adapter alike. One
 * caller at a time: a question and its answer share one connection.
 */
final class AdapterProcess implements AutoCloseable {

    /** What a client checks of a freshly started adapter before anything is asked of it. */
    @FunctionalInterface
    interface Handshake {

        /**
         * Checks the adapter, refusing one that speaks another contract.
         *
         * @param client The connection to it.
         * @throws BuildReaderException When it is refused.
         */
        void check(VarlinkClient client);
    }

    private final Path executable;

    private final Path socket;

    private final LimitFactors limits;

    private final Duration readyLimit;

    private final Duration callLimit;

    private final Duration endWait;

    private final Handshake handshake;

    private Running running;

    /** The adapter's process and the connection to it. */
    private record Running(Process process, VarlinkClient client) {}

    /**
     * Starts an adapter and completes its handshake.
     *
     * @param executable The adapter.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @param limits The factors its time limits follow.
     * @param handshake What is checked before it is asked anything.
     * @throws BuildReaderException When it does not start or the handshake refuses it.
     */
    AdapterProcess(
            final Path executable,
            final Path socket,
            final LimitFactors limits,
            final Handshake handshake) {
        this.executable = executable;
        this.socket = socket;
        this.limits = limits;
        this.readyLimit = InstalledBuildReader.readyLimit(limits);
        this.callLimit = InstalledBuildReader.callLimit(limits);
        this.endWait = InstalledBuildReader.endWait(limits);
        this.handshake = handshake;
        this.running = start();
    }

    /**
     * Returns the adapter's executable, for messages.
     *
     * @return The path.
     */
    Path executable() {
        return executable;
    }

    /**
     * Returns how long the handshake may take.
     *
     * @return The limit.
     */
    Duration readyLimit() {
        return readyLimit;
    }

    /**
     * Returns how long one question may take.
     *
     * @return The limit.
     */
    Duration callLimit() {
        return callLimit;
    }

    /**
     * Asks one question, starting the adapter again first when its process has ended.
     *
     * @param method The fully qualified method.
     * @param parameters Its parameters.
     * @return The answer.
     * @throws VarlinkTimeoutException When it did not answer in time; its process is ended.
     * @throws VarlinkException When the call failed: with an error name as the adapter answered,
     *     without one when the connection failed, its process then ended.
     */
    Map<String, Object> call(final String method, final Map<String, Object> parameters) {
        if (!running.process().isAlive()) {
            // Killed for not answering in time, or gone by itself: the watch asks again later, and
            // one that cannot answer now must not leave every later question unanswered.
            closeQuietly(running.client());
            running = start();
        }
        final Running asked = running;
        try {
            return asked.client().call(method, parameters, callLimit);
        } catch (VarlinkTimeoutException ex) {
            // Killed, so the next question starts it again rather than reading this answer late.
            ended(asked.process());
            throw ex;
        } catch (VarlinkException ex) {
            if (ex.getErrorName() == null) {
                // The connection failed, not the question: the reader is gone or going. Its process
                // is ended and waited for here, so the next question finds it ended and starts it
                // again - looking at it straight away found a crashed reader still alive on a busy
                // machine, and asked it again.
                ended(asked.process());
            }
            throw ex;
        }
    }

    @Override
    public void close() {
        final Process process = running.process();
        closeQuietly(running.client());
        process.destroy();
        try {
            if (!process.waitFor(endWait.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The socket is this adapter's alone, in a directory that goes with the task.
        }
    }

    /**
     * Returns a time limit as a person reads it.
     *
     * @param limit The limit.
     * @return Such as {@code 1.5 seconds}.
     */
    static String seconds(final Duration limit) {
        return java.math.BigDecimal.valueOf(limit.toMillis(), 3)
                        .stripTrailingZeros()
                        .toPlainString()
                + " seconds";
    }

    /** Starts the adapter, waits for it to listen, and completes the handshake. */
    private Running start() {
        final Process process;
        try {
            Files.deleteIfExists(socket);
            process =
                    new ProcessBuilder(InstalledBuildReader.command(executable, socket, limits))
                            .redirectErrorStream(true)
                            .start();
        } catch (IOException ex) {
            throw new BuildReaderException(
                    "Cannot start " + executable + ": " + ex.getMessage(), ex);
        }
        VarlinkClient opened = null;
        try {
            awaitReady(process);
            opened = new VarlinkClient(socket, limits);
            handshake.check(opened);
            return new Running(process, opened);
        } catch (RuntimeException ex) {
            ended(process);
            closeQuietly(opened);
            try {
                Files.deleteIfExists(socket);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex instanceof BuildReaderException reader
                    ? reader
                    : new BuildReaderException(
                            executable + " failed to start: " + ex.getMessage(), ex);
        }
    }

    /**
     * Waits for the ready line the adapter prints once its socket listens; whatever it said instead
     * is the reason.
     */
    private void awaitReady(final Process process) {
        final BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        final String said =
                bounded(
                        process,
                        () -> {
                            final StringBuilder lines = new StringBuilder();
                            try {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("ready ")) {
                                        drain(reader);
                                        return null;
                                    }
                                    lines.append(lines.isEmpty() ? "" : "; ").append(line.strip());
                                }
                            } catch (IOException ex) {
                                lines.append(ex.getMessage());
                            }
                            return lines.toString();
                        },
                        readyLimit);
        if (said != null) {
            throw new BuildReaderException(
                    executable
                            + " exited without becoming ready"
                            + (said.isEmpty() ? "" : ", saying: " + said));
        }
    }

    /**
     * Keeps reading what the adapter writes after it is ready, so a line more never meets a full
     * pipe.
     */
    private static void drain(final BufferedReader reader) {
        Thread.ofPlatform()
                .daemon()
                .name("porelo-build-reader-output")
                .start(
                        () -> {
                            try (reader) {
                                while (reader.readLine() != null) {
                                    // Discarded: an adapter's answers come over the socket.
                                }
                            } catch (IOException ex) {
                                // The adapter ended.
                            }
                        });
    }

    /** Runs a step that is no varlink call within a limit: reading the ready line waits without. */
    private <T> T bounded(final Process process, final Supplier<T> step, final Duration limit) {
        final ExecutorService runner =
                Executors.newSingleThreadExecutor(
                        task -> {
                            final Thread thread = new Thread(task, "porelo-build-reader-call");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            return runner.submit(step::get).get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            process.destroyForcibly();
            throw new BuildReaderException(
                    executable + " did not answer within " + seconds(limit), ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BuildReaderException(executable + " failed: " + ex.getCause(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BuildReaderException("Interrupted while asking " + executable, ex);
        } finally {
            runner.shutdownNow();
        }
    }

    /** Ends an adapter's process and waits for it, so that it reads as ended at once. */
    private void ended(final Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(endWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(final @Nullable VarlinkClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (IOException ex) {
                // Closing a connection to an adapter that is being ended.
            }
        }
    }

    /**
     * Returns the interfaces of one family an adapter serves, for a refusal that names them.
     *
     * @param client The connection to it.
     * @param prefix Such as {@code org.fuin.porelo.Build}.
     * @param limit How long asking may take.
     * @return Their names; empty when it names none.
     */
    static List<String> interfaces(
            final VarlinkClient client, final String prefix, final Duration limit) {
        final Object interfaces =
                client.call("org.varlink.service.GetInfo", Map.of(), limit).get("interfaces");
        return interfaces instanceof List<?> listed
                ? listed.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(name -> name.startsWith(prefix))
                        .toList()
                : List.of();
    }
}
