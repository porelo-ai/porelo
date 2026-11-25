package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldRefused;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Suggestion;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * A held items' store in memory, and the gate's held and decide commands over it, so the daemon's
 * tests reach the commands' package-private seam.
 */
public final class HeldStandIns {

    /** The task the items below belong to, by its container name. */
    public static final String TASK = "porelo-uc_shop-2";

    /** The pull request the items below are held for. */
    public static final String PLACE = "https://github.com/uc/shop/pull/7";

    private HeldStandIns() {}

    /**
     * Returns an outgoing comment held at a given second, with a suggestion.
     *
     * @param id The hold's id.
     * @param text What it says.
     * @param second When it was held, in seconds from the epoch.
     * @return The item, undecided.
     */
    public static Held outgoing(final String id, final String text, final long second) {
        return outgoing(TASK, id, text, second);
    }

    /**
     * Returns an outgoing comment of a given task held at a given second, with a suggestion.
     *
     * @param task The task's container name.
     * @param id The hold's id.
     * @param text What it says.
     * @param second When it was held, in seconds from the epoch.
     * @return The item, undecided.
     */
    public static Held outgoing(
            final String task, final String id, final String text, final long second) {
        final ReviewComment comment =
                new ReviewComment(
                        new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                        new Body(text),
                        new Suggestion("orders.sort();\n"));
        return held(task, id, Direction.OUTGOING, comment, null, null, second);
    }

    /**
     * Returns an incoming reply a forge account wrote, held at a given second.
     *
     * @param id The hold's id.
     * @param second When it was held, in seconds from the epoch.
     * @return The item, undecided.
     */
    public static Held incoming(final String id, final long second) {
        final ForgeReply reply = new ForgeReply("1834527690", new Body("Why not sort it?"));
        final Kept received =
                new Kept(
                        "9001",
                        "1834527690",
                        Origin.of(new ForgeAccount("github.com", "583231", "octocat")),
                        reply);
        return held(TASK, id, Direction.INCOMING, reply, received, "extended: passed", second);
    }

    private static Held held(
            final String task,
            final String id,
            final Direction direction,
            final ReviewItem item,
            final @Nullable Kept received,
            final @Nullable String reason,
            final long second) {
        return new Held(
                id,
                direction,
                task,
                1,
                PLACE,
                "a".repeat(40),
                item,
                received,
                Character.forDigit(id.length() % 16, 16) + "d".repeat(63),
                Instant.ofEpochSecond(second),
                HeldState.UNDECIDED,
                reason,
                null);
    }

    /** Keeps held items in memory, decides them as the journal does, and records each decision. */
    public static final class Holds implements ReviewHolds {

        /** The items, by id, oldest first. */
        public final Map<String, Held> items = new LinkedHashMap<>();

        /** Each decision taken, as "task id decision". */
        public final List<String> decided = new ArrayList<>();

        @Override
        public Held sending(final String task, final String id) {
            throw new UnsupportedOperationException("not used here");
        }

        /** The task each listing asked for; "" for every task. */
        public final List<String> listed = new ArrayList<>();

        /**
         * Adds items.
         *
         * @param held The items.
         * @return This store.
         */
        public Holds with(final Held... held) {
            for (final Held each : held) {
                items.put(each.id(), each);
            }
            return this;
        }

        @Override
        public List<Held> undecided(final @Nullable String task) {
            listed.add(task == null ? "" : task);
            return items.values().stream()
                    .filter(held -> held.state() == HeldState.UNDECIDED)
                    .filter(held -> task == null || held.task().equals(task))
                    .toList();
        }

        @Override
        public Optional<Held> find(final String task, final String id) {
            return Optional.ofNullable(items.get(id)).filter(held -> held.task().equals(task));
        }

        @Override
        public Held decide(
                final String task, final String id, final String digest, final Decision decision)
                throws HoldRefused {
            final Held now = find(task, id).orElse(null);
            if (now == null) {
                throw new HoldRefused(HoldRefused.Reason.NO_SUCH_ITEM, id);
            }
            if (!decision.fits(now.direction())) {
                throw new IllegalArgumentException(decision + " does not fit " + now.direction());
            }
            if (now.state() == HeldState.LAPSED) {
                throw new HoldRefused(HoldRefused.Reason.LAPSED, id);
            }
            if (now.state() != HeldState.UNDECIDED) {
                throw new HoldRefused(HoldRefused.Reason.ALREADY_DECIDED, id);
            }
            if (!now.digest().equals(digest)) {
                throw new HoldRefused(HoldRefused.Reason.DIGEST_CHANGED, id);
            }
            final Held decidedNow = now.in(decision.state(), now.reason(), null);
            items.put(id, decidedNow);
            decided.add(task + " " + id + " " + decision);
            return decidedNow;
        }

        @Override
        public int startRun(final String task, final String place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Held hold(
                final Run run,
                final String task,
                final Direction direction,
                final ReviewItem item,
                final @Nullable Kept received,
                final @Nullable String rule) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Held> incoming(
                final String task, final String place, final String received) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Held> due(final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mark(
                final String task, final String id, final WriteOutcome outcome, final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int count(final String task) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Holds one outgoing comment of a task's first run in a real journal.
     *
     * @param holds The journal.
     * @param task The task's container name.
     * @return The item, undecided.
     */
    public static Held heldIn(final ReviewHolds holds, final String task) {
        final int number = holds.startRun(task, PLACE);
        final org.fuin.porelo.gate.review.Push push =
                new org.fuin.porelo.gate.review.Push(
                        "uc", "shop", "review-7", "a".repeat(40), null, "0123456789ab");
        return holds.hold(
                new Run(
                        PLACE,
                        org.fuin.porelo.gate.review.Role.AUTHOR,
                        new org.fuin.porelo.gate.review.Pinned(push, null),
                        number),
                task,
                Direction.OUTGOING,
                new org.fuin.porelo.gate.review.ReviewReply("t-1", new Body("why")),
                null,
                null);
    }

    /**
     * What a command printed and answered.
     *
     * @param code Its exit code.
     * @param out Its standard output.
     * @param err Its standard error.
     */
    public record Ran(int code, String out, String err) {}

    /**
     * Runs {@code porelo gate decide} over a store.
     *
     * @param holds The store.
     * @param arguments The command's arguments.
     * @return What it did.
     */
    public static Ran decide(final ReviewHolds holds, final String... arguments) {
        final GateDecideCommand command = new GateDecideCommand();
        command.use(holds);
        return run(command, arguments);
    }

    /**
     * Runs {@code porelo gate held} over a store.
     *
     * @param holds The store.
     * @param arguments The command's arguments.
     * @return What it did.
     */
    public static Ran held(final ReviewHolds holds, final String... arguments) {
        final GateHeldCommand command = new GateHeldCommand();
        command.use(holds);
        return run(command, arguments);
    }

    private static Ran run(final Callable<Integer> command, final String... arguments) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code =
                new CommandLine(command)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err))
                        .execute(arguments);
        return new Ran(code, out.toString(), err.toString());
    }
}
