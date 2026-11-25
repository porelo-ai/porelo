package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CommentFields;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeComment;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.Withheld;
import org.jspecify.annotations.Nullable;

/**
 * Reads a pull request's whole conversation for a run: every page the forge adapter answers, each
 * through the one input path, each comment stamped with the adapter's forge account and checked for
 * format and size.
 *
 * <p>Nothing is filtered by id: Porelo keeps none of a forge's ids, so the reviewer's own earlier
 * posts are read like any other. The caller lends the read credential per call; it is kept in no
 * field.
 */
public final class ConversationRead {

    /** The reason of a conversation one of whose pages the input path refused. */
    public static final String PAGE_REFUSED = "PageRefused";

    /** The adapter's reason when the forge refused further reads for now. */
    public static final String RATE_LIMITED = "RateLimited";

    /** The rule a reply is withheld by when the run does not see its thread's opening comment. */
    public static final String NO_SUCH_THREAD = "reply: no such thread";

    /** The longest id or thread id a forge's page may carry. */
    private static final int ID_LIMIT = 64;

    /** The reason of a conversation whose pages say it goes on past the last one, or stop early. */
    public static final String PAGE_PARTIAL = "PagePartial";

    private final ForgeReview forge;
    private final ProfileReading reading;
    private final CommentCheck check;

    /**
     * Constructor with the adapter, the input path and the check.
     *
     * @param forge The forge adapter's review calls.
     * @param reading The one input path for profile documents.
     * @param check The check of each comment's format and size.
     */
    public ConversationRead(
            final ForgeReview forge, final ProfileReading reading, final CommentCheck check) {
        this.forge = Objects.requireNonNull(forge, "forge");
        this.reading = Objects.requireNonNull(reading, "reading");
        this.check = Objects.requireNonNull(check, "check");
    }

    /**
     * Reads the pull request's whole conversation.
     *
     * @param ref The pull request.
     * @param pinned The state the run reviews; each comment is checked against its push.
     * @param credential The read credential, lent for this call and kept nowhere.
     * @return Every comment kept or withheld, and how completely it was read.
     * @throws PlaceUnreadable As the adapter answered, when nothing could be read.
     */
    public Conversation read(
            final PullRequestRef ref, final Pinned pinned, final ForgeCredential credential)
            throws PlaceUnreadable {
        final ForgePages pages = forge.conversation(ref, credential);
        final ProfileChannel channel = new ProfileChannel.Adapter(ref.forge());
        final List<Kept> items = new ArrayList<>();
        final List<Withheld> withheld = new ArrayList<>();
        final List<byte[]> answered = pages.pages();
        final Set<String> ids = new HashSet<>();
        final Set<String> opened = new HashSet<>();
        final Set<String> kept = new HashSet<>();
        boolean refused = false;
        // A forge answers at least one page, so none at all is a short read.
        boolean shortened = answered.isEmpty();
        final List<ForgeComment> read = new ArrayList<>();
        for (int index = 0; index < answered.size(); index++) {
            final Optional<Page> page = page(answered.get(index), channel, ref.forge());
            if (page.isEmpty() || !fresh(page.get(), ids, opened)) {
                refused = true;
                continue;
            }
            // Only partial before the last page and complete on it say the pages are all there.
            final Completeness word = page.get().completeness();
            final boolean last = index == answered.size() - 1;
            if (word != (last ? Completeness.COMPLETE : Completeness.PARTIAL)) {
                shortened = true;
            }
            read.addAll(page.get().comments());
        }
        // Every opening comment the run keeps, wherever the read lists it: a reply listed before
        // its thread's opening comment still has a thread the run sees.
        final java.util.Map<ForgeComment, Optional<String>> opening =
                new java.util.IdentityHashMap<>();
        for (final ForgeComment comment : read) {
            if (comment.item() instanceof ReviewComment) {
                final Optional<String> rule = checked(pinned, comment);
                opening.put(comment, rule);
                if (rule.isEmpty()) {
                    kept.add(comment.thread());
                }
            }
        }
        for (final ForgeComment comment : read) {
            final @Nullable Optional<String> known = opening.get(comment);
            final Optional<String> rule = known != null ? known : withheldBy(pinned, comment, kept);
            if (rule.isPresent()) {
                withheld.add(new Withheld(comment.id(), comment.thread(), rule.get()));
            } else {
                items.add(comment.kept());
            }
        }
        // Pages the adapter never answered are the larger gap, so its reason is the one told.
        final @Nullable String reason =
                pages.reason() != null
                        ? adapterReason(pages.reason())
                        : refused ? PAGE_REFUSED : shortened ? PAGE_PARTIAL : null;
        return new Conversation(
                items,
                withheld,
                reason == null ? Completeness.COMPLETE : Completeness.PARTIAL,
                reason);
    }

