package org.fuin.porelo.app;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestPinning;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;

/**
 * One pull request at a forge instance this machine knows, as a run's place: its name is its https
 * address, it is pinned through the task's gate, read and written through the instance's forge
 * adapter, and its comments are read as the instance's setting says.
 *
 * <p>A reviewer's pin fetches the pull request's head into its gate and then reads the facts, with
 * the forge's read credential lent for that call; facts of another head than the one pinned mean it
 * moved meanwhile, and nothing is pinned. An author's pin reads the facts first and is pinned to
 * their head only when that is the commit its own gate forwarded. A write reads the facts afresh
 * with the write credential, so an item held for a head the pull request has moved past is refused,
 * never written against another commit; a reply is written at its thread's place as the forge shows
 * it then.
 */
public final class PullRequestPlace implements ReviewPlace {

    private final PullRequestRef ref;
    private final ForgeInstance instance;
    private final PullRequestPinning pinning;
    private final ForgeReview forge;
    private final ProfileReading reading;
    private final CommentCheck check;
    private final ProfileWriting writing;
    private final ReleasedSender.Credentials credentials;
    private final Function<String, Role> roles;
    private final AuthorPins authors;

    /**
     * Pins an author's run at its own pull request, to the head the forge answered, once that is
     * the commit its own gate forwarded.
     */
    @FunctionalInterface
    public interface AuthorPins {

        /**
         * Pins it.
         *
         * @param task The task's container name.
         * @param facts The pull request's facts now.
         * @return The pinned push, its head the facts' head.
         * @throws PlaceUnreadable When the head is not the one its gate forwarded, or the gate
         *     cannot be read.
         */
        Push pin(String task, PullRequestFacts facts) throws PlaceUnreadable;
    }

    /**
     * Constructor.
     *
     * @param ref The pull request.
     * @param instance The forge instance its host names, read and with its adapter.
     * @param pinning Fetches and pins its head into a task's gate.
     * @param forge The instance's forge adapter's review calls.
     * @param reading The one input path for profile documents.
     * @param check The check of each comment's format and size against the pinned push.
     * @param writing The one writer of profile 2.
     * @param credentials Lends the forge's read credential for a pin's facts.
     * @param roles A task's role, as its run recorded it.
     * @param authors Pins an author at its own pull request.
     */
    public PullRequestPlace(
            final PullRequestRef ref,
            final ForgeInstance instance,
            final PullRequestPinning pinning,
            final ForgeReview forge,
            final ProfileReading reading,
            final CommentCheck check,
            final ProfileWriting writing,
            final ReleasedSender.Credentials credentials,
            final Function<String, Role> roles,
            final AuthorPins authors) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.instance = Objects.requireNonNull(instance, "instance");
        this.pinning = Objects.requireNonNull(pinning, "pinning");
        this.forge = Objects.requireNonNull(forge, "forge");
        this.reading = Objects.requireNonNull(reading, "reading");
        this.check = Objects.requireNonNull(check, "check");
        this.writing = Objects.requireNonNull(writing, "writing");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.authors = Objects.requireNonNull(authors, "authors");
        if (instance.adapter() == null || !instance.host().equals(ref.forge())) {
            throw new IllegalArgumentException(
                    "a pull request's place is at a readable instance of its own host");
        }
    }

    /**
     * Returns the pull request.
     *
     * @return It.
     */
    public PullRequestRef ref() {
        return ref;
    }

    /**
     * Returns the vault entry the pull request is read with: its forge's credential.
     *
     * @return For example {@code git.token.github.com}.
     */
    public String instanceCredential() {
        return instance.credential();
    }

    /**
     * Reads the pull request's facts now.
     *
     * @param credential The read credential, lent for this call.
     * @return Its facts.
     * @throws PlaceUnreadable As the forge answered, such as {@code NoSuchPullRequest}.
     */
    public PullRequestFacts facts(final ForgeCredential credential) throws PlaceUnreadable {
        return forge.facts(ref, credential);
    }

    @Override
    public String name() {
        return ref.url().toString();
    }

    @Override
    public Pinned pin(final String task) throws PlaceUnreadable {
        if (roles.apply(task) == Role.AUTHOR) {
            // The pinning fetches only a reviewer's head; an author's is the one its gate sent.
            final PullRequestFacts facts =
                    forge.facts(ref, credentials.lend(instance.credential()));
            return new Pinned(authors.pin(task, facts), facts);
        }
        final Push push = pinning.pin(ref, task);
        final PullRequestFacts facts = forge.facts(ref, credentials.lend(instance.credential()));
        if (!facts.head().equals(push.head())) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.MOVED_SINCE_REVIEW,
                    "the pull request " + name() + " moved while it was pinned; start again");
        }
        return new Pinned(push, facts);
    }

    @Override
    public Conversation conversation(final Pinned pinned, final ForgeCredential credential)
            throws PlaceUnreadable {
        return new ConversationRead(forge, reading, check).read(ref, pinned, credential);
    }

    @Override
    public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
        return writing.pages(facts(pinned), conversation);
    }

    @Override
    public WriteOutcome write(final Held held, final ForgeCredential credential) {
        final PullRequestFacts now;
        try {
            now = forge.facts(ref, credential);
        } catch (PlaceUnreadable ex) {
            return new WriteOutcome.Refused(ex.reason().wireName());
        }
        LineAnchor thread = null;
        if (!(held.item() instanceof ReviewComment)) {
            try {
                thread = threadPlace(held, credential);
            } catch (PlaceUnreadable ex) {
                return new WriteOutcome.Refused(ex.reason().wireName());
            }
        }
        return new ForgeWrite(forge, writing).write(ref, now, held, thread, credential);
    }

    /**
     * Finds where the thread a reply answers stands, as the forge shows its opening comment now,
     * read through the one input path; null when the forge shows no such thread.
     */
    private @Nullable LineAnchor threadPlace(final Held held, final ForgeCredential credential)
            throws PlaceUnreadable {
        final String thread =
                switch (held.item()) {
                    case org.fuin.porelo.gate.review.ForgeReply reply -> reply.thread();
                    // A thread of Porelo's own is never on the forge; the write refuses it unread.
                    case org.fuin.porelo.gate.review.ReviewReply reply -> null;
                    case ReviewComment comment -> null;
                };
        if (thread == null) {
            return null;
        }
        final ProfileChannel channel = new ProfileChannel.Adapter(ref.forge());
        for (final byte[] page : forge.conversation(ref, credential).pages()) {
            final List<ProfileItem> items;
            try {
                items = reading.read(page, channel).items();
            } catch (ProfileRefused refused) {
                // A page the input path refuses shows nothing; another may show the thread.
                continue;
            }
            for (final ProfileItem item : items) {
                if (thread.equals(item.thread()) && item.item() instanceof ReviewComment root) {
                    return switch (root.anchor()) {
                        case LineAnchor line -> line;
                    };
                }
            }
        }
        return null;
    }

    @Override
    public CommentReading reading() {
        return instance.reading();
    }

    private PullRequestFacts facts(final Pinned pinned) {
        final PullRequestFacts facts = pinned.facts();
        if (facts == null) {
            throw new IllegalArgumentException("a pull request's run is pinned with its facts");
        }
        return facts;
    }

    /** Names the place, never a credential. */
    @Override
    public String toString() {
        return "PullRequestPlace[" + name() + "]";
    }
}
