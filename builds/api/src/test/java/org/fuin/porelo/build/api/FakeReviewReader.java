package org.fuin.porelo.build.api;

import org.jspecify.annotations.Nullable;

/**
 * A forge adapter the tests start as an executable: {@link FakeReader}'s builds and fixed review
 * answers, so a test sees what crossed the socket.
 *
 * <p>A pull request ending in {@code /1} is open, one ending in {@code /2} merged, one ending in
 * {@code /404} unknown; the token {@code wrong} is refused. The branch {@code porelo/t} has {@link
 * #OPEN} open from it. The conversation is {@link #FIRST_PAGE} and then {@link #LAST_PAGE}, neither
 * of them profile 2, since Porelo reads none of it. A post's thread is the decimal hash of the
 * document it received, so a test sees the document crossed as given; one naming {@code rate} is
 * rate limited for 30 seconds, {@code general} is not in the profile, {@code orphan} answers no
 * thread, {@code unreachable} is unreachable, {@code broken} fails, {@code leak} fails with the
 * token in a long message, {@code leak-field} names the token as what profile 2 cannot carry,
 * {@code slow} answers after a minute, and {@code halt} ends the process before it answers. A page
 * asked for at cursor {@code slow} also answers after a minute. Each post received is written as a
 * line to the file {@link #POSTS} names, when it names one.
 */
public final class FakeReviewReader implements BuildReader, ReviewReader {

    /** The environment variable naming the file each received post is written to. */
    static final String POSTS = "FAKE_REVIEW_POSTS";

    /** The open pull request from {@code porelo/t}. */
    static final String OPEN = "https://forge.example/owner/name/pull/1";

    /** The first page's document: not JSON, and not ASCII. */
    static final String FIRST_PAGE = "page one: Grüße, not a profile document";

    /** The last page's document. */
    static final String LAST_PAGE = "{\"page\": 2}";

    private final FakeReader builds = new FakeReader();

    /**
     * Runs the fake as an adapter executable.
     *
     * @param arguments As every adapter takes them.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new FakeReviewReader(), arguments);
    }

    /**
     * Returns the thread a post of this document answers.
     *
     * @param document What was posted.
     * @return Its decimal hash.
     */
    static String threadOf(final String document) {
        return Integer.toString(document.hashCode() & Integer.MAX_VALUE);
    }

    @Override
    public String forge() {
        return builds.forge();
    }

    @Override
    public String head(final Target target, final String branch) {
        return builds.head(target, branch);
    }

    @Override
    public Build build(final Target target, final String commit, final Build.Logs logs) {
        return builds.build(target, commit, logs);
    }

    @Override
    public ReviewFacts pullRequest(final ForgeAccess access, final String url) {
        refuseWrongToken(access);
        if (url.endsWith("/1")) {
            return new ReviewFacts(
                    "https://forge.example/owner/name",
                    "a".repeat(40),
                    "b".repeat(40),
                    "c".repeat(40),
                    ReviewFacts.State.OPEN,
                    true,
                    null,
                    null,
                    null,
                    null);
        }
        if (url.endsWith("/2")) {
            return new ReviewFacts(
                    "https://forge.example/owner/name",
                    "a".repeat(40),
                    "b".repeat(40),
                    "c".repeat(40),
                    ReviewFacts.State.MERGED,
                    false,
                    "d".repeat(40),
                    "main",
                    java.time.Instant.parse("2026-11-20T10:15:30Z"),
                    null);
        }
        throw new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
    }

    @Override
    public @Nullable String pullRequestOf(
            final ForgeAccess access, final String upstream, final String branch) {
        refuseWrongToken(access);
        return "porelo/t".equals(branch) ? OPEN : null;
    }

    @Override
    public ConversationPage comments(
            final ForgeAccess access, final String url, final @Nullable String cursor) {
        refuseWrongToken(access);
        if ("slow".equals(cursor)) {
            sleep();
        }
        return cursor == null
                ? ConversationPage.of(FIRST_PAGE, "after-first")
                : ConversationPage.of(LAST_PAGE, null);
    }

    @Override
    public String post(final ForgeAccess access, final String url, final String document) {
        refuseWrongToken(access);
        received(document);
        if (document.contains("rate")) {
            throw ForgeReviewRefused.rateLimited(30);
        }
        if (document.contains("general")) {
            throw ForgeReviewRefused.notInProfile("general comment");
        }
        if (document.contains("orphan")) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_THREAD);
        }
        if (document.contains("unreachable")) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        if (document.contains("leak-field")) {
            throw ForgeReviewRefused.notInProfile("field " + access.token());
        }
        if (document.contains("leak")) {
            throw new IllegalStateException(
                    "Authorization: Bearer " + access.token() + " " + "z".repeat(5000));
        }
        if (document.contains("slow")) {
            sleep();
        }
        if (document.contains("broken")) {
            throw new IllegalStateException("the adapter broke");
        }
        if (document.contains("halt")) {
            // As an adapter that dies with the request sent is left: gone, with no answer.
            Runtime.getRuntime().halt(1);
        }
        return threadOf(document);
    }

    private static void received(final String document) {
        final String file = System.getenv(POSTS);
        if (file != null) {
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(file),
                        document.replace('\n', ' ') + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("cannot record a post", ex);
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void refuseWrongToken(final ForgeAccess access) {
        if ("wrong".equals(access.token())) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
        }
    }
}
