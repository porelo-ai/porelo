package org.fuin.porelo.gate.review;

/**
 * Where in a push a comment points. Only {@link LineAnchor} exists now; file and general anchors
 * are later cases.
 */
public sealed interface Anchor permits LineAnchor {

    /**
     * Returns whether a suggestion may stand at this anchor: only on the new side, which a commit
     * can take.
     *
     * @return True on the new side.
     */
    boolean takesSuggestion();
}
