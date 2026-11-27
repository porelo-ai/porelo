package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A forge's review calls through its adapter, started for each call, the adapter a stand-in. */
class AdapterForgeReviewTest {

    private static final String TOKEN = "tok-very-secret";

    private static final ForgeCredential LENT = new ForgeCredential("git.token.github.com", TOKEN);

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/7"));

    private static final ReviewFacts FACTS =
            new ReviewFacts(
                    "https://github.com/uc/shop",
                    "a".repeat(40),
                    "b".repeat(40),
                    "c".repeat(40),
                    ReviewFacts.State.OPEN,
                    false,
                    null,
                    null,
                    null,
                    new ReviewFacts.Account("583231", "octocat"));

    /** Answers what it is told and records each call; counts what was opened and closed. */
    private static final class Adapter implements AdapterForgeReview.Session {
        final List<String> calls = new ArrayList<>();
        final List<ForgeAccess> accesses = new ArrayList<>();
        final List<Object> answers = new ArrayList<>();
        int opened;
        int closed;

        private Object next() {
            final Object answer = answers.removeFirst();
            if (answer instanceof RuntimeException thrown) {
                throw thrown;
            }
            return answer;
        }

        @Override
        public ReviewFacts pullRequest(final ForgeAccess access, final String url) {
            calls.add("facts " + url);
            accesses.add(access);
            return (ReviewFacts) next();
        }

        @Override
        public ConversationPage comments(
                final ForgeAccess access, final String url, final @Nullable String cursor) {
            calls.add("comments " + cursor);
            accesses.add(access);
            return (ConversationPage) next();
        }

        @Override
        public String post(final ForgeAccess access, final String url, final byte[] document) {
            calls.add("post " + new String(document, StandardCharsets.UTF_8));
            accesses.add(access);
            return (String) next();
        }

        @Override
        public void resolve(final ForgeAccess access, final String url, final String thread) {
            calls.add("resolve " + thread);
            accesses.add(access);
            next();
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private final Adapter adapter = new Adapter();

    private final AdapterForgeReview review =
            new AdapterForgeReview(
                    () -> {
                        adapter.opened++;
                        return adapter;
                    });

    @Test
    void factsAreTheAdaptersAnswerForThePullRequestWithTheLentToken() throws Exception {
        adapter.answers.add(FACTS);

        final PullRequestFacts facts = review.facts(REF, LENT);

        assertThat(facts.ref()).as("the pull request").isEqualTo(REF);
        assertThat(facts.head()).as("its head").isEqualTo("a".repeat(40));
        assertThat(facts.mergeBase()).as("its merge base").isEqualTo("c".repeat(40));
        assertThat(facts.state()).as("its state").isEqualTo(PullRequestState.OPEN);
        assertThat(adapter.accesses)
                .as("the token lent, the forge's own API")
                .containsExactly(new ForgeAccess("", TOKEN));
        assertThat(adapter.closed).as("the adapter stopped after the call").isEqualTo(1);
    }

    @Test
    void theAccountTheTokenStandsForIsTheViewerAtThePullRequestsForge() throws Exception {
        adapter.answers.add(FACTS);

        assertThat(review.facts(REF, LENT).viewer())
                .as("the adapter's account, at the forge the pull request is on")
                .isEqualTo(new ForgeAccount("github.com", "583231", "octocat"));
    }

    @Test
    void aForgeThatDoesNotSayWhichAccountLeavesNoViewer() throws Exception {
        adapter.answers.add(
                new ReviewFacts(
                        FACTS.repository(),
                        FACTS.head(),
                        FACTS.base(),
                        FACTS.mergeBase(),
                        FACTS.state(),
                        false,
                        null,
                        null,
                        null,
                        null));

        assertThat(review.facts(REF, LENT).viewer()).as("no account is guessed").isNull();
    }

    @Test
    void aPullRequestTheForgeDoesNotKnowIsNoSuchPullRequest() {
        adapter.answers.add(new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST));

        assertThatThrownBy(() -> review.facts(REF, LENT))
                .as("an unknown pull request")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("its reason")
                                        .isEqualTo(PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST));
        assertThat(adapter.closed).as("stopped all the same").isEqualTo(1);
    }

