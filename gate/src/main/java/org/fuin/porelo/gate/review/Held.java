package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An item held for the owner account's decision, bound to its run, its place, the commit the run
 * pinned and the digest of its exact content, so a decision reaches only what was shown.
 *
 * @param id The hold's own id.
 * @param direction Outgoing from the run, or incoming to it.
 * @param task The task whose run it belongs to.
 * @param run The run's number at the place.
 * @param place The place's name.
 * @param commit The commit the run pinned, in full; an outgoing item is written against it.
 * @param item The item, unchanged.
 * @param received An incoming item as its place gave it: its id and thread there, its producer (the
 *     forge account) and its time; null for an outgoing one, which the task wrote.
 * @param digest The SHA-256 of the item's canonical content, in lowercase hex.
 * @param at When it was held, in whole seconds, UTC.
 * @param state Where it stands.
 * @param reason The place's reason for {@link HeldState#NOT_DELIVERED}, or the rule an incoming
 *     item was withheld by; null otherwise.
 * @param retryAt When a {@link HeldState#RETRY} item may be written again; null otherwise.
 */
public record Held(
        String id,
        Direction direction,
        String task,
        int run,
        String place,
        String commit,
        ReviewItem item,
        @Nullable Kept received,
        String digest,
        Instant at,
        HeldState state,
        @Nullable String reason,
        @Nullable Instant retryAt) {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /**
     * Refuses a blank id, task or place, a run below 1, a commit not in full, a digest that is no
     * SHA-256, an incoming item without what its place gave or an outgoing one with it, and a retry
     * time on any state but {@link HeldState#RETRY}.
     */
    public Held {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(state, "state");
        if (id.isBlank() || task.isBlank() || place.isBlank()) {
            throw new IllegalArgumentException("a held item names its id, task and place");
        }
        if (run < 1) {
            throw new IllegalArgumentException("a run is counted from 1");
        }
        if (!COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException("the pinned commit is not a full commit id");
        }
        if (!DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("the digest is not a SHA-256 in lowercase hex");
        }
        if ((direction == Direction.INCOMING) != (received != null)) {
            throw new IllegalArgumentException(
                    "an incoming item carries what its place gave, an outgoing one nothing");
        }
        if (received != null && !received.item().equals(item)) {
            throw new IllegalArgumentException("an incoming item is the one its place gave");
        }
        if ((state == HeldState.RETRY) != (retryAt != null)) {
            throw new IllegalArgumentException("a retry time is given exactly when retried");
        }
        at = at.truncatedTo(ChronoUnit.SECONDS);
        if (retryAt != null) {
            retryAt = retryAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }

    /**
     * Returns who wrote an incoming item, as {@code HeldItem.from} carries it: a forge account's
     * name alone.
     *
     * @return The name, or null for an outgoing item or one whose producer is no forge account.
     */
    public @Nullable String from() {
        return received != null && received.origin().producer() instanceof ForgeAccount account
                ? account.name()
                : null;
    }

    /**
     * Returns this item in another state, keeping everything else.
     *
     * @param next The new state.
     * @param why The place's reason or the rule, or null.
     * @param retry The retry time for {@link HeldState#RETRY}, else null.
     * @return The same item, now in that state.
     */
    public Held in(
            final HeldState next, final @Nullable String why, final @Nullable Instant retry) {
        return new Held(
                id, direction, task, run, place, commit, item, received, digest, at, next, why,
                retry);
    }
}
