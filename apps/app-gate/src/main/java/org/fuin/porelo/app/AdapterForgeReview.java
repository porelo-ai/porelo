package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.build.api.BuildReaderException;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.build.api.InstalledReviewReader;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * A forge instance's review calls, made through its installed forge adapter: the adapter is started
 * for each call on an owner-only socket of its own, handed the credential lent for that call, and
 * stopped. Nothing of the forge's format is read here: pages and submissions pass as bytes.
 *
 * <p>The adapter's refusals become the contract's: {@code NoSuchPullRequest} and {@code
 * Unreachable} as {@link PlaceUnreadable}, a rate limit while reading as a short conversation, and
 * on a post a refusal by its word, a rate limit as a wait, and anything unclear as {@link
 * WriteOutcome.OutcomeUnknown}. An adapter that fails is unreachable; its message, which may echo
 * the token, is never passed on.
 */
public final class AdapterForgeReview implements ForgeReview {

    /** The most pages one conversation is read in, so an adapter that never ends is cut off. */
    static final int MOST_PAGES = 1000;

    /** One started adapter, asked once and closed. */
    interface Session extends AutoCloseable {

        /**
         * As {@link InstalledReviewReader#pullRequest}.
         *
         * @param access The API and the lent token.
         * @param url The pull request.
         * @return Its facts.
         */
        ReviewFacts pullRequest(ForgeAccess access, String url);

        /**
         * As {@link InstalledReviewReader#comments}.
         *
         * @param access The API and the lent token.
         * @param url The pull request.
         * @param cursor Where the page starts, or null.
         * @return The page.
         */
        ConversationPage comments(ForgeAccess access, String url, @Nullable String cursor);

        /**
         * As {@link InstalledReviewReader#post}.
         *
         * @param access The API and the lent token.
         * @param url The pull request.
         * @param document The submission.
         * @return The thread's native id.
         */
        String post(ForgeAccess access, String url, byte[] document);

        @Override
        void close();
    }

    /** Starts the adapter for one call. */
    @FunctionalInterface
    interface Sessions {

        /**
         * Starts it.
         *
         * @return The started adapter.
         * @throws BuildReaderException When it is not installed or does not start.
         */
        Session open();
    }

    private final Sessions sessions;

