package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The items held for the owner account's decision, both ways, for every task of the machine.
 * Nothing held reaches its place or its run before it is decided.
 *
 * <p>A stand-in keeps them in memory.
 */
public interface ReviewHolds {

    /**
     * Begins a run of a task at a place: every undecided item of its earlier runs there lapses.
     *
     * @param task The task's name.
     * @param place The place's name.
     * @return The new run's number there, from 1.
     */
    int startRun(String task, String place);

    /**
     * Holds an item of a run, undecided, with a new id and the digest of its canonical content. An
     * item of a run its task has already started again at the place is held lapsed, never
     * undecided.
     *
     * <p>The digest is the one {@code HoldJournal.digest} computes and documents: SHA-256 of the
     * item's fields, each as its UTF-8 length, a colon, its bytes and a line feed. A client never
     * computes it; it names the digest it was shown.
     *
     * @param run The run it belongs to.
     * @param task The task's name.
     * @param direction Outgoing from the run, or incoming to it.
     * @param item The item.
     * @param received For an incoming item what its place gave (id, thread, producer, time); null
     *     for an outgoing one.
     * @param rule For an incoming item the rule it was withheld by, the comment gate's filter rule
     *     id, kept as {@link Held#reason()}; null for an outgoing one.
     * @return The item as held, undecided or lapsed.
     * @throws IllegalArgumentException When an incoming item lacks what its place gave or its rule,
     *     or an outgoing one carries either.
     */
    Held hold(
            Run run,
            String task,
            Direction direction,
            ReviewItem item,
            @Nullable Kept received,
            @Nullable String rule);

    /**
     * Finds the hold of an incoming item at a place by the item's id there, in any state, so a
     * comment read again in a later run keeps its decision.
     *
     * @param task The task's name.
     * @param place The place's name.
     * @param received The item's id at its place ({@link Kept#guid()}).
     * @return Its newest hold for that task and place, or empty.
     */
    Optional<Held> incoming(String task, String place, String received);

    /**
     * Lists the undecided items.
     *
     * @param task A task's name, or null for every task of the machine.
     * @return Them, oldest first.
     */
    List<Held> undecided(@Nullable String task);

    /**
     * The undecided items of every task whose journal can be read, and what cannot be read.
     *
     * @param items The items, oldest first; a tie in time keeps the tasks in name order and each
     *     task's items in the order they were held.
     * @param unreadable The store's entries whose journal cannot be read, or that are no task's, by
     *     name and in name order.
     */
    record Undecided(List<Held> items, List<String> unreadable) {

        /** Keeps both lists as given, unchangeable. */
        public Undecided {
            items = List.copyOf(items);
            unreadable = List.copyOf(unreadable);
        }
    }

    /**
     * Lists the undecided items of every task of the machine, past a task whose journal cannot be
     * read: one damaged journal never hides every other task's items.
     *
     * @return The items, and the entries that could not be read.
     */
    default Undecided everyUndecided() {
        return new Undecided(undecided(null), List.of());
    }

    /**
     * Finds a held item by its id, in any state.
     *
     * @param task The task's name.
     * @param id The item's id.
     * @return It, or empty.
     */
    Optional<Held> find(String task, String id);

    /**
     * Decides one item, if its content is still the one shown.
     *
     * @param task The task's name.
     * @param id The item's id.
     * @param digest The digest the decider was shown.
     * @param decision Release or refuse for an outgoing item, pass or dismiss for an incoming one.
     * @return The item in its new state.
     * @throws HoldRefused When no such item, the digest changed, it was decided, or it lapsed.
     * @throws IllegalArgumentException When the decision is not one for the item's direction.
     */
    Held decide(String task, String id, String digest, Decision decision) throws HoldRefused;

    /**
     * Lists the outgoing items due for a write: released ones, and retried ones whose time passed.
     *
     * @param now The time it is.
     * @return Them, oldest first.
     */
    List<Held> due(Instant now);

    /**
     * Records that a due item is handed to its place's write now, before the write, so an item
     * whose answer is never marked is not written again by itself.
     *
     * @param task The task's name.
     * @param id The item's id.
     * @return The item, now {@link HeldState#SENDING}.
     * @throws IllegalStateException When the task holds no such item, or it is not released or
     *     waiting for a retry whose time passed.
     */
    Held sending(String task, String id);

    /**
     * Marks what became of an item's write: delivered, not delivered with the place's reason,
     * retried after the time a rate limit named (rounded up to a whole second), or of unknown
     * outcome, never retried. An item being sent takes any answer; a released or retried one that
     * was never handed to its place's write only a refusal.
     *
     * @param task The task's name.
     * @param id The item's id.
     * @param outcome What the place answered.
     * @param now The time it is, from which a rate limit's wait is counted.
     * @throws IllegalStateException When the task holds no such item, or its state takes no such
     *     answer.
     */
    void mark(String task, String id, WriteOutcome outcome, Instant now);

    /**
     * Counts a task's undecided items, as its row shows them.
     *
     * @param task The task's name.
     * @return How many.
     */
    int count(String task);
}
