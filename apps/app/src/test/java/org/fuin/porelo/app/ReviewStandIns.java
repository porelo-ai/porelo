package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;

/** Small stand-ins of the review's interfaces, for the intake and the commands. */
final class ReviewStandIns {

    private ReviewStandIns() {}

    static final Push SHOP =
            new Push("uc", "shop", "shop-2", "a".repeat(40), "b".repeat(40), "c".repeat(64));

    static final Push OTHER =
            new Push("uc", "shop", "shop-3", "d".repeat(40), null, "e".repeat(64));

    /** Answers a fixed refusal or none, and records what it was asked. */
    static final class Check implements CommentCheck {
        final List<ReviewItem> asked = new ArrayList<>();
        @Nullable ReviewRefusal answer;

        @Override
        public Optional<ReviewRefusal> check(final Push push, final ReviewItem item) {
            asked.add(item);
            return Optional.ofNullable(answer);
        }
    }

    /**
     * Keeps in memory what it is given, with retry ids, and answers a push's threads. It keeps a
     * reply wherever it names, so only the intake's own rule over threads is under test.
     */
    static final class Store implements ReviewStore {
        final List<Kept> kept = new ArrayList<>();
        final Map<String, Push> threadPush = new LinkedHashMap<>();
        final Map<String, ThreadState> states = new LinkedHashMap<>();
        final Map<String, Kept> byId = new LinkedHashMap<>();
        final Map<String, ReviewItem> idItem = new LinkedHashMap<>();
        @Nullable ReviewRefusal refusal;

        @Override
        public Kept keep(
                final Push push,
                final ReviewItem item,
                final Origin origin,
                final @Nullable String id)
                throws ReviewRefused {
            if (refusal != null) {
                throw new ReviewRefused(refusal);
            }
            final String key = push.name() + "/" + id;
            if (id != null && byId.containsKey(key)) {
                final Kept first = byId.get(key);
                if (first.item().equals(item) && first.origin().equals(origin)) {
                    return first;
                }
                throw new ReviewRefused(new ReviewRefusal(ReviewError.ID_REUSED, null));
            }
            final String thread;
            if (item instanceof ReviewReply reply) {
                thread = reply.thread();
            } else {
                thread = UUID.randomUUID().toString();
                threadPush.put(thread, push);
                states.put(thread, ThreadState.OPEN);
            }
            final Kept one = new Kept(UUID.randomUUID().toString(), thread, origin, item);
            kept.add(one);
            if (id != null) {
                byId.put(key, one);
            }
            return one;
        }

        @Override
        public List<ReviewThread> threads(final Push push) {
            final List<ReviewThread> threads = new ArrayList<>();
            threadPush.forEach(
                    (thread, owner) -> {
                        if (owner.equals(push)) {
                            threads.add(thread(thread));
                        }
                    });
            return threads;
        }

        private ReviewThread thread(final String id) {
            return new ReviewThread(
                    id,
                    states.get(id),
                    kept.stream().filter(each -> each.thread().equals(id)).toList());
        }

        @Override
        public Optional<Push> recorded(
                final String project,
                final String repository,
                final String name,
                final String task) {
            Push last = null;
            for (final Push each : threadPush.values()) {
                if (each.project().equals(project)
                        && each.repository().equals(repository)
                        && each.name().equals(name)
                        && each.task().equals(task)) {
                    last = each;
                }
            }
            return Optional.ofNullable(last);
        }

        @Override
        public ReviewThread state(final Push push, final String thread, final ThreadState state)
                throws ReviewRefused {
            if (!push.equals(threadPush.get(thread))) {
                throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
            }
            states.put(thread, state);
            return thread(thread);
        }

        /** Opens a thread with a comment, as the store keeps one. */
        String opened(final Push push, final Origin origin, final ReviewComment comment)
                throws ReviewRefused {
            return keep(push, comment, origin, null).thread();
        }
    }

    /** Answers a fixed page or refusal, and records what it was given. */
    static final class Writer implements ReviewWriter {
        @Nullable Push push;
        @Nullable List<ReviewThread> threads;
        @Nullable String cursor;
        Page page = new Page("{\"version\":\"2.1.0\"}", null);
        @Nullable ReviewRefusal refusal;

        @Override
        public Page page(
                final Push push, final List<ReviewThread> threads, final @Nullable String cursor)
                throws ReviewRefused {
            this.push = push;
            this.threads = threads;
            this.cursor = cursor;
            if (refusal != null) {
                throw new ReviewRefused(refusal);
            }
            return page;
        }
    }

    /** Parts over these stand-ins, with the pushes that wait. */
    static CommentIntake.Parts parts(final Check check, final Store store, final Writer writer) {
        return new CommentIntake.Parts(
                (project, repository, name) -> {
                    for (final Push push : List.of(SHOP, OTHER)) {
                        if (push.project().equals(project) && push.name().equals(name)) {
                            return push;
                        }
                    }
                    throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
                },
                check,
                store,
                writer,
                push -> new ReviewCounts.Counts(0, 0, List.of()));
    }
}