    /** Passes only the adapter's reasons the contract names; any other is a short read. */
    private static String adapterReason(final String reason) {
        return RATE_LIMITED.equals(reason) ? RATE_LIMITED : PAGE_PARTIAL;
    }

    /**
     * Takes a page's ids and opened threads when none was taken before, on this page or an earlier
     * one; otherwise takes nothing, as the page is refused whole.
     */
    private static boolean fresh(final Page page, final Set<String> ids, final Set<String> opened) {
        final Set<String> pageIds = new HashSet<>();
        final Set<String> pageOpened = new HashSet<>();
        for (final ForgeComment comment : page.comments()) {
            if (ids.contains(comment.id()) || !pageIds.add(comment.id())) {
                return false;
            }
            if (comment.item() instanceof ReviewComment
                    && (opened.contains(comment.thread()) || !pageOpened.add(comment.thread()))) {
                return false;
            }
        }
        ids.addAll(pageIds);
        opened.addAll(pageOpened);
        return true;
    }

    /**
     * Returns the rule a comment is withheld by: a reply whose thread's opening comment the run
     * does not see, or the check's refusal.
     */
    private Optional<String> withheldBy(
            final Pinned pinned, final ForgeComment comment, final Set<String> kept) {
        if (comment.item() instanceof ForgeReply && !kept.contains(comment.thread())) {
            return Optional.of(NO_SUCH_THREAD);
        }
        return checked(pinned, comment);
    }

    /** The check's refusal of a comment, as its rule. */
    private Optional<String> checked(final Pinned pinned, final ForgeComment comment) {
        return check.check(pinned.push(), comment.item()).map(ConversationRead::rule);
    }

    /**
     * Reads one page through the input path, or nothing when the path refuses it or an item of it
     * is not a comment of an account at this forge: a page is taken whole or not at all.
     */
    private Optional<Page> page(
            final byte[] page, final ProfileChannel channel, final String host) {
        final ProfileRead read;
        try {
            read = reading.read(page, channel);
        } catch (final ProfileRefused refused) {
            return Optional.empty();
        }
        final List<ForgeComment> comments = new ArrayList<>();
        for (final ProfileItem item : read.items()) {
            final ForgeComment comment = comment(item, host);
            if (comment == null) {
                return Optional.empty();
            }
            comments.add(comment);
        }
        return Optional.of(new Page(comments, read.completeness()));
    }

    /** A page taken whole: its comments and what it says of itself. */
    private record Page(List<ForgeComment> comments, Completeness completeness) {}

    /**
     * Returns an item as a forge's comment, or null when the input path answered one that no forge
     * adapter's channel may carry: without an id or thread, or not by an account of this forge.
     */
    private static @Nullable ForgeComment comment(final ProfileItem item, final String host) {
        final String id = item.id();
        final String thread = item.thread();
        if (id == null
                || thread == null
                || !shown(id)
                || !shown(thread)
                || !(item.producer() instanceof ForgeAccount author)
                || !author.forge().equals(host)) {
            return null;
        }
        try {
            return new ForgeComment(id, thread, author, item.item(), item.at());
        } catch (final IllegalArgumentException notAForgeComment) {
            // A blank id, a task's reply or a reply naming another thread.
            return null;
        }
    }

    /** Whether a forge's id may be kept and shown: at most 64 characters, all printable. */
    private static boolean shown(final String id) {
        return id.length() <= ID_LIMIT && CommentFields.lineCharacterRule(id) == null;
    }

    /** Names a refusal's rule by the contract's error and its detail, never the text. */
    private static String rule(final ReviewRefusal refusal) {
        final String detail = refusal.detail();
        return detail == null
                ? refusal.error().wireName()
                : refusal.error().wireName() + ": " + detail;
    }
}
