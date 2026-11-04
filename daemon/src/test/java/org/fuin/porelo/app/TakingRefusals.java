package org.fuin.porelo.app;

/** Makes a refusal of a take, whose constructor stays in its package, for the daemon's tests. */
public final class TakingRefusals {

    private TakingRefusals() {}

    /**
     * Returns a refusal.
     *
     * @param reason The contract's word.
     * @param words What it says.
     * @return It.
     */
    public static ProjectTaking.Refused refusal(final String reason, final String words) {
        return new ProjectTaking.Refused(65, reason, words);
    }
}
