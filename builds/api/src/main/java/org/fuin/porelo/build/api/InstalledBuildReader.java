package org.fuin.porelo.build.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Porelo's side of {@link BuildProtocol#INTERFACE}: one reader executable, started, handshaken, and
 * asked.
 *
 * <p>A reader that will not start, speaks another protocol version, or reads another forge than the
 * one asked for is refused here, with its own name in the message, before anything is asked of it.
 * A reader whose process has ended - killed for not answering in time, or gone by itself - is
 * started again at the next question.
 *
 * <p>One caller at a time: a question and its answer share one connection.
 */
public final class InstalledBuildReader implements AutoCloseable {

    /** Where Porelo's packages install a reader. */
    public static final Path PACKAGED = Path.of("/usr/libexec/porelo/builds");

    /** How long a reader may take to start and to describe itself. */
    static final long READY_SECONDS = 10;

    /**
     * How long one question may take: above the 300 s a reader bounds a whole call by itself, so
     * the reader's own answer, naming why, arrives before Porelo gives up.
     */
    static final long CALL_SECONDS = 330;

    /** How long a reader asked to end, or killed, has to be gone. */
    static final Duration END_WAIT = Duration.ofSeconds(5);

    /**
     * Returns how long a reader may take to start and to describe itself.
     *
     * @param limits The factors.
     * @return {@link #READY_SECONDS} under {@code machines}.
     */
    static Duration readyLimit(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.machines(Duration.ofSeconds(READY_SECONDS));
    }

    /**
     * Returns how long one question may take.
     *
     * @param limits The factors.
     * @return {@link #CALL_SECONDS} under {@code services}.
     */
    static Duration callLimit(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.services(Duration.ofSeconds(CALL_SECONDS));
    }

    /**
     * Returns how long a reader asked to end, or killed, has to be gone.
     *
     * @param limits The factors.
     * @return {@link #END_WAIT} under {@code machines}.
     */
    static Duration endWait(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.machines(END_WAIT);
    }

    private final Path executable;

    private final String forge;

    private final AdapterProcess process;

