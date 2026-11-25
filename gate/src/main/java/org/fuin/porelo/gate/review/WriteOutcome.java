package org.fuin.porelo.gate.review;

import java.time.Duration;
import java.util.Objects;

/** What became of one write of a released item to its place. */
public sealed interface WriteOutcome {

    /**
     * Written.
     *
     * @param thread The place's id of the thread written in: the one answered, or the new one a
     *     comment opened.
     */
    record Delivered(String thread) implements WriteOutcome {
        /** Refuses a blank thread. */
        public Delivered {
            Objects.requireNonNull(thread, "thread");
            if (thread.isBlank()) {
                throw new IllegalArgumentException("a delivered item names its thread");
            }
        }
    }

    /**
     * The place refused it; it was not written.
     *
     * @param reason Why, by rule, never the place's own text.
     */
    record Refused(String reason) implements WriteOutcome {
        /** Refuses a blank reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("a refusal says why");
            }
        }
    }

    /**
     * Not written: the place asked to wait.
     *
     * @param retryAfter How long, at least.
     */
    record RateLimited(Duration retryAfter) implements WriteOutcome {
        /** Refuses a negative wait. */
        public RateLimited {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("a wait is not negative");
            }
        }
    }

    /** The request was sent and no answer says whether it was written; it is never sent again. */
    record OutcomeUnknown() implements WriteOutcome {}
}