    @Test
    void anAdapterThatFailsIsUnreachableAndItsWordsAreNotRepeated() {
        adapter.answers.add(new IllegalStateException("bad answer holding " + TOKEN));

        assertThatThrownBy(() -> review.facts(REF, LENT))
                .as("a failing adapter")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        ex -> {
                            assertThat(ex.reason())
                                    .as("its reason")
                                    .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE);
                            assertThat(ex.getMessage()).as("no token").doesNotContain(TOKEN);
                            assertThat(ex.getCause()).as("nothing chained").isNull();
                        });
    }

    @Test
    void theConversationIsEveryPageTheCursorsLeadTo() throws Exception {
        adapter.answers.add(ConversationPage.of("{\"p\":1}", "1"));
        adapter.answers.add(ConversationPage.of("{\"p\":2}", null));

        final ForgePages pages = review.conversation(REF, LENT);

        assertThat(pages.pages())
                .as("both pages, unread")
                .extracting(page -> new String(page, StandardCharsets.UTF_8))
                .containsExactly("{\"p\":1}", "{\"p\":2}");
        assertThat(pages.reason()).as("all of it").isNull();
        assertThat(adapter.calls)
                .as("asked in order")
                .containsExactly("comments null", "comments 1");
        assertThat(adapter.opened).as("one adapter for the whole read").isEqualTo(1);
    }

    @Test
    void aRateLimitAfterTheFirstPageHandsOnWhatWasReadAsRateLimited() throws Exception {
        adapter.answers.add(ConversationPage.of("{\"p\":1}", "1"));
        adapter.answers.add(ForgeReviewRefused.rateLimited(30));

        final ForgePages pages = review.conversation(REF, LENT);

        assertThat(pages.pages()).as("the page read").hasSize(1);
        assertThat(pages.reason()).as("why not all").isEqualTo(ConversationRead.RATE_LIMITED);
    }

    @Test
    void noPageAtAllIsAnUnreadablePlace() {
        adapter.answers.add(new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE));

        assertThatThrownBy(() -> review.conversation(REF, LENT))
                .as("nothing read")
                .isInstanceOf(PlaceUnreadable.class);
    }

    @Test
    void aPostTheForgeTookIsDeliveredInTheThreadItNames() {
        adapter.answers.add("9000000001");

        final WriteOutcome outcome =
                review.post(REF, "{\"s\":1}".getBytes(StandardCharsets.UTF_8), LENT);

        assertThat(outcome).as("written").isEqualTo(new WriteOutcome.Delivered("9000000001"));
        assertThat(adapter.calls).as("posted once").containsExactly("post {\"s\":1}");
    }

    @Test
    void aPostsRefusalsAreTheContractsAnswers() {
        adapter.answers.add(new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_THREAD));
        adapter.answers.add(ForgeReviewRefused.rateLimited(12));
        adapter.answers.add(
                new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN));
        adapter.answers.add(new IllegalStateException("broke after sending"));
        final byte[] document = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(review.post(REF, document, LENT))
                .as("a thread the forge does not know")
                .isEqualTo(new WriteOutcome.Refused("NoSuchThread"));
        assertThat(review.post(REF, document, LENT))
                .as("a rate limit")
                .isEqualTo(new WriteOutcome.RateLimited(Duration.ofSeconds(12)));
        assertThat(review.post(REF, document, LENT))
                .as("sent without an answer")
                .isEqualTo(new WriteOutcome.OutcomeUnknown());
        assertThat(review.post(REF, document, LENT))
                .as("a failure that may have gone out")
                .isEqualTo(new WriteOutcome.OutcomeUnknown());
    }

    @Test
    void aResolveIsTheContractsAnswerWithTheLentTokenAndAForgeThatCannotKeepsItsThreadOpen() {
        adapter.answers.add("done");
        adapter.answers.add(new ForgeReviewRefused(ForgeReviewRefused.Reason.RESOLVE_UNSUPPORTED));
        adapter.answers.add(new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_THREAD));
        adapter.answers.add(
                new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN));
        adapter.answers.add(new IllegalArgumentException("no thread id"));

        assertThat(review.resolve(REF, "1834527690", LENT))
                .as("resolved")
                .isEqualTo(new WriteOutcome.Delivered("1834527690"));
        assertThat(review.resolve(REF, "1834527690", LENT))
                .as("a forge that cannot resolve a thread")
                .isEqualTo(new WriteOutcome.Refused("ResolveUnsupported"));
        assertThat(review.resolve(REF, "1834527690", LENT))
                .as("a thread the forge does not know")
                .isEqualTo(new WriteOutcome.Refused("NoSuchThread"));
        assertThat(review.resolve(REF, "1834527690", LENT))
                .as("sent without an answer")
                .isEqualTo(new WriteOutcome.OutcomeUnknown());
        assertThat(review.resolve(REF, "x", LENT))
                .as("refused before it was sent")
                .isEqualTo(new WriteOutcome.Refused("NoSuchThread"));
        assertThat(adapter.accesses)
                .as("the token lent for each call")
                .containsOnly(new ForgeAccess("", TOKEN));
    }

    @Test
    void anAdapterThatDoesNotStartPostsNothingAndSaysSo() {
        final AdapterForgeReview none =
                new AdapterForgeReview(
                        () -> {
                            throw new IllegalStateException("not installed");
                        });

        assertThat(none.post(REF, "{}".getBytes(StandardCharsets.UTF_8), LENT))
                .as("never sent")
                .isEqualTo(new WriteOutcome.Refused("Unreachable"));
    }
}