    /**
     * Returns the reader of a forge, from the first of the directories that holds one.
     *
     * @param forge The forge, as a project file names it.
     * @param directories Where to look, in order: the account's own first, then {@link #PACKAGED}.
     * @return The executable.
     * @throws BuildReaderException When none holds it, naming where it looked.
     */
    public static Path find(final String forge, final List<Path> directories) {
        if (!forge.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new BuildReaderException("'" + forge + "' is not a build reader's name");
        }
        for (final Path directory : directories) {
            final Path candidate = directory.resolve(forge);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new BuildReaderException(
                "No build reader for '"
                        + forge
                        + "' is installed; looked in "
                        + directories
                        + ". Install the package that reads builds from it.");
    }

    /**
     * Starts a reader and completes its handshake, every time limit at its default.
     *
     * @param executable The reader.
     * @param forge The forge it must read.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @throws BuildReaderException When it does not start, speaks another protocol, or reads
     *     another forge.
     */
    public InstalledBuildReader(final Path executable, final String forge, final Path socket) {
        this(executable, forge, socket, org.fuin.porelo.wire.LimitFactors.DEFAULT);
    }

    /**
     * Starts a reader and completes its handshake.
     *
     * @param executable The reader.
     * @param forge The forge it must read.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @param limits The factors its time limits follow.
     * @throws BuildReaderException When it does not start, speaks another protocol, or reads
     *     another forge.
     */
    public InstalledBuildReader(
            final Path executable,
            final String forge,
            final Path socket,
            final org.fuin.porelo.wire.LimitFactors limits) {
        this.executable = executable;
        this.forge = forge;
        final Duration ready = readyLimit(limits);
        this.process =
                new AdapterProcess(
                        executable,
                        socket,
                        limits,
                        client -> handshake(client, executable, forge, ready));
    }

    /**
     * Returns the forge the reader reads.
     *
     * @return Its name.
     */
    public String forge() {
        return forge;
    }

    /**
     * Asks where a branch points.
     *
     * @param target Where and with what.
     * @param branch The branch's name.
     * @return The full sha; "" when the forge has no such branch.
     * @throws BuildRefused When the forge would not answer.
     * @throws BuildReaderException When the reader failed.
     */
    public String head(final Target target, final String branch) {
        final Map<String, Object> parameters = parameters(target);
        parameters.put("branch", branch);
        final Object commit = call(BuildProtocol.HEAD, parameters).get("commit");
        return commit == null ? "" : String.valueOf(commit);
    }

    /**
     * Asks what the build of a commit did.
     *
     * @param target Where and with what.
     * @param commit The full sha.
     * @param logs Which jobs' logs to hand over.
     * @return The build.
     * @throws BuildRefused When the forge would not answer.
     * @throws BuildReaderException When the reader failed, or answered something that is not a
     *     build.
     */
    public Build build(final Target target, final String commit, final Build.Logs logs) {
        final Map<String, Object> parameters = parameters(target);
        parameters.put("commit", commit);
        parameters.put("logs", logs.wire());
        try {
            return Build.of(call(BuildProtocol.BUILD, parameters));
        } catch (IllegalArgumentException ex) {
            throw new BuildReaderException(
                    executable + " answered something that is not a build: " + ex.getMessage(), ex);
        }
    }

    /**
     * Asks the reader to open a pull request from a branch, or to answer the open one.
     *
     * @param target Where and with what.
     * @param branch The branch.
     * @param title Its title, or {@code null} for the reader's own.
     * @return The pull request; {@code null} when this reader opens none, either because it says so
     *     or because it was built before the question existed.
     * @throws BuildRefused When the forge would not answer or not open it.
     * @throws BuildReaderException When the reader failed, or answered without an address.
     */
    public @Nullable PullRequest openPullRequest(
            final Target target, final String branch, final @Nullable String title) {
        final Map<String, Object> parameters = parameters(target);
        parameters.put("branch", branch);
        if (title != null) {
            parameters.put("title", title);
        }
        final Map<String, Object> answer;
        try {
            answer = call(BuildProtocol.OPEN_PULL_REQUEST, parameters);
        } catch (BuildReaderException ex) {
            if (ex.getCause() instanceof VarlinkException said
                    && ("org.varlink.service.MethodNotFound".equals(said.getErrorName())
                            || "org.varlink.service.MethodNotImplemented"
                                    .equals(said.getErrorName()))) {
                return null;
            }
            throw ex;
        }
        try {
            return new PullRequest(
                    String.valueOf(answer.getOrDefault("url", "")),
                    Boolean.TRUE.equals(answer.get("opened")));
        } catch (IllegalArgumentException ex) {
            throw new BuildReaderException(
                    executable + " answered a pull request " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        process.close();
    }

    private static Map<String, Object> parameters(final Target target) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("upstream", target.upstream());
        parameters.put("api", target.api());
        parameters.put("token", target.token());
        return parameters;
    }

    /**
     * Returns the command that starts a reader: the one place it is said.
     *
     * @param executable The reader.
     * @param socket Its socket.
     * @param limits The factors its starter holds, handed on before the mode.
     * @return The command.
     */
    static List<String> command(
            final Path executable,
            final Path socket,
            final org.fuin.porelo.wire.LimitFactors limits) {
        return List.of(
                executable.toString(),
                org.fuin.porelo.wire.LimitFactors.OPTION,
                limits.line(),
                "serve",
                socket.toString());
    }

    /** Refuses a reader that speaks another protocol version or reads another forge. */
    private static void handshake(
            final VarlinkClient client,
            final Path executable,
            final String forge,
            final Duration readyLimit) {
        final Map<String, Object> answer = describe(client, executable, readyLimit);
        final long version =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.whole(answer.get("protocolVersion"))
                        .orElse(-1);
        if (version != BuildProtocol.VERSION) {
            throw new BuildReaderException(
                    executable
                            + " speaks "
                            + BuildProtocol.INTERFACE
                            + " version "
                            + version
                            + "; this Porelo speaks version "
                            + BuildProtocol.VERSION
                            + ". Install the reader's version that goes with this Porelo.");
        }
        final String reads = String.valueOf(answer.get("forge"));
        if (!forge.equals(reads)) {
            throw new BuildReaderException(
                    executable + " reads '" + reads + "', not '" + forge + "'");
        }
    }

    private static Map<String, Object> describe(
            final VarlinkClient client, final Path executable, final Duration readyLimit) {
        try {
            return client.call(BuildProtocol.DESCRIBE, Map.of(), readyLimit);
        } catch (VarlinkTimeoutException ex) {
            throw new BuildReaderException(
                    executable + " did not answer within " + AdapterProcess.seconds(readyLimit),
                    ex);
        } catch (VarlinkException ex) {
            if (!"org.varlink.service.MethodNotFound".equals(ex.getErrorName())) {
                throw ex;
            }
            throw new BuildReaderException(
                    executable
                            + " speaks "
                            + AdapterProcess.interfaces(client, "org.fuin.porelo.Build", readyLimit)
                            + "; this Porelo speaks "
                            + BuildProtocol.INTERFACE
                            + ". Install the reader's version that goes with this Porelo.");
        }
    }

    private Map<String, Object> call(final String method, final Map<String, Object> parameters) {
        try {
            return process.call(method, parameters);
        } catch (VarlinkTimeoutException ex) {
            throw new BuildReaderException(
                    executable
                            + " did not answer within "
                            + AdapterProcess.seconds(process.callLimit()),
                    ex);
        } catch (VarlinkException ex) {
            final BuildRefused.Reason reason = BuildRefused.Reason.of(ex.getErrorName());
            final Map<String, Object> said =
                    ex.getParameters() == null ? Map.of() : ex.getParameters();
            if (reason != null) {
                throw new BuildRefused(
                        reason, String.valueOf(said.getOrDefault("detail", "")), retryAfter(said));
            }
            throw new BuildReaderException(
                    executable
                            + " failed: "
                            + (ex.getErrorName() == null
                                    ? ex.getMessage()
                                    : ex.getErrorName() + " " + said),
                    ex);
        }
    }

    /**
     * When a refusing reader said to ask again, in whole seconds.
     *
     * @param said The refusal's parameters.
     * @return The seconds, 0 when it said none.
     * @throws BuildReaderException When it said a fraction or anything but a whole number.
     */
    static long retryAfter(final Map<String, ?> said) {
        final Object given = said.get("retryAfter");
        if (given == null) {
            return 0;
        }
        return org.fuin.porelo.wire.varlink.VarlinkNumbers.whole(given)
                .orElseThrow(
                        () ->
                                new BuildReaderException(
                                        "retryAfter is a whole number of seconds, not " + given));
    }
}
