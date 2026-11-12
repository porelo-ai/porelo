package org.fuin.porelo.app;

import java.time.Duration;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AtRest;
import org.fuin.porelo.agent.api.Waiting;
import org.jspecify.annotations.Nullable;

/**
 * Whether an attached agent's screen shows it at rest at its prompt: the one rule the wake types by
 * and a listing says {@code at rest} by, so the two never disagree about the same screen.
 *
 * <p>Read only for an agent that declares both what its screen shows at rest and what a question to
 * a person looks like on it: one that cannot say when a question is open cannot be told at rest
 * from waiting for an answer.
 */
public final class Rest {

    /**
     * How far apart two looks at the screen must be for rest to hold: an agent's work line comes
     * and goes between its tool calls, and one look can fall into such a gap.
     */
    public static final Duration LOOK = Duration.ofMillis(1500);

    private Rest() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether an agent declares enough for its rest to be read.
     *
     * @param definition The agent.
     * @return {@code true} when it declares {@code at_rest} and screen rules for waiting.
     */
    public static boolean readable(final AgentDefinition definition) {
        return readable(definition.waiting(), definition.atRest());
    }

    /**
     * Says whether these declarations are enough for rest to be read.
     *
     * @param waiting What waiting for a person looks like, or {@code null}.
     * @param atRest What rest looks like, or {@code null}.
     * @return {@code true} when both are there and waiting has rules for the screen.
     */
    public static boolean readable(final @Nullable Waiting waiting, final @Nullable AtRest atRest) {
        return waiting != null && atRest != null && !waiting.screen().isEmpty();
    }

    /**
     * Says whether one screen shows the agent at rest, with no question to a person open.
     *
     * @param shown The screen's text, as drawn.
     * @param waiting What waiting for a person looks like, or {@code null}.
     * @param atRest What rest looks like, or {@code null}.
     * @return {@code true} when it rests on this screen; {@code false} for an agent that does not
     *     declare enough to say.
     */
    public static boolean shows(
            final String shown, final @Nullable Waiting waiting, final @Nullable AtRest atRest) {
        return waiting != null
                && atRest != null
                && !waiting.screen().isEmpty()
                && waiting.read(shown).seen() != Waiting.Seen.WAITING
                && atRest.matches(shown);
    }
}
