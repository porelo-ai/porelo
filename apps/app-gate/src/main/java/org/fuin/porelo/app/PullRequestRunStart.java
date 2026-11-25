package org.fuin.porelo.app;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.Role;
import org.jspecify.annotations.Nullable;

/**
 * Starts a task's run at a pull request, in two steps. Before anything is made, {@link #checked}
 * asks the forge for the pull request's facts, so a pull request it does not know is refused then.
 * Once the task's gate, its recorded place and its mailbox exist and before its agent runs, {@link
 * #begin} has {@link RunStart} pin the place, read its whole conversation with the forge's read
 * credential lent for the call, judge each comment with the message filter as the forge's setting
 * says, and deliver what passed to the task as notes, waking it once.
 *
 * <p>Refusals are the task contract's: {@code NoSuchPullRequest(url)}, {@code UnknownForge(host)},
 * and {@code PlaceUnreadable(reason, detail)} for every other reason, an unreadable forge file
 * among them; a delivery that failed is {@code Failed(message)}. No run of the task is current
 * after a refusal.
 */
public final class PullRequestRunStart {

    private final PullRequestPlaces places;
    private final ReviewHolds holds;
    private final CurrentRuns runs;
    private final ProfileWriting writing;
    private final InboundFilter filter;
    private final ConversationDelivery delivery;
    private final ReleasedSender.Credentials credentials;

    /**
     * Constructor with every part a start goes over.
     *
     * @param places Finds a pull request's place.
     * @param holds The held items, both ways.
     * @param runs The run each task is in now.
     * @param writing Writes a comment as the page the filter checks.
     * @param filter The message filter's inbound check.
     * @param delivery Hands the task its conversation.
     * @param credentials Lends a vault entry's credential for one call.
     */
    public PullRequestRunStart(
            final PullRequestPlaces places,
            final ReviewHolds holds,
            final CurrentRuns runs,
            final ProfileWriting writing,
            final InboundFilter filter,
            final ConversationDelivery delivery,
            final ReleasedSender.Credentials credentials) {
        this.places = Objects.requireNonNull(places, "places");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.writing = Objects.requireNonNull(writing, "writing");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Asks the forge for the pull request before anything is made, as a start's check is told of
     * it; nothing is pinned, kept or delivered.
     *
     * @param task The task's name within its project; not read.
     * @param place Its recorded role and place; not read.
     * @param ref The pull request.
     * @throws RunPlaceCheck.Refused As the class says, when the forge does not answer for it.
     */
    public void checked(final String task, final RunPlace place, final PullRequestRef ref) {
        try {
            final PullRequestPlace at = places.place(ref);
            at.facts(credentials.lend(at.instanceCredential()));
        } catch (PlaceUnreadable ex) {
            throw refused(ex.reason(), String.valueOf(ex.getMessage()), ref);
        }
    }

    /**
     * Starts the task's run at the pull request its place names, once the task is made and before
     * its agent runs.
     *
     * @param container The task's container, which keys its run, its holds and its mailbox.
     * @param place Its role and place; one that is no pull request starts nothing here.
     * @return What the run was given, or null for a place that is no pull request.
     * @throws RunPlaceCheck.Refused As the class says, when the run could not start.
     */
    public RunStart.@Nullable Outcome begin(final String container, final RunPlace place) {
        final String url = place.pullRequest();
        if (url == null) {
            return null;
        }
        final PullRequestRef ref = PullRequestRef.of(URI.create(url));
        final PullRequestPlace at;
        try {
            at = places.place(ref);
        } catch (PlaceUnreadable ex) {
            throw refused(ex.reason(), String.valueOf(ex.getMessage()), ref);
        }
        final RunStart start =
                new RunStart(
                        holds,
                        runs,
                        new CommentGateCheck(writing, filter, holds, container),
                        delivery,
                        lent -> credentials.lend(at.instanceCredential()));
        try {
            return start.start(container, Role.of(place.role()), at);
        } catch (RunRefused ex) {
            final PlaceUnreadable.Reason reason = ex.reason();
            if (reason == null) {
                throw new RunPlaceCheck.Refused(
                        RunPlaceCheck.Refusal.unavailable(
                                "Failed",
                                Map.of("message", String.valueOf(ex.getMessage())),
                                String.valueOf(ex.getMessage())));
            }
            throw refused(reason, String.valueOf(ex.getMessage()), ref);
        }
    }

    /** A place's reason as the task contract's refusal. */
    static RunPlaceCheck.Refused refused(
            final PlaceUnreadable.Reason reason, final String detail, final PullRequestRef ref) {
        return new RunPlaceCheck.Refused(
                switch (reason) {
                    case NO_SUCH_PULL_REQUEST ->
                            RunPlaceCheck.Refusal.unavailable(
                                    "NoSuchPullRequest",
                                    Map.of("url", ref.url().toString()),
                                    "the forge knows no pull request at " + ref.url());
                    case UNKNOWN_FORGE ->
                            RunPlaceCheck.Refusal.unavailable(
                                    "UnknownForge",
                                    Map.of("host", ref.forge()),
                                    "this machine knows no forge at " + ref.forge());
                    default ->
                            RunPlaceCheck.Refusal.unavailable(
                                    "PlaceUnreadable",
                                    Map.of("reason", reason.wireName(), "detail", detail),
                                    reason.wireName() + ", " + detail);
                });
    }
}
