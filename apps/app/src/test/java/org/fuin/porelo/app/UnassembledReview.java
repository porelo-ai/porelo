package org.fuin.porelo.app;

import java.util.List;
import java.util.Optional;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for the review's parts in a test of a reject that is not about its threads: a store
 * that holds no thread, so the note carries none, and a writer that is never reached.
 */
final class UnassembledReview {

    /** Holds no thread and keeps nothing. */
    public static final ReviewStore STORE =
            new ReviewStore() {
                @Override
                public Kept keep(
                        final Push push,
                        final ReviewItem item,
                        final Origin origin,
                        final @Nullable String id) {
                    throw new IllegalStateException("no review is assembled to keep it");
                }

                @Override
                public List<ReviewThread> threads(final Push push) {
                    return List.of();
                }

                @Override
                public Optional<Push> recorded(
                        final String project,
                        final String repository,
                        final String name,
                        final String task) {
                    return Optional.empty();
                }

                @Override
                public ReviewThread state(
                        final Push push, final String thread, final ThreadState state) {
                    throw new IllegalStateException("no review is assembled to hold the thread");
                }
            };

    /** Never reached, since {@link #STORE} answers no thread to write. */
    public static final ReviewWriter WRITER =
            (push, threads, cursor) -> {
                throw new IllegalStateException("no review is assembled to write the threads");
            };

    private UnassembledReview() {
        throw new UnsupportedOperationException("Utility class");
    }
}