    /**
     * Constructor with how the adapter is started.
     *
     * @param sessions Starts the adapter for each call.
     */
    AdapterForgeReview(final Sessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * Returns the review calls of an installed adapter on this machine.
     *
     * @param context The machine.
     * @param adapter The adapter's name, which is the forge kind it reads.
     * @return The calls.
     */
    public static AdapterForgeReview of(final PoreloContext context, final String adapter) {
        return new AdapterForgeReview(() -> installed(context, adapter));
    }

    @Override
    public PullRequestFacts facts(final PullRequestRef ref, final ForgeCredential credential)
            throws PlaceUnreadable {
        final ReviewFacts facts;
        try (Session session = open()) {
            facts = session.pullRequest(access(credential), ref.url().toString());
        } catch (ForgeReviewRefused ex) {
            throw unreadable(ex, ref);
        } catch (RuntimeException ex) {
            throw failed(ref);
        }
        try {
            return new PullRequestFacts(
                    ref,
                    facts.repository(),
                    facts.head(),
                    facts.base(),
                    facts.mergeBase(),
                    switch (facts.state()) {
                        case OPEN -> PullRequestState.OPEN;
                        case MERGED -> PullRequestState.MERGED;
                        case CLOSED -> PullRequestState.CLOSED;
                    },
                    facts.approved(),
                    facts.mergeCommit(),
                    facts.mergedHead(),
                    facts.decidedAt(),
                    viewer(facts.account(), ref));
        } catch (IllegalArgumentException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the forge adapter answered facts of " + ref.url() + " that do not read");
        }
    }

    /** The account the token stands for, at the pull request's forge; none when none was said. */
    private static @Nullable ForgeAccount viewer(
            final ReviewFacts.@Nullable Account account, final PullRequestRef ref) {
        return account == null
                ? null
                : new ForgeAccount(ref.forge(), account.id(), account.login());
    }

    @Override
    public ForgePages conversation(final PullRequestRef ref, final ForgeCredential credential)
            throws PlaceUnreadable {
        final List<byte[]> pages = new ArrayList<>();
        try (Session session = open()) {
            final ForgeAccess access = access(credential);
            String cursor = null;
            do {
                if (pages.size() == MOST_PAGES) {
                    return new ForgePages(pages, ConversationRead.PAGE_PARTIAL);
                }
                final ConversationPage page =
                        session.comments(access, ref.url().toString(), cursor);
                pages.add(page.document());
                cursor = page.cursor();
            } while (cursor != null);
        } catch (ForgeReviewRefused ex) {
            if (pages.isEmpty()) {
                throw unreadable(ex, ref);
            }
            // What was read is handed on; the rest is said to be missing.
            return new ForgePages(
                    pages,
                    ex.reason() == ForgeReviewRefused.Reason.RATE_LIMITED
                            ? ConversationRead.RATE_LIMITED
                            : ConversationRead.PAGE_PARTIAL);
        } catch (RuntimeException ex) {
            if (pages.isEmpty()) {
                throw failed(ref);
            }
            return new ForgePages(pages, ConversationRead.PAGE_PARTIAL);
        }
        return new ForgePages(pages, null);
    }

    @Override
    public WriteOutcome post(
            final PullRequestRef ref, final byte[] document, final ForgeCredential credential) {
        final Session session;
        try {
            session = open();
        } catch (RuntimeException ex) {
            // Never started, so nothing was sent.
            return new WriteOutcome.Refused(PlaceUnreadable.Reason.UNREACHABLE.wireName());
        }
        try (session) {
            return new WriteOutcome.Delivered(
                    session.post(access(credential), ref.url().toString(), document));
        } catch (ForgeReviewRefused ex) {
            return switch (ex.reason()) {
                case RATE_LIMITED ->
                        new WriteOutcome.RateLimited(Duration.ofSeconds(ex.retryAfterSeconds()));
                case WRITE_OUTCOME_UNKNOWN -> new WriteOutcome.OutcomeUnknown();
                default -> new WriteOutcome.Refused(ex.reason().wire());
            };
        } catch (IllegalArgumentException ex) {
            // Refused before it was sent: too large or not UTF-8.
            return new WriteOutcome.Refused(ForgeWrite.NOT_IN_PROFILE);
        } catch (RuntimeException ex) {
            // It may have gone out; sending it again could write it twice.
            return new WriteOutcome.OutcomeUnknown();
        }
    }

    private Session open() {
        return sessions.open();
    }

    /** The forge's own API: the adapter knows its forge's address. */
    private static ForgeAccess access(final ForgeCredential credential) {
        return new ForgeAccess("", credential.token());
    }

    private static PlaceUnreadable unreadable(
            final ForgeReviewRefused refused, final PullRequestRef ref) {
        return switch (refused.reason()) {
            case NO_SUCH_PULL_REQUEST ->
                    new PlaceUnreadable(
                            PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST,
                            "the forge knows no pull request at " + ref.url());
            case CREDENTIAL_REFUSED ->
                    new PlaceUnreadable(
                            PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                            "the forge at " + ref.forge() + " refused the token it was lent");
            default ->
                    new PlaceUnreadable(
                            PlaceUnreadable.Reason.UNREACHABLE,
                            "the forge at "
                                    + ref.forge()
                                    + " did not answer: "
                                    + refused.reason().wire());
        };
    }

    /** An adapter that failed; its own words may hold the token and are not repeated. */
    private static PlaceUnreadable failed(final PullRequestRef ref) {
        return new PlaceUnreadable(
                PlaceUnreadable.Reason.UNREACHABLE,
                "the forge adapter for " + ref.forge() + " failed or could not be started");
    }

    private static Session installed(final PoreloContext context, final String adapter) {
        final Path executable =
                InstalledBuildReader.find(adapter, ForgeAdapters.of(context).directories());
        final Path directory;
        try {
            final Path root = context.paths().tasks().buildQueries();
            Directories.create(
                    root,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            directory =
                    Files.createTempDirectory(
                            root,
                            "review-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final InstalledReviewReader reader;
        try {
            reader =
                    new InstalledReviewReader(
                            executable,
                            adapter,
                            directory.resolve("review.sock"),
                            context.limits());
        } catch (RuntimeException ex) {
            removed(directory);
            throw ex;
        }
        return new Session() {
            @Override
            public ReviewFacts pullRequest(final ForgeAccess access, final String url) {
                return reader.pullRequest(access, url);
            }

            @Override
            public ConversationPage comments(
                    final ForgeAccess access, final String url, final @Nullable String cursor) {
                return reader.comments(access, url, cursor);
            }

            @Override
            public String post(final ForgeAccess access, final String url, final byte[] document) {
                return reader.post(access, url, document);
            }

            @Override
            public void close() {
                try {
                    reader.close();
                } finally {
                    removed(directory);
                }
            }
        };
    }

    private static void removed(final Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("review.sock"));
            Files.deleteIfExists(directory);
        } catch (IOException ex) {
            // A query directory left behind holds no token: the socket's calls carried it.
        }
    }
}
