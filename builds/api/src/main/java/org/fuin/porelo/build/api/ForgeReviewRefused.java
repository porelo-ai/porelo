package org.fuin.porelo.build.api;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The forge would not answer a review call, or a post's fate is unknown, for a reason Porelo acts
 * on; never the forge's own text and never the token.
 */
public final class ForgeReviewRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why, as the review contract names it. */
    public enum Reason {

        /** The forge limits how often it is asked, and the limit is reached. */
        RATE_LIMITED("RateLimited"),

        /** The forge refused the token: expired, revoked, wrong, or without the scope it needs. */
        CREDENTIAL_REFUSED("CredentialRefused"),

        /** The forge knows no pull request at that address, or the token may not see it. */
        NO_SUCH_PULL_REQUEST("NoSuchPullRequest"),

        /** A submission answers a thread the pull request does not hold. */
        NO_SUCH_THREAD("NoSuchThread"),

        /** The forge did not answer, or answered something that is not an answer. */
        UNREACHABLE("Unreachable"),

        /** A post was sent and no answer says whether it was written; it is never sent again. */
        WRITE_OUTCOME_UNKNOWN("WriteOutcomeUnknown"),

        /** What the forge holds or was asked to write is something profile 2 cannot carry. */
        NOT_IN_PROFILE("NotInProfile");

        private final String wire;

        Reason(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns the error's name on the wire, without the interface.
         *
         * @return It.
         */
        public String wire() {
            return wire;
        }

        /**
         * Returns the reason a wire name stands for.
         *
         * @param wire A fully qualified error name.
         * @return It, or {@code null} when the name is none of these.
         */
        static @Nullable Reason of(final @Nullable String wire) {
            for (final Reason reason : values()) {
                if (ReviewProtocol.error(reason).equals(wire)) {
                    return reason;
                }
            }
            return null;
        }
    }

    private final Reason reason;

    private final long retryAfterSeconds;

    private final String field;

    /**
     * Constructor.
     *
     * @param reason Why.
     */
    public ForgeReviewRefused(final Reason reason) {
        this(reason, 0, "");
    }

    private ForgeReviewRefused(
            final Reason reason, final long retryAfterSeconds, final String field) {
        super(message(reason, retryAfterSeconds, field));
        this.reason = reason;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        this.field = field;
    }

    /** Checks the arguments first, so a missing one is named rather than failing the message. */
    private static String message(
            final Reason reason, final long retryAfterSeconds, final String field) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(field, "field");
        return reason.wire()
                + (reason == Reason.RATE_LIMITED
                        ? " for " + Math.max(0, retryAfterSeconds) + " seconds"
                        : "")
                + (field.isEmpty() ? "" : ": " + field);
    }

    /**
     * Returns a rate limit.
     *
     * @param retryAfterSeconds When asking again can succeed; 0 when the forge did not say.
     * @return The refusal.
     */
    public static ForgeReviewRefused rateLimited(final long retryAfterSeconds) {
        return new ForgeReviewRefused(Reason.RATE_LIMITED, retryAfterSeconds, "");
    }

    /**
     * Returns a refusal of something profile 2 cannot carry.
     *
     * @param field The first point of it, such as {@code general comment}.
     * @return The refusal.
     */
    public static ForgeReviewRefused notInProfile(final String field) {
        return new ForgeReviewRefused(Reason.NOT_IN_PROFILE, 0, field);
    }

    /**
     * Returns why.
     *
     * @return The reason.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns when asking again can succeed, for {@link Reason#RATE_LIMITED}.
     *
     * @return Seconds; 0 when the forge did not say.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Returns what profile 2 cannot carry, for {@link Reason#NOT_IN_PROFILE}.
     *
     * @return The field; "" for any other reason.
     */
    public String field() {
        return field;
    }
}
