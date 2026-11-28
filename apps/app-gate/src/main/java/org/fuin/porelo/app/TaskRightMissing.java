package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;

/**
 * A task's item refused because its start did not record the right the item needs. On the wire it
 * stays the contract's {@code NoSuchThread}; to the task it says {@code RightMissing: <right>
 * (<act>)}, the one form a missing right is said in.
 */
public final class TaskRightMissing extends ReviewRefused {

    private static final long serialVersionUID = 1L;

    private final String rule;

    /**
     * Constructor with the rule broken.
     *
     * @param rule {@code RightMissing: <right> (<act>)}.
     */
    public TaskRightMissing(final String rule) {
        super(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    /**
     * Returns the rule broken, as the task reads it.
     *
     * @return {@code RightMissing: <right> (<act>)}.
     */
    public String rule() {
        return rule;
    }
}
