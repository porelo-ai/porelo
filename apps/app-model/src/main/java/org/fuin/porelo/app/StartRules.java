package org.fuin.porelo.app;

import java.util.Optional;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * The rules Porelo keeps itself for every start, whoever asks and by hand too, checked before
 * anything is made: a start that breaks one is refused as the contract's {@code StartRuleBroken}.
 *
 * <p>A task that reads another task's work reads what nobody has reviewed, so nothing that leads
 * out of the machine goes with it: no project services, no push, no added credential, and a
 * clearance that never lets a blocked connection through. A task never holds a judgment together
 * with push, or over its own work, and pushes only from its own work.
 */
public final class StartRules {

    /** The rule a task at another task's work breaks with anything that leads out. */
    static final String FOREIGN_WORK_LEADS_OUT = "foreignWorkLeadsOut";

    private StartRules() {}

    /**
     * Returns the rule a start breaks.
     *
     * @param parts The parts the start names, or the task recorded.
     * @param addedCredentials Whether the start names credentials beyond the project's.
     * @param clearance What the start asks to happen with a blocked connection, or {@code null}
     *     when it names nothing.
     * @return The broken rule with the part that breaks it, or empty for a start that keeps them
     *     all.
     */
    public static Optional<TaskParts.Broken> broken(
            final TaskParts parts,
            final boolean addedCredentials,
            final @Nullable String clearance) {
        final Optional<TaskParts.Broken> byParts = parts.broken();
        if (byParts.isPresent() || !parts.readsAnothersWork()) {
            return byParts;
        }
        if (addedCredentials) {
            return Optional.of(new TaskParts.Broken(FOREIGN_WORK_LEADS_OUT, "credentials"));
        }
        if ("allow".equals(clearance) || "off".equals(clearance)) {
            return Optional.of(
                    new TaskParts.Broken(FOREIGN_WORK_LEADS_OUT, "clearance " + clearance));
        }
        return Optional.empty();
    }

    /**
     * Returns a broken rule as a person is told it.
     *
     * @param broken The rule and the part that breaks it.
     * @return {@code StartRuleBroken: <rule> (<detail>)}.
     */
    public static String said(final TaskParts.Broken broken) {
        return "StartRuleBroken: " + broken.rule() + " (" + broken.detail() + ")";
    }
}
