package org.fuin.porelo.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.TaskRights;
import org.fuin.porelo.gate.review.WriteOutcome;

/**
 * Hands each released outgoing item to its place's write once, and marks the answer in the hold:
 * delivered, not delivered with the place's reason, to be retried after the time a rate limit
 * named, or of unknown outcome, which is shown and never sent again by itself.
 *
 * <p>A pass takes what the hold lists as due, oldest first, and hands over only an item that is
 * released, or waiting for a retry whose time passed when its turn comes; the time is read anew for
 * each check and each answer. An item whose place cannot be found is marked not delivered as
 * {@value #PLACE_UNREACHABLE}. A write that throws may have gone out, so it is marked of unknown
 * outcome. Before the write the item is recorded as being sent, so one whose answer is never marked
 * is shown as it is and never sent again by itself. Any other failure before the write, a
 * credential that cannot be lent and that record among them, leaves the item due; the pass goes on
 * with the next item and throws the failure at its end, named by its class, as it does when an
 * answer cannot be marked. The same for every place.
 *
 * <p>The credential is lent for each write and never kept; a waiting push at the gate is written as
 * the task itself, with {@link WaitingPushPlace#NO_ACCOUNT}, and no vault is asked.
 */
public final class ReleasedSender {

    /** The reason an item is not delivered when no place of its name can be reached any more. */
    public static final String PLACE_UNREACHABLE = "PlaceUnreachable";

    /**
     * Lends the write credential a vault entry holds, for one call.
     *
     * <p>A stand-in answers a fixed credential.
     */
    @FunctionalInterface
    public interface Credentials {

        /**
         * Lends the credential of an entry.
         *
         * @param entry The entry's name, as {@link TaskRights#postingEntry} gives it.
         * @return The credential, to be used for one write and dropped.
         * @throws PlaceUnreadable As {@code MissingForgeToken} when the vault holds no such entry.
         */
        ForgeCredential lend(String entry) throws PlaceUnreadable;
    }

    private final ReviewHolds holds;

    private final ReviewPlaces places;

    private final TaskRights role;

    private final Credentials credentials;

    private final Clock clock;

