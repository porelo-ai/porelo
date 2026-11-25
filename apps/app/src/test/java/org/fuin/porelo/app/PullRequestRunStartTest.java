package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run's start at a pull request, as a start's seam is told of it: the real start, holds and runs,
 * with stand-ins only at the forge adapter, the pin, the profile's reader and writer, the filter
 * and the task's mailbox.
 */
class PullRequestRunStartTest {

    private static final String URL = "https://review.stub.invalid/shop/app/pull/42";

    private static final PullRequestRef REF = PullRequestRef.of(URI.create(URL));

    private static final String TASK = "porelo-uc-shop-2";

    private static final String HEAD = "a".repeat(40);

    private static final RunPlace AT = RunPlace.atPullRequest(RunPlace.REVIEWER, TASK, URL);

    @TempDir Path holdsDir;

    private final List<ForgeInstance> known = new ArrayList<>();
    private final List<String> delivered = new ArrayList<>();
    private boolean noSuchPullRequest;

    private final ForgeReview forge =
            new ForgeReview() {
                @Override
                public PullRequestFacts facts(
                        final PullRequestRef ref, final ForgeCredential credential)
                        throws PlaceUnreadable {
                    if (noSuchPullRequest) {
                        throw new PlaceUnreadable(
                                PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST, "no " + ref.url());
                    }
                    return new PullRequestFacts(
                            ref,
                            "https://review.stub.invalid/shop/app",
                            HEAD,
                            "b".repeat(40),
                            "b".repeat(40),
                            PullRequestState.OPEN,
                            false,
                            null,
                            null,
                            null,
                            null);
                }

                @Override
                public ForgePages conversation(
                        final PullRequestRef ref, final ForgeCredential credential) {
                    return new ForgePages(List.of("{}".getBytes(StandardCharsets.UTF_8)), null);
                }

                @Override
                public WriteOutcome post(
                        final PullRequestRef ref,
                        final byte[] document,
                        final ForgeCredential credential) {
                    throw new UnsupportedOperationException();
                }
            };

    private final ProfileWriting writing =
            new ProfileWriting() {
                @Override
                public List<byte[]> pages(
                        final PullRequestFacts facts, final Conversation conversation) {
                    return List.of(
                            ("{\"head\":\"" + facts.head() + "\"}")
                                    .getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public byte[] one(final PullRequestFacts facts, final Kept item, final Kept root) {
                    return "{}".getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public byte[] submission(
                        final PullRequestFacts facts,
                        final ReviewItem item,
                        final LineAnchor place) {
                    return "{}".getBytes(StandardCharsets.UTF_8);
                }
            };

    private final HeldRuns runs = new HeldRuns();

    private Role role = Role.REVIEWER;

    private PullRequestRunStart start() {
        final ForgeInstances forges =
                new ForgeInstances() {
                    @Override
                    public Optional<ForgeInstance> of(final URI url) {
                        return known.stream()
                                .filter(each -> each.host().equals(url.getHost()))
                                .findFirst();
                    }

                    @Override
                    public List<ForgeInstance> all() {
                        return known;
                    }

                    @Override
                    public void set(
                            final String host, final String adapter, final CommentReading reading) {
                        throw new UnsupportedOperationException();
                    }
                };
        final PullRequestPlaces places =
                new PullRequestPlaces(
                        forges,
                        "stub-forge"::equals,
                        adapter -> forge,
                        review ->
                                (ref, task) ->
                                        new Push(
                                                "uc",
                                                "shop",
                                                "pr-42",
                                                HEAD,
                                                "b".repeat(40),
                                                "c".repeat(64)),
                        (document, channel) -> new ProfileRead(List.of(), Completeness.COMPLETE),
                        new ReviewStandIns.Check(),
                        writing,
                        entry -> new ForgeCredential(entry, "t"),
                        task -> role == Role.AUTHOR,
                        (task, facts) -> {
                            // As the daemon's author pin refuses: the contract's error as its
                            // cause.
                            final PlaceUnreadable unpinned =
                                    new PlaceUnreadable(
                                            PlaceUnreadable.Reason.NO_SUCH_PUSH, "not its push");
                            unpinned.initCause(
                                    new RunPlaceCheck.Refused(
                                            RunPlaceCheck.Refusal.unavailable(
                                                    "NotTheTasksPush",
                                                    java.util.Map.of("head", facts.head()),
                                                    "not its push")));
                            throw unpinned;
                        });
        return new PullRequestRunStart(
                places,
                new HoldJournal(holdsDir),
                runs,
                writing,
                (peer, data) -> Optional.empty(),
                (task, run, place, pages, held, unread) ->
                        delivered.add(
                                task
                                        + " run "
                                        + run
                                        + " at "
                                        + place
                                        + ": "
                                        + new String(pages.getFirst(), StandardCharsets.UTF_8)),
                entry -> new ForgeCredential(entry, "t"));
    }

    @Test
    void aStartAtAKnownPullRequestDeliversItsConversationAndBeginsTheRun() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));

        final RunStart.Outcome outcome = start().begin(TASK, AT);

        assertThat(outcome).as("a run began").isNotNull();

        assertThat(outcome.run()).as("the first run there").isEqualTo(1);
        assertThat(outcome.head()).as("the pinned head").isEqualTo(HEAD);
        assertThat(delivered)
                .as("the pages handed to the task")
                .containsExactly(TASK + " run 1 at " + URL + ": {\"head\":\"" + HEAD + "\"}");
        assertThat(runs.of(TASK))
                .as("the task's current run")
                .hasValueSatisfying(
                        run -> {
                            assertThat(run.place()).as("its place").isEqualTo(URL);
                            assertThat(run.role()).as("its role").isEqualTo(Role.REVIEWER);
                        });
    }

