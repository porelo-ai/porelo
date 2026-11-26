package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A commit a check has to read is not in the mirror it was given: the gate never forwarded it
 * there. Nothing at the commit was judged, so no comment on it passes.
 */
public final class CommitNotInMirror extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String commit;

    /**
     * Constructor.
     *
     * @param commit The commit looked for.
     */
    public CommitNotInMirror(final String commit) {
        super("The commit is not in the mirror");
        this.commit = Objects.requireNonNull(commit, "commit");
    }

    /**
     * Returns the commit looked for.
     *
     * @return Its object id.
     */
    public String commit() {
        return commit;
    }
}