    /**
     * Constructor with what a pass reads and writes.
     *
     * @param holds The held items, listing what is due and taking each answer.
     * @param places Finds an item's place by the name its hold carries.
     * @param role Names the vault entry each task's items are written with at a place.
     * @param credentials Lends an entry's credential for one write.
     * @param clock What the time a pass runs at, and a retry is counted from, is read from.
     */
    public ReleasedSender(
            final ReviewHolds holds,
            final ReviewPlaces places,
            final TaskRights role,
            final Credentials credentials,
            final Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.places = Objects.requireNonNull(places, "places");
        this.role = Objects.requireNonNull(role, "role");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one pass over the items due now.
     *
     * @return How many items were handed to their place's write.
     * @throws IllegalStateException After the pass, when an item failed before its write, which
     *     leaves it due, or its answer could not be marked; every other item was handed over.
     */
    public int send() {
        int written = 0;
        IllegalStateException failed = null;
        for (final Held held : holds.due(clock.instant())) {
            // Read anew for each item: a slow write before it must not leave it waiting a pass.
            if (!due(held, clock.instant())) {
                continue;
            }
            try {
                if (send(held)) {
                    written++;
                }
            } catch (RuntimeException ex) {
                final IllegalStateException wrapped =
                        new IllegalStateException(
                                "The released item " + held.id() + " " + ex.getMessage(), ex);
                if (failed == null) {
                    failed = wrapped;
                } else {
                    failed.addSuppressed(wrapped);
                }
            }
        }
        if (failed != null) {
            throw failed;
        }
        return written;
    }

    // Whether the item may be written now, whatever the hold listed.
    private static boolean due(final Held held, final Instant now) {
        final Instant retryAt = held.retryAt();
        return held.state() == HeldState.RELEASED
                || held.state() == HeldState.RETRY && retryAt != null && !retryAt.isAfter(now);
    }

    // Whether the item was handed to its place's write; either way, an answer is marked.
    private boolean send(final Held held) {
        final ReviewPlace place;
        try {
            final Optional<ReviewPlace> found = places.named(held.place());
            if (found.isEmpty()) {
                holds.mark(
                        held.task(),
                        held.id(),
                        new WriteOutcome.Refused(PLACE_UNREACHABLE),
                        clock.instant());
                return false;
            }
            place = found.get();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "was not sent and stays due: " + ex.getClass().getSimpleName(), ex);
        }
        if (!stillMay(held, place)) {
            holds.mark(
                    held.task(),
                    held.id(),
                    new WriteOutcome.Refused(rightRule(held)),
                    clock.instant());
            return false;
        }
        final ForgeCredential credential = lent(held, place);
        try {
            // Recorded before the write: an item whose answer is never marked is not sent again.
            holds.sending(held.task(), held.id());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "was not sent and stays due: " + ex.getClass().getSimpleName(), ex);
        }
        WriteOutcome outcome;
        try {
            outcome = place.write(held, credential);
        } catch (RuntimeException ex) {
            // The request may have gone out; sending it again could write it twice.
            outcome = new WriteOutcome.OutcomeUnknown();
        }
        try {
            // Read after the write: a retry time counts from the answer, however long it took.
            holds.mark(held.task(), held.id(), outcome, clock.instant());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "was handed to its place, but the answer could not be marked; it stays being"
                            + " sent and is never sent again by itself: "
                            + ex.getClass().getSimpleName(),
                    ex);
        }
        return true;
    }

    /**
     * Whether the task still holds the right its item needs at its place, asked anew for every kind
     * before any credential is lent: a right taken away since the release sends nothing, and a
     * record that no longer reads throws, which leaves the item due.
     */
    private boolean stillMay(final Held held, final ReviewPlace place) {
        return switch (held.item()) {
            case org.fuin.porelo.gate.review.ReviewComment comment ->
                    role.mayOpenThread(held.task(), place);
            case org.fuin.porelo.gate.review.ReviewReply reply -> role.mayReply(held.task(), place);
            case org.fuin.porelo.gate.review.ForgeReply reply -> role.mayReply(held.task(), place);
            case org.fuin.porelo.gate.review.ThreadResolve resolve ->
                    role.mayResolve(held.task(), place);
        };
    }

    /** The rule an item's right breaks, as the intake names it. */
    private static String rightRule(final Held held) {
        return switch (held.item()) {
            case org.fuin.porelo.gate.review.ReviewComment comment -> RunIntake.MAY_NOT_OPEN;
            case org.fuin.porelo.gate.review.ReviewReply reply -> RunIntake.MAY_NOT_REPLY;
            case org.fuin.porelo.gate.review.ForgeReply reply -> RunIntake.MAY_NOT_REPLY;
            case org.fuin.porelo.gate.review.ThreadResolve resolve -> RunIntake.MAY_NOT_RESOLVE;
        };
    }

    /**
     * The credential for the item's write. A failure leaves the item due and is named by its class
     * alone, without its message or cause, which may hold what the vault was asked for.
     */
    private ForgeCredential lent(final Held held, final ReviewPlace place) {
        if (place instanceof WaitingPushPlace) {
            // A waiting push's threads are written as the task itself: no account, no vault.
            return WaitingPushPlace.NO_ACCOUNT;
        }
        try {
            return credentials.lend(role.postingEntry(held.task(), place));
        } catch (PlaceUnreadable ex) {
            throw new IllegalStateException(
                    "was not sent and stays due: "
                            + ex.getClass().getSimpleName()
                            + " "
                            + ex.reason().wireName());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "was not sent and stays due: " + ex.getClass().getSimpleName());
        }
    }
}