    @Test
    void aSecondStartAtTheSamePullRequestIsItsNextRun() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));
        final PullRequestRunStart start = start();
        start.begin(TASK, AT);

        final RunStart.Outcome second = start.begin(TASK, AT);

        assertThat(second).as("a run began").isNotNull();
        assertThat(second.run()).as("the second run").isEqualTo(2);
        assertThat(runs.of(TASK).orElseThrow().number()).as("the current one").isEqualTo(2);
    }

    @Test
    void aForgeTheMachineDoesNotKnowIsRefusedAsUnknownForge() {
        assertThatThrownBy(() -> start().begin(TASK, AT))
                .as("an unknown forge")
                .isInstanceOfSatisfying(
                        RunPlaceCheck.Refused.class,
                        ex -> {
                            assertThat(ex.refusal().error())
                                    .as("the contract's error")
                                    .isEqualTo("org.fuin.porelo.Tasks1.UnknownForge");
                            assertThat(ex.refusal().fields())
                                    .as("its host")
                                    .containsEntry("host", "review.stub.invalid");
                        });
        assertThat(runs.of(TASK)).as("no run").isEmpty();
        assertThat(delivered).as("nothing delivered").isEmpty();
    }

    @Test
    void aPullRequestTheForgeDoesNotKnowIsRefusedAsNoSuchPullRequest() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));
        noSuchPullRequest = true;

        assertThatThrownBy(() -> start().begin(TASK, AT))
                .as("an unknown pull request")
                .isInstanceOfSatisfying(
                        RunPlaceCheck.Refused.class,
                        ex -> {
                            assertThat(ex.refusal().error())
                                    .as("the contract's error")
                                    .isEqualTo("org.fuin.porelo.Tasks1.NoSuchPullRequest");
                            assertThat(ex.refusal().fields())
                                    .as("its url")
                                    .containsEntry("url", URL);
                        });
        assertThat(runs.of(TASK)).as("no run").isEmpty();
    }

    @Test
    void anUnreadablePlacesOtherReasonsArePlaceUnreadable() {
        final RunPlaceCheck.Refused refused =
                PullRequestRunStart.refused(
                        PlaceUnreadable.Reason.MISSING_FORGE_TOKEN, "no git.token.x", REF);

        assertThat(refused.refusal().error())
                .as("the error")
                .isEqualTo("org.fuin.porelo.Tasks1.PlaceUnreadable");
        assertThat(refused.refusal().fields())
                .as("its reason")
                .containsEntry("reason", "MissingForgeToken");
    }

    @Test
    void anUnreadableForgeFileIsRefusedAsPlaceUnreadable() {
        final RunPlaceCheck.Refused refused =
                PullRequestRunStart.refused(
                        PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE, "not owner-only", REF);

        assertThat(refused.refusal().error())
                .as("the error a start lists")
                .isEqualTo("org.fuin.porelo.Tasks1.PlaceUnreadable");
        assertThat(refused.refusal().fields())
                .as("its reason")
                .containsEntry("reason", "ForgeFileUnreadable");
    }

    @Test
    void theEarlyCheckRefusesAnUnknownPullRequestAndBeginsNoRun() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));
        noSuchPullRequest = true;

        assertThatThrownBy(() -> start().checked("shop-2", AT, REF))
                .as("asked before anything is made")
                .isInstanceOfSatisfying(
                        RunPlaceCheck.Refused.class,
                        ex ->
                                assertThat(ex.refusal().error())
                                        .as("the contract's error")
                                        .isEqualTo("org.fuin.porelo.Tasks1.NoSuchPullRequest"));
        assertThat(runs.of(TASK)).as("no run").isEmpty();
        assertThat(delivered).as("nothing delivered").isEmpty();
    }

    @Test
    void theEarlyCheckAtAKnownPullRequestPinsAndDeliversNothing() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));

        start().checked("shop-2", AT, REF);

        assertThat(runs.of(TASK)).as("no run yet").isEmpty();
        assertThat(delivered).as("nothing delivered").isEmpty();
    }

    @Test
    void aPlaceThatIsNoPullRequestStartsNothingHere() {
        final RunStart.Outcome outcome = start().begin(TASK, RunPlace.ownPush("shop", null, TASK));

        assertThat(outcome).as("nothing started").isNull();
        assertThat(runs.of(TASK)).as("no run").isEmpty();
    }

    @Test
    void anAuthorWhoseGateDidNotForwardTheHeadIsRefusedAsNotTheTasksPush() {
        known.add(
                new ForgeInstance(
                        "review.stub.invalid", "stub-forge", CommentReading.EXTENDED, null));
        role = Role.AUTHOR;

        assertThatThrownBy(
                        () ->
                                start().begin(
                                                TASK,
                                                RunPlace.atPullRequest(RunPlace.AUTHOR, TASK, URL)))
                .as("a head its own gate never forwarded")
                .isInstanceOfSatisfying(
                        RunPlaceCheck.Refused.class,
                        ex -> {
                            assertThat(ex.refusal().error())
                                    .as("the contract's own error")
                                    .isEqualTo("org.fuin.porelo.Tasks1.NotTheTasksPush");
                            assertThat(ex.refusal().fields())
                                    .as("its head")
                                    .containsEntry("head", HEAD);
                        });
        assertThat(runs.of(TASK)).as("no run").isEmpty();
        assertThat(delivered).as("nothing delivered").isEmpty();
    }
}
