package org.fuin.porelo.build.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkNumbers;
import org.fuin.porelo.wire.varlink.VarlinkTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Porelo's side of {@link ReviewProtocol#INTERFACE}: one forge adapter executable, found as {@link
 * InstalledBuildReader#find} finds it, started on an owner-only socket, handshaken, and asked.
 *
 * <p>An adapter that will not start, serves no review, speaks another review version, or reads
 * another forge than the one asked for is refused here, with its own name in the message, before
 * anything is asked of it. It hands every document on as bytes and parses none: what a page holds
 * is read elsewhere, and a submission is sent as it was given.
 *
 * <p>One caller at a time: a question and its answer share one connection.
 */
public final class InstalledReviewReader implements AutoCloseable {

    private final Path executable;

    private final String forge;

    private final AdapterProcess process;

    /**
     * Starts an adapter and completes its handshake, every time limit at its default.
     *
     * @param executable The adapter.
     * @param forge The forge it must read.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @throws BuildReaderException When it does not start, serves no review, speaks another review
     *     version, or reads another forge.
     */
    public InstalledReviewReader(final Path executable, final String forge, final Path socket) {
        this(executable, forge, socket, LimitFactors.DEFAULT);
    }

    /**
     * Starts an adapter and completes its handshake.
     *
     * @param executable The adapter.
     * @param forge The forge it must read.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @param limits The factors its time limits follow.
     * @throws BuildReaderException When it does not start, serves no review, speaks another review
     *     version, or reads another forge.
     */
    public InstalledReviewReader(
            final Path executable,
            final String forge,
            final Path socket,
            final LimitFactors limits) {
        this.executable = executable;
        this.forge = forge;
        final Duration ready = InstalledBuildReader.readyLimit(limits);
        this.process =
                new AdapterProcess(
                        executable,
                        socket,
                        limits,
                        client -> handshake(client, executable, forge, ready));
    }

    /**
     * Returns the forge the adapter reads.
     *
     * @return Its name.
     */
    public String forge() {
        return forge;
    }

