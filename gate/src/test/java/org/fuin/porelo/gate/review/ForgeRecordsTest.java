package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The records a review at a forge and a held item are made of: what each refuses. */
class ForgeRecordsTest {

    private static final String COMMIT = "a".repeat(40);
    private static final String DIGEST = "b".repeat(64);
    private static final ReviewItem REPLY = new ForgeReply("1001", new Body("Agreed."));

    @Test
    void aForgeAccountWithoutAnIdIsRefusedAndOneWithItIsShownByName() {
        // An account without its id could be any account of that name, renamed or not.
        assertThatThrownBy(() -> new ForgeAccount("github.com", "", "octocat"))
                .as("an empty account id")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForgeAccount("github.com", "583231", "octo\ncat"))
                .as("a name that would break the line it is shown on")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ForgeAccount("github.com", "583231", "octocat").words())
                .as("how it is shown")
                .isEqualTo("forge account octocat");
    }

    @Test
    void aForgeReplyWithoutAThreadIdIsRefused() {
        // A reply that names no thread would land in none, or in whichever a reader guesses.
        assertThatThrownBy(() -> new ForgeReply(" ", new Body("Agreed.")))
                .as("a blank thread id")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForgeReply("1".repeat(65), new Body("Agreed.")))
                .as("a thread id over profile 2's 64 characters")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHeldItemWithoutADigestOrAFullPinnedCommitIsRefused() {
        // A decision names the digest it was shown; without one a changed text could be released.
        assertThatThrownBy(() -> held(COMMIT, ""))
                .as("no digest")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> held("abc123", DIGEST))
                .as("a pinned commit not in full")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(held(COMMIT, DIGEST).state()).as("a whole one").isEqualTo(HeldState.UNDECIDED);
    }

    @Test
    void aPullRequestWhoseAddressIsNotHttpsIsRefused() {
        // Its address is handed to an adapter that reads with a lent token; never over plain http.
        assertThatThrownBy(() -> PullRequestRef.of(URI.create("http://github.com/o/r/pull/12")))
                .as("http")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullRequestRef.of(URI.create("https://me@github.com/o/r/pull/12")))
                .as("a user in the address")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PullRequestRef(
                                        "gitlab.com", URI.create("https://github.com/o/r/pull/12")))
                .as("a forge other than the address's host")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PullRequestRef.of(URI.create("https://github.com/o/r/pull/12")).forge())
                .as("the forge is the address's host")
                .isEqualTo("github.com");
    }

    @Test
    void aDecisionFitsOnlyItsDirection() {
        // Releasing an incoming comment would make no sense; passing an outgoing one neither.
        assertThat(Decision.RELEASE.fits(Direction.OUTGOING)).as("release outgoing").isTrue();
        assertThat(Decision.RELEASE.fits(Direction.INCOMING)).as("release incoming").isFalse();
        assertThat(Decision.PASS.fits(Direction.INCOMING)).as("pass incoming").isTrue();
        assertThat(Decision.DISMISS.fits(Direction.OUTGOING)).as("dismiss outgoing").isFalse();
    }

    @Test
    void aForgeReplyIsJudgedAsAReplyIs() {
        // The same text rules for every reply, whichever thread it names.
        assertThat(CommentFields.check(new ForgeReply("1001", new Body(""))))
                .as("an empty forge reply")
                .contains(new ReviewRefusal(ReviewError.TEXT_REFUSED, "empty"));
        assertThat(CommentFields.check(REPLY)).as("a forge reply with words").isEmpty();
    }

    @Test
    void theGatesProfileOneWriterAndJournalNeverTakeAForgeItem() {
        // Profile 1 and a push's journal are Porelo's threads; a forge's item there would carry
        // a forge's words under no channel Porelo stamps at the gate.
        final Kept forge =
                new Kept(
                        "g1",
                        "1001",
                        Origin.of(new ForgeAccount("github.com", "583231", "octocat")),
                        REPLY);
        assertThat(forge.thread()).as("a forge reply kept in its own thread").isEqualTo("1001");
        assertThatThrownBy(() -> new Kept("g2", "1002", Origin.of(new OwnerAccount()), REPLY))
                .as("a forge reply kept in another thread than it names")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Held held(final String commit, final String digest) {
        return new Held(
                "h1",
                Direction.OUTGOING,
                "review-1",
                1,
                "https://github.com/o/r/pull/12",
                commit,
                REPLY,
                null,
                digest,
                Instant.parse("2026-11-25T12:00:00Z"),
                HeldState.UNDECIDED,
                null,
                null);
    }

    @Test
    void aForgeAccountNameThatWouldMisleadWhereItIsShownIsRefused() {
        // The name is the forge's text, shown to the owner account deciding what reaches a run.
        for (final String name : List.of("octo\u202Ecat", "octo\u200Bcat", "octo\u2028cat")) {
            assertThatThrownBy(() -> new ForgeAccount("github.com", "583231", name))
                    .as("a name with U+" + Integer.toHexString(name.codePointAt(4)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ForgeAccount("github.com", "1".repeat(65), "octocat"))
                .as("an id over 64 characters")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIncomingHeldItemCarriesItsForgeAccountAndAnOutgoingOneNone() {
        // A passed comment is delivered with its producer, so the hold keeps what the place gave.
        final ForgeAccount octocat = new ForgeAccount("github.com", "583231", "octocat");
        final Kept received = new Kept("c-7", "1001", Origin.of(octocat), REPLY, null);
        final Held incoming =
                new Held(
                        "h2",
                        Direction.INCOMING,
                        "review-1",
                        1,
                        "https://github.com/o/r/pull/12",
                        COMMIT,
                        REPLY,
                        received,
                        DIGEST,
                        Instant.parse("2026-11-25T12:00:00Z"),
                        HeldState.UNDECIDED,
                        null,
                        null);
        assertThat(incoming.from()).as("the account's name alone").isEqualTo("octocat");
        assertThat(held(COMMIT, DIGEST).from()).as("an outgoing item's writer").isNull();
        assertThatThrownBy(() -> held(COMMIT, DIGEST).in(HeldState.RETRY, null, null))
                .as("a retry without its time")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePushJournalRefusesAForgeReplyAndAForgeAccountAndKeepsNothing(@TempDir final Path dir)
            throws Exception {
        // A push's threads are Porelo's; a forge's item kept there would speak under no channel.
        final ReviewComments journal =
                new ReviewComments(
                        dir.resolve("review"),
                        Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));
        final Push push = new Push("shop", "shop", "shop-2", COMMIT, null, "c".repeat(12));
        final Kept opened =
                journal.keep(
                        push,
                        new ReviewComment(
                                new LineAnchor("a.txt", Side.NEW, 1, 1), new Body("Why?"), null),
                        Origin.of(new OwnerAccount()),
                        null);
        assertThatThrownBy(
                        () ->
                                journal.keep(
                                        push,
                                        new ForgeReply(opened.thread(), new Body("Agreed.")),
                                        Origin.of(new OwnerAccount()),
                                        null))
                .as("a forge reply")
                .isInstanceOf(ReviewRefused.class);
        assertThatThrownBy(
                        () ->
                                journal.keep(
                                        push,
                                        new ReviewReply(opened.thread(), new Body("Agreed.")),
                                        Origin.of(
                                                new ForgeAccount(
                                                        "github.com", "583231", "octocat")),
                                        null))
                .as("a forge account's reply")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(journal.threads(push))
                .singleElement()
                .satisfies(
                        thread ->
                                assertThat(thread.items())
                                        .as("only the owner account's comment")
                                        .containsExactly(opened));
    }

    @Test
    void theProfileOneWriterRefusesAForgeAccountsItem() {
        // Profile 1 writes a push's threads; a forge account is no producer there.
        final ReviewComment root =
                new ReviewComment(new LineAnchor("a.txt", Side.NEW, 1, 1), new Body("Why?"), null);
        final ReviewThread thread =
                new ReviewThread(
                        "t1",
                        ThreadState.OPEN,
                        List.of(
                                new Kept(
                                        "g1",
                                        "t1",
                                        Origin.of(
                                                new ForgeAccount(
                                                        "github.com", "583231", "octocat")),
                                        root,
                                        null)));
        final Push push = new Push("shop", "shop", "shop-2", COMMIT, null, "c".repeat(12));
        assertThatThrownBy(() -> new ReviewSarif().page(push, List.of(thread), null))
                .as("a forge account in profile 1")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
