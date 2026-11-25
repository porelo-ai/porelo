package org.fuin.porelo.build.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A pull request as its forge answered it at one moment.
 *
 * @param repository The https address of the repository its head is read from.
 * @param head The commit reviewed: the pull request's head, for a merged one the head that merged.
 * @param base The base branch's commit.
 * @param mergeBase The merge base of head and base.
 * @param state Open, merged or closed.
 * @param approved The forge's observed verdict at the head, read only.
 * @param mergeCommit The merge commit exactly when merged, else {@code null}.
 * @param mergedHead The branch the merge commit went onto exactly when merged, else {@code null}.
 */
public record ReviewFacts(
        String repository,
        String head,
        String base,
        String mergeBase,
        State state,
        boolean approved,
        @Nullable String mergeCommit,
        @Nullable String mergedHead) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** Where a pull request stands at its forge. */
    public enum State {
        /** Open: its head may still move. */
        OPEN,
        /** Merged: its head went onto its base. */
        MERGED,
        /** Closed without a merge. */
        CLOSED;

        /**
         * Returns the state's name on the wire.
         *
         * @return Lower case.
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static State of(final String wire) {
            for (final State state : values()) {
                if (state.wire().equals(wire)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("no pull request state '" + wire + "'");
        }
    }

    /**
     * Refuses a repository not on https, a commit not written in full, and a merge commit or branch
     * on a pull request that is not merged, or missing on one that is.
     */
    public ReviewFacts {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(mergeBase, "mergeBase");
        Objects.requireNonNull(state, "state");
        if (!repository.startsWith("https://")) {
            throw new IllegalArgumentException("the repository's address is https");
        }
        for (final String commit : new String[] {head, base, mergeBase}) {
            if (!commit.matches(COMMIT)) {
                throw new IllegalArgumentException("a commit is not a full commit id");
            }
        }
        final boolean merged = state == State.MERGED;
        if (merged != (mergeCommit != null) || merged != (mergedHead != null)) {
            throw new IllegalArgumentException(
                    "a merge commit and its branch are given exactly when merged");
        }
        if (mergeCommit != null && !mergeCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("the merge commit is not a full commit id");
        }
        if (mergedHead != null && mergedHead.isBlank()) {
            throw new IllegalArgumentException("the merged branch is blank");
        }
    }

    /** The answer on the wire. */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("repository", repository);
        map.put("head", head);
        map.put("base", base);
        map.put("mergeBase", mergeBase);
        map.put("state", state.wire());
        map.put("approved", Boolean.valueOf(approved));
        if (mergeCommit != null) {
            map.put("mergeCommit", mergeCommit);
        }
        if (mergedHead != null) {
            map.put("mergedHead", mergedHead);
        }
        return map;
    }

    /**
     * Reads an answer on the wire.
     *
     * @param map The answer.
     * @return The facts.
     * @throws IllegalArgumentException When the answer is not a pull request's facts.
     */
    public static ReviewFacts of(final Map<String, Object> map) {
        if (!(map.get("approved") instanceof Boolean approved)) {
            throw new IllegalArgumentException("approved is not a boolean");
        }
        return new ReviewFacts(
                text(map, "repository"),
                text(map, "head"),
                text(map, "base"),
                text(map, "mergeBase"),
                State.of(text(map, "state")),
                approved.booleanValue(),
                optional(map, "mergeCommit"),
                optional(map, "mergedHead"));
    }

    private static String text(final Map<String, Object> map, final String name) {
        if (!(map.get(name) instanceof String value)) {
            throw new IllegalArgumentException(name + " is not a string");
        }
        return value;
    }

    private static @Nullable String optional(final Map<String, Object> map, final String name) {
        final Object value = map.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " is not a string");
        }
        return text;
    }
}
