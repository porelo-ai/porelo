package org.fuin.porelo.build.api;

/**
 * The review contract between Porelo and a forge adapter, served beside {@link
 * BuildProtocol#INTERFACE} on the adapter's one socket.
 *
 * <p>Versioned apart from the build contract, so a change to one never makes an adapter misread the
 * other: Porelo refuses an adapter whose review version it does not know.
 */
public final class ReviewProtocol {

    /** Varlink interface both sides implement. */
    public static final String INTERFACE = "org.fuin.porelo.Review1";

    /**
     * Version of this contract; raised when a change would make an older Porelo misread a newer
     * adapter, or the reverse.
     */
    public static final int VERSION = 1;

    /** Returns the forge an adapter reads and the review version it speaks. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** Returns a pull request's facts. */
    public static final String PULL_REQUEST = INTERFACE + ".PullRequest";

    /** Returns the open pull request from a branch, opening none. */
    public static final String PULL_REQUEST_OF = INTERFACE + ".PullRequestOf";

    /** Returns one page of a pull request's conversation. */
    public static final String COMMENTS = INTERFACE + ".Comments";

    /** Writes one released result. */
    public static final String POST = INTERFACE + ".Post";

    /** Marks one thread of a pull request resolved. */
    public static final String RESOLVE = INTERFACE + ".Resolve";

    /** The most bytes of one page's document. */
    public static final int MOST_PAGE_BYTES = 1024 * 1024;

    /** The most results one page holds. */
    public static final int MOST_PAGE_RESULTS = 100;

    private ReviewProtocol() {
        throw new UnsupportedOperationException("Constants");
    }

    /**
     * Returns a refusal's fully qualified error name.
     *
     * @param reason One of {@link ForgeReviewRefused.Reason}.
     * @return The name on the wire.
     */
    static String error(final ForgeReviewRefused.Reason reason) {
        return INTERFACE + "." + reason.wire();
    }
}
