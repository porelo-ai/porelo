package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A released item reaches its pull request as one post, against its pinned commit. */
class ForgeWriteTest {

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/7"));

    private static final String HEAD = "4e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d";

    private static final String BASE = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

    private static final String OTHER = "1111111111111111111111111111111111111111";

    private static final String TOKEN = "ghp-lent-7f3a9c";

    private static final ForgeCredential LENT =
            new ForgeCredential("forge/github.com/porelo-review", TOKEN);

    private static final PullRequestFacts FACTS =
            new PullRequestFacts(
                    REF,
                    "https://github.com/uc/shop",
                    HEAD,
                    BASE,
                    BASE,
                    PullRequestState.OPEN,
                    false,
                    null,
                    null,
                    null);

    private static final byte[] SUBMISSION =
            "{\"one\": \"submission\"}".getBytes(StandardCharsets.UTF_8);

    private final Adapter adapter = new Adapter();

    private final Writing writing = new Writing();

    private final ForgeWrite write = new ForgeWrite(adapter, writing);

    @Test
    void oneWriteIsOnePostOfTheSubmissionsBytesWithTheLentCredential() {
        adapter.answer = new WriteOutcome.Delivered("1834527690");
        final ReviewItem reply = new ForgeReply("1834527690", new Body("Moved the check up."));

        final WriteOutcome outcome = write.write(REF, FACTS, held(HEAD, reply), LENT, OPENING);

        assertThat(outcome)
                .as("the forge's answer")
                .isEqualTo(new WriteOutcome.Delivered("1834527690"));
        assertThat(adapter.posts).as("exactly one post").hasSize(1);
        assertThat(adapter.posts.getFirst().document())
                .as("the submission's bytes, as written")
                .isEqualTo(SUBMISSION);
        assertThat(adapter.posts.getFirst().ref()).as("to its pull request").isEqualTo(REF);
        assertThat(adapter.posts.getFirst().credential())
                .as("with the lent credential")
                .isSameAs(LENT);
        assertThat(writing.written)
                .as("the reply written against the pinned facts at its thread's place")
                .containsExactly(new Written(FACTS, reply, OPENING));
    }

    @Test
    void aCommentIsWrittenAtItsOwnAnchorWhateverPlaceIsGiven() {
        write.write(REF, FACTS, held(HEAD, comment()), LENT, OPENING);

        assertThat(writing.written)
                .as("the comment at its own line anchor")
                .containsExactly(new Written(FACTS, comment(), (LineAnchor) comment().anchor()));
    }

