package org.fuin.porelo.gate;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} for one smart-HTTP request, in bytes.
 *
 * <p>Deliberately not routed through {@code CommandRunner}: that seam works in strings, and the
 * pack protocol is binary. Decoding a pack as text and re-encoding it corrupts it - the symptom is
 * {@code inflate: data stream error} from the receiving end, which says nothing about the cause.
 * Anything carrying pack data has to stay in bytes from end to end.
 */
public class GitSubprocess implements GitHttpServer.GitProcess {

    /** How long one git request may take. */
    static final java.time.Duration TIMEOUT = java.time.Duration.ofMinutes(5);

    private final String executable;

    private final long timeoutSeconds;

    /** Whether git runs without the account's and the system's configuration. */
    private final boolean withoutConfig;

    /** Constructor using the {@code git} on the path and a five-minute timeout. */
    public GitSubprocess() {
        this(org.fuin.porelo.wire.LimitFactors.DEFAULT);
    }

    /**
     * Constructor using the {@code git} on the path and {@link #TIMEOUT} under the account's
     * factors.
     *
     * @param limits The factors.
     */
    public GitSubprocess(final org.fuin.porelo.wire.LimitFactors limits) {
        this("git", timeout(limits), false);
    }

    /**
     * Returns how long one git request may take, in whole seconds, rounded up.
     *
     * @param limits The factors.
     * @return {@link #TIMEOUT} under {@code machines}.
     */
    static long timeout(final org.fuin.porelo.wire.LimitFactors limits) {
        return (limits.machines(TIMEOUT).toMillis() + 999) / 1000;
    }

    /**
     * Constructor.
     *
     * @param executable Program name or path.
     * @param timeoutSeconds How long a single request may take.
     */
    public GitSubprocess(String executable, long timeoutSeconds) {
        this(executable, timeoutSeconds, false);
    }

    private GitSubprocess(String executable, long timeoutSeconds, boolean withoutConfig) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
        this.withoutConfig = withoutConfig;
    }

    /**
     * Returns a runner of the {@code git} on the path that reads neither the account's nor the
     * system's configuration, for a judgement no setting of the host may change.
     *
     * @return The runner, with a five-minute timeout.
     */
    public static GitSubprocess withoutConfig() {
        return withoutConfig(org.fuin.porelo.wire.LimitFactors.DEFAULT);
    }

    /**
     * Returns a runner as {@link #withoutConfig()} does, with {@link #TIMEOUT} under the account's
     * factors.
     *
     * @param limits The factors.
     * @return The runner.
     */
    public static GitSubprocess withoutConfig(final org.fuin.porelo.wire.LimitFactors limits) {
        return new GitSubprocess("git", timeout(limits), true);
    }

    private ProcessBuilder configured(final ProcessBuilder builder) {
        if (withoutConfig) {
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        }
        return builder;
    }

    @Override
    public void stream(List<String> arguments, java.nio.file.Path input, OutputStream output)
            throws IOException {

        final List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(arguments);

        // Read from the file and written as it comes: a push or a fetch is held by neither side in
        // memory whole.
        transfer(
                arguments,
                configured(new ProcessBuilder(command)).redirectInput(input.toFile()),
                output,
                false);
    }

    /**
     * Runs git without input and hands its output to {@code output} as it comes, so the caller
     * keeps only what it needs; a git that exits other than 0 fails, as {@link #runOrFail} does.
     *
     * @param arguments The arguments after the program.
     * @param output Takes the output; an exception it throws ends git.
     * @throws IOException When git cannot be run, takes too long, exits other than 0, or the output
     *     cannot be taken.
     */
    public void streamOrFail(List<String> arguments, OutputStream output) throws IOException {
        final List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(arguments);
        transfer(arguments, configured(new ProcessBuilder(command)), output, true);
    }

    private void transfer(
            List<String> arguments,
            ProcessBuilder builder,
            OutputStream output,
            boolean mustSucceed)
            throws IOException {
        final Process process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // The limit runs from the start, on a thread of its own: a git that hangs is ended, and the
        // copy below with it.
        final Thread watch =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                                            process.destroyForcibly();
                                        }
                                    } catch (InterruptedException ex) {
                                        process.destroyForcibly();
                                    }
                                });
        try (java.io.InputStream in = process.getInputStream()) {
            if (builder.redirectInput().equals(ProcessBuilder.Redirect.PIPE)) {
                // Given no input, git reads an end at once.
                process.getOutputStream().close();
            }
            in.transferTo(output);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException(
                        "git "
                                + arguments.getFirst()
                                + " timed out after "
                                + timeoutSeconds
                                + " s");
            }
            if (mustSucceed && process.exitValue() != 0) {
                throw new IOException(
                        "git " + arguments.getFirst() + " exited " + process.exitValue());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", ex);
        } finally {
            process.destroyForcibly();
            watch.interrupt();
        }
    }

    @Override
    public byte[] run(List<String> arguments, byte[] input) throws IOException {
        return running(arguments, input, false);
    }

    @Override
    public byte[] runOrFail(List<String> arguments, byte[] input) throws IOException {
        return running(arguments, input, true);
    }

    private byte[] running(List<String> arguments, byte[] input, boolean mustSucceed)
            throws IOException {

        final List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(arguments);

        final Process process =
                configured(new ProcessBuilder(command))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();

        // Written on a separate thread: receive-pack answers while it is still reading, so writing
        // the whole request first can fill both pipe buffers and deadlock.
        final Thread writer =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (OutputStream out = process.getOutputStream()) {
                                        out.write(input);
                                    } catch (IOException ex) {
                                        // git closed its input early, which it is entitled to do.
                                    }
                                });

        // Read on a thread of its own, so the limit runs from the start: read here first, it began
        // only once git had closed its output, and a git that hung held its request for ever.
        final java.util.concurrent.CompletableFuture<byte[]> output =
                new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                output.complete(process.getInputStream().readAllBytes());
                            } catch (IOException ex) {
                                output.completeExceptionally(ex);
                            }
                        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException(
                        "git "
                                + arguments.getFirst()
                                + " timed out after "
                                + timeoutSeconds
                                + " s");
            }
            writer.join();
            final byte[] said = output.get(timeoutSeconds, TimeUnit.SECONDS);
            if (mustSucceed && process.exitValue() != 0) {
                throw new IOException(
                        "git " + arguments.getFirst() + " exited " + process.exitValue());
            }
            return said;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IOException(
                    "git " + arguments.getFirst() + " could not be read: " + ex.getCause(), ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IOException(
                    "git " + arguments.getFirst() + " timed out after " + timeoutSeconds + " s",
                    ex);
        } finally {
            // Whatever ended the request, git does not outlive it.
            process.destroyForcibly();
        }
    }
}
