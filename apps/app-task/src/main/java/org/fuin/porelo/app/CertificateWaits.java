package org.fuin.porelo.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The starts waiting for a certificate from the person's own computer, by an id each was told.
 *
 * <p>An interface asks a daemon on a host to start a task and signs with the agent at the person's
 * computer, which never leaves it. The request goes out on the start's own stream and the answer
 * comes on another connection, so a wait is held here between the two. What comes back is checked
 * by {@link TaskCertification} as any other.
 */
public final class CertificateWaits {

    /** How long a start waits for a person's computer. */
    public static final Duration LIMIT = Duration.ofMinutes(5);

    /** A certificate line is a few hundred bytes; the same as a start from a terminal reads. */
    private static final int MAX_LINE = StdinCertifier.MAX_LINE;

    /** What a person's words may take of a refusal. */
    private static final int MAX_WORDS = 500;

    private static final long POLL_MILLIS = 200;

    /** The start's side of a wait: how it asks and whether anyone is still there to answer. */
    public interface Asker {

        /**
         * Sends the request, on the start's own stream.
         *
         * @param request The whole reply.
         */
        void ask(Map<String, Object> request);

        /**
         * Tells whether whoever asked for the start is still there.
         *
         * @return Whether they are.
         */
        boolean open();
    }

    /** What came back: a certificate, or the words the start ends with. */
    private record Answer(@Nullable String certificate, @Nullable String refused) {}

    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();

    private final Duration limit;

    private final Clock clock;

    /** Creates the waits of a daemon. */
    public CertificateWaits() {
        this(LIMIT, Clock.systemUTC());
    }

    /**
     * Constructor.
     *
     * @param limit How long a start waits.
     * @param clock The time.
     */
    CertificateWaits(final Duration limit, final Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /**
     * Answers a waiting start, with one of the two.
     *
     * @param start The id it was told.
     * @param certificate The certificate line, or {@code null}.
     * @param refused The words it ends with, which may be empty, or {@code null}.
     * @return Whether a start was waiting by that id; {@code false} changes nothing.
     * @throws IllegalArgumentException With both or neither, which changes nothing.
     */
    public boolean answer(
            final String start,
            final @Nullable String certificate,
            final @Nullable String refused) {
        if ((certificate == null) == (refused == null)) {
            throw new IllegalArgumentException(
                    "Certify takes a certificate or the words it was refused with");
        }
        return certificate != null ? certificate(start, certificate) : refused(start, refused);
    }

    /**
     * Gives a waiting start its certificate.
     *
     * @param start The id it was told.
     * @param certificate The certificate line.
     * @return Whether a start was waiting by that id; {@code false} changes nothing.
     */
    public boolean certificate(final String start, final String certificate) {
        return certificate.length() > MAX_LINE
                ? refused(start, "it sent a line longer than a certificate")
                : answer(start, new Answer(certificate.strip(), null));
    }

    /**
     * Ends a waiting start with the person's words.
     *
     * @param start The id it was told.
     * @param words Why.
     * @return Whether a start was waiting by that id; {@code false} changes nothing.
     */
    public boolean refused(final String start, final String words) {
        return answer(start, new Answer(null, cleaned(words)));
    }

    private boolean answer(final String start, final Answer answer) {
        final CompletableFuture<Answer> wait = waiting.remove(start);
        return wait != null && wait.complete(answer);
    }

    /** They reach a person's screen and a task's log, as one line of a bounded length. */
    private static String cleaned(final String words) {
        final String line = StartStep.cleanLines(words).replaceAll("\\s+", " ").strip();
        return line.codePointCount(0, line.length()) > MAX_WORDS
                ? line.substring(0, line.offsetByCodePoints(0, MAX_WORDS))
                : line;
    }

    /**
     * The number of starts waiting.
     *
     * @return How many.
     */
    int size() {
        return waiting.size();
    }

    /**
     * A certifier asking through one start's stream.
     *
     * @param asker The start's side.
     * @param certifiers The keys that may certify, so the person's computer picks one it holds.
     * @return The certifier.
     */
    TaskCertification.Certifier certifier(final Asker asker, final List<String> certifiers) {
        return (key, principal, keyId, validBefore) ->
                await(asker, certifiers, key, principal, keyId, validBefore);
    }

    private String await(
            final Asker asker,
            final List<String> certifiers,
            final String key,
            final String principal,
            final String keyId,
            final Instant validBefore) {
        final String start = UUID.randomUUID().toString();
        final CompletableFuture<Answer> wait = new CompletableFuture<>();
        waiting.put(start, wait);
        try {
            final Map<String, Object> request = new LinkedHashMap<>();
            request.put("start", start);
            request.put("key", key);
            request.put("principal", principal);
            request.put("key_id", keyId);
            request.put("valid_before", validBefore.toString());
            request.put("certifiers", List.copyOf(certifiers));
            asker.ask(Map.of(StdinCertifier.REQUEST, request));
            final Answer answer = answered(wait, asker, principal);
            if (answer.refused() != null) {
                throw new TaskCertification.Refused(
                        "the person's computer did not certify "
                                + principal
                                + (answer.refused().isEmpty()
                                        ? ", and gave no reason"
                                        : ": " + answer.refused()));
            }
            return java.util.Objects.requireNonNull(answer.certificate());
        } finally {
            waiting.remove(start);
        }
    }

    private Answer answered(
            final CompletableFuture<Answer> wait, final Asker asker, final String principal) {
        final Instant end = clock.instant().plus(limit);
        try {
            while (true) {
                try {
                    return wait.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    if (!asker.open()) {
                        throw new TaskCertification.Refused(
                                "whoever asked for this start went away before certifying "
                                        + principal);
                    }
                    if (!clock.instant().isBefore(end)) {
                        throw new TaskCertification.Refused(
                                "no certificate came for "
                                        + principal
                                        + " within "
                                        + limit.toMinutes()
                                        + " minutes");
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TaskCertification.Refused(
                    "the wait for a certificate for " + principal + " was interrupted");
        } catch (ExecutionException ex) {
            throw new TaskCertification.Refused(
                    "no certificate came for " + principal + ": " + ex.getCause());
        }
    }
}