    @Test
    void aForgeReplyWhoseThreadWasNotFoundIsRefusedUnposted() {
        final WriteOutcome outcome =
                write.write(
                        REF,
                        FACTS,
                        held(HEAD, new ForgeReply("77", new Body("Lost."))),
                        LENT,
                        null);

        assertThat(outcome)
                .as("no place to write the reply at")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.NO_SUCH_THREAD));
        assertThat(adapter.posts).as("nothing posted").isEmpty();
        assertThat(writing.written).as("nothing written").isEmpty();
    }

    @Test
    void aThreadTheForgeDoesNotKnowComesBackRefusedWithItsReason() {
        adapter.answer = new WriteOutcome.Refused("NoSuchThread");

        final WriteOutcome outcome =
                write.write(
                        REF,
                        FACTS,
                        held(HEAD, new ForgeReply("77", new Body("Lost."))),
                        LENT,
                        OPENING);

        assertThat(outcome)
                .as("the forge's refusal, with its reason")
                .isEqualTo(new WriteOutcome.Refused("NoSuchThread"));
        assertThat(adapter.posts).as("posted once").hasSize(1);
    }

    @Test
    void aPostThatThrowsIsOfUnknownOutcomeAndIsNotRepeated() {
        adapter.failure = new IllegalStateException("connection reset, token " + TOKEN);

        final WriteOutcome outcome = write.write(REF, FACTS, held(HEAD, comment()), LENT, null);

        assertThat(outcome)
                .as("sent, and no answer said whether it was written")
                .isEqualTo(new WriteOutcome.OutcomeUnknown());
        assertThat(adapter.posts).as("never sent again").hasSize(1);
    }

    @Test
    void anAdapterThatAnswersNothingIsOfUnknownOutcome() {
        adapter.answer = null;

        final WriteOutcome outcome = write.write(REF, FACTS, held(HEAD, comment()), LENT, null);

        assertThat(outcome)
                .as("sent, and no answer said whether it was written")
                .isEqualTo(new WriteOutcome.OutcomeUnknown());
        assertThat(adapter.posts).as("posted once").hasSize(1);
    }

    @Test
    void anItemHeldForAnotherCommitThanThePinnedHeadIsRefusedUnposted() {
        final WriteOutcome outcome = write.write(REF, FACTS, held(OTHER, comment()), LENT, null);

        assertThat(outcome)
                .as("held for a commit the pull request is no longer at")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.MOVED_SINCE_REVIEW));
        assertThat(adapter.posts).as("nothing posted").isEmpty();
        assertThat(writing.written).as("nothing written").isEmpty();
    }

    @Test
    void aReplyInAThreadOfPorelosOwnIsRefusedUnposted() {
        final WriteOutcome outcome =
                write.write(
                        REF,
                        FACTS,
                        held(HEAD, new ReviewReply("porelo-thread-3", new Body("Done."))),
                        LENT,
                        OPENING);

        assertThat(outcome)
                .as("a reply naming no forge thread")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.NO_SUCH_THREAD));
        assertThat(adapter.posts).as("nothing posted").isEmpty();
    }

    @Test
    void anItemTheProfileCannotCarryIsRefusedUnposted() {
        writing.failure = new IllegalArgumentException("a suggestion profile 2 cannot carry");

        final WriteOutcome outcome = write.write(REF, FACTS, held(HEAD, comment()), LENT, null);

        assertThat(outcome)
                .as("the writer refused it")
                .isEqualTo(new WriteOutcome.Refused(ForgeWrite.NOT_IN_PROFILE));
        assertThat(adapter.posts).as("nothing posted").isEmpty();
    }

    @Test
    void theLentTokenIsInNoAnswerAndNoOutput() {
        final List<WriteOutcome> outcomes = new ArrayList<>();
        final ByteArrayOutputStream printed = new ByteArrayOutputStream();
        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final PrintStream capture = new PrintStream(printed, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            adapter.failure = new IllegalStateException("Authorization: Bearer " + TOKEN);
            outcomes.add(write.write(REF, FACTS, held(HEAD, comment()), LENT, null));
            adapter.failure = null;
            adapter.answer = new WriteOutcome.RateLimited(Duration.ofSeconds(30));
            outcomes.add(write.write(REF, FACTS, held(HEAD, comment()), LENT, null));
            outcomes.add(write.write(REF, FACTS, held(OTHER, comment()), LENT, null));
            writing.failure = new IllegalArgumentException("refused for " + TOKEN);
            outcomes.add(write.write(REF, FACTS, held(HEAD, comment()), LENT, null));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(outcomes)
                .as("every answer, as text")
                .allSatisfy(
                        outcome ->
                                assertThat(outcome.toString())
                                        .as("%s", outcome.getClass().getSimpleName())
                                        .doesNotContain(TOKEN));
        assertThat(printed.toString(StandardCharsets.UTF_8))
                .as("nothing printed holds the token")
                .doesNotContain(TOKEN);
        assertThat(write.toString()).as("the writer as text").doesNotContain(TOKEN);
    }

    /** The anchor of the forge thread's opening comment a reply answers. */
    private static final LineAnchor OPENING = new LineAnchor("src/Login.java", Side.NEW, 12, 12);

    private static ReviewComment comment() {
        return new ReviewComment(
                new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                new Body("Check the expiry first."),
                null);
    }

    private static Held held(final String commit, final ReviewItem item) {
        return new Held(
                "h-1",
                Direction.OUTGOING,
                "fix-login",
                1,
                "github.com",
                commit,
                item,
                null,
                HoldJournal.digest(item),
                Instant.parse("2026-11-25T10:00:00Z"),
                HeldState.SENDING,
                null,
                null);
    }

    /** One post as the adapter was handed it. */
    private record Post(PullRequestRef ref, byte[] document, ForgeCredential credential) {}

    /** One submission as the writer was asked for it. */
    private record Written(PullRequestFacts facts, ReviewItem item, LineAnchor place) {}

    /** The forge adapter: records each post and answers as told. */
    private static final class Adapter implements ForgeReview {

        private final List<Post> posts = new ArrayList<>();

        private @Nullable WriteOutcome answer = new WriteOutcome.Delivered("1");

        private @Nullable RuntimeException failure;

        @Override
        public PullRequestFacts facts(final PullRequestRef ref, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a write reads no facts");
        }

        @Override
        public ForgePages conversation(final PullRequestRef ref, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a write reads no conversation");
        }

        @Override
        public WriteOutcome post(
                final PullRequestRef ref, final byte[] document, final ForgeCredential credential) {
            posts.add(new Post(ref, document.clone(), credential));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /** The profile 2 writer: fixed bytes for a submission, or a refusal. */
    private static final class Writing implements ProfileWriting {

        private final List<Written> written = new ArrayList<>();

        private @Nullable IllegalArgumentException failure;

        @Override
        public List<byte[]> pages(final PullRequestFacts facts, final Conversation conversation) {
            throw new UnsupportedOperationException("a write writes no pages");
        }

        @Override
        public byte[] one(final PullRequestFacts facts, final Kept opening, final Kept item) {
            throw new UnsupportedOperationException("a write writes no conversation item");
        }

        @Override
        public byte[] submission(
                final PullRequestFacts facts, final ReviewItem item, final LineAnchor place) {
            if (failure != null) {
                throw failure;
            }
            written.add(new Written(facts, item, place));
            return SUBMISSION.clone();
        }
    }
}