    /**
     * Asks for a pull request's facts.
     *
     * @param access The forge's API and the read token, lent for this call.
     * @param url The pull request's https address.
     * @return Its facts now.
     * @throws ForgeReviewRefused When the forge would not answer.
     * @throws BuildReaderException When the adapter failed, or answered something that is not a
     *     pull request's facts.
     */
    public ReviewFacts pullRequest(final ForgeAccess access, final String url) {
        final Map<String, Object> parameters = parameters(access);
        parameters.put("url", url);
        try {
            return ReviewFacts.of(call(ReviewProtocol.PULL_REQUEST, parameters));
        } catch (IllegalArgumentException ex) {
            throw new BuildReaderException(
                    executable
                            + " answered something that is not a pull request's facts: "
                            + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Asks for the open pull request from a branch; none is opened.
     *
     * @param access The forge's API and the read token, lent for this call.
     * @param upstream The repository as the project file names it.
     * @param branch The branch's name.
     * @return Its https address; {@code null} when none is open from it.
     * @throws ForgeReviewRefused When the forge would not answer.
     * @throws BuildReaderException When the adapter failed.
     */
    public @Nullable String pullRequestOf(
            final ForgeAccess access, final String upstream, final String branch) {
        final Map<String, Object> parameters = parameters(access);
        parameters.put("upstream", upstream);
        parameters.put("branch", branch);
        final Object url = call(ReviewProtocol.PULL_REQUEST_OF, parameters).get("url");
        if (url == null) {
            return null;
        }
        if (!(url instanceof String address) || address.isBlank()) {
            throw new BuildReaderException(
                    executable + " answered a pull request's address that is none: " + url);
        }
        return address;
    }

    /**
     * Asks for one page of a pull request's conversation.
     *
     * @param access The forge's API and the read token, lent for this call.
     * @param url The pull request's https address.
     * @param cursor Where the page starts, as the previous page said; {@code null} for the first.
     * @return The page's document as bytes, unread, and where the next page starts.
     * @throws ForgeReviewRefused When the forge would not answer.
     * @throws BuildReaderException When the adapter failed, or answered no document or one over
     *     {@link ReviewProtocol#MOST_PAGE_BYTES}.
     */
    public ConversationPage comments(
            final ForgeAccess access, final String url, final @Nullable String cursor) {
        final Map<String, Object> parameters = parameters(access);
        parameters.put("url", url);
        if (cursor != null) {
            parameters.put("cursor", cursor);
        }
        final Map<String, Object> answer = call(ReviewProtocol.COMMENTS, parameters);
        if (!(answer.get("document") instanceof String document)) {
            throw new BuildReaderException(executable + " answered a page without a document");
        }
        final Object next = answer.get("cursor");
        if (next != null && !(next instanceof String)) {
            throw new BuildReaderException(executable + " answered a cursor that is not text");
        }
        try {
            return ConversationPage.of(document, (String) next);
        } catch (IllegalArgumentException ex) {
            throw new BuildReaderException(
                    executable + " answered a page that is none: " + ex.getMessage(), ex);
        }
    }

    /**
     * Writes one released result, once: a call whose answer never came is {@link
     * ForgeReviewRefused.Reason#WRITE_OUTCOME_UNKNOWN}, never asked again.
     *
     * @param access The forge's API and the write token, lent for this call.
     * @param url The pull request's https address.
     * @param document One profile 2 submission of exactly one result, as UTF-8 bytes; sent as
     *     given.
     * @return The thread answered, or the new thread root's id.
     * @throws ForgeReviewRefused When the forge would not write it, or its outcome is unknown.
     * @throws BuildReaderException When the adapter failed before it wrote, or answered no thread.
     * @throws IllegalArgumentException When the document is not UTF-8.
     */
    public String post(final ForgeAccess access, final String url, final byte[] document) {
        final Map<String, Object> parameters = parameters(access);
        parameters.put("url", url);
        parameters.put("document", utf8(document));
        final Map<String, Object> answer;
        try {
            answer = process.call(ReviewProtocol.POST, parameters);
        } catch (VarlinkTimeoutException ex) {
            // Sent, and no answer says whether it was written: sending it again could write it
            // twice.
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
        } catch (VarlinkException ex) {
            if (ex.getErrorName() == null) {
                // The connection broke with the request out: whether it was written is unknown.
                throw new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
            }
            throw refusal(ex);
        }
        if (!(answer.get("thread") instanceof String thread) || !thread.matches("[0-9]{1,64}")) {
            throw new BuildReaderException(
                    executable + " answered a post without a thread's decimal id");
        }
        return thread;
    }

    @Override
    public void close() {
        process.close();
    }

    private static Map<String, Object> parameters(final ForgeAccess access) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("api", access.api());
        parameters.put("token", access.token());
        return parameters;
    }

    private static String utf8(final byte[] document) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(document))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("a submission is UTF-8", ex);
        }
    }

    /** Refuses an adapter that serves no review, another review version, or another forge. */
    private static void handshake(
            final VarlinkClient client,
            final Path executable,
            final String forge,
            final Duration readyLimit) {
        final Map<String, Object> answer;
        try {
            answer = client.call(ReviewProtocol.DESCRIBE, Map.of(), readyLimit);
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
                            + " serves no "
                            + ReviewProtocol.INTERFACE
                            + " (its review interfaces: "
                            + AdapterProcess.interfaces(
                                    client, "org.fuin.porelo.Review", readyLimit)
                            + "); this Porelo speaks "
                            + ReviewProtocol.INTERFACE
                            + ". Install the adapter's version that reads reviews for this Porelo.");
        }
        final long version = VarlinkNumbers.whole(answer.get("protocolVersion")).orElse(-1);
        if (version != ReviewProtocol.VERSION) {
            throw new BuildReaderException(
                    executable
                            + " speaks "
                            + ReviewProtocol.INTERFACE
                            + " version "
                            + version
                            + "; this Porelo speaks version "
                            + ReviewProtocol.VERSION
                            + ". Install the adapter's version that goes with this Porelo.");
        }
        final String reads = String.valueOf(answer.get("forge"));
        if (!forge.equals(reads)) {
            throw new BuildReaderException(
                    executable + " reads '" + reads + "', not '" + forge + "'");
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
            throw refusal(ex);
        }
    }

    /** A contract error as the refusal it names; anything else is the adapter's own fault. */
    private RuntimeException refusal(final VarlinkException ex) {
        final ForgeReviewRefused.Reason reason = ForgeReviewRefused.Reason.of(ex.getErrorName());
        final Map<String, Object> said = ex.getParameters() == null ? Map.of() : ex.getParameters();
        if (reason == ForgeReviewRefused.Reason.RATE_LIMITED) {
            return ForgeReviewRefused.rateLimited(InstalledBuildReader.retryAfter(said));
        }
        if (reason == ForgeReviewRefused.Reason.NOT_IN_PROFILE) {
            return ForgeReviewRefused.notInProfile(String.valueOf(said.getOrDefault("field", "")));
        }
        if (reason != null) {
            return new ForgeReviewRefused(reason);
        }
        return new BuildReaderException(
                executable
                        + " failed: "
                        + (ex.getErrorName() == null
                                ? ex.getMessage()
                                : ex.getErrorName() + " " + said),
                ex);
    }
}
