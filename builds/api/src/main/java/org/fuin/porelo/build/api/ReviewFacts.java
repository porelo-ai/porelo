package org.fuin.porelo.build.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
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
 * @param decidedAt When the forge merged or closed it, exactly when merged or closed, else {@code
 *     null}.
 */
public record ReviewFacts(
        String repository,
        String head,
        String base,
        String mergeBase,
        State state,
        boolean approved,
        @Nullable String mergeCommit,
        @Nullable String mergedHead,
        @Nullable Instant decidedAt) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** RFC 3339 in UTC, as the contract writes a time: a date, a time, its fraction if any, Z. */
    private static final Pattern UTC =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");

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
            throw new IllegalArgumentException("no pull request state is open, merged or closed");
        }
    }

    /**
     * Refuses a repository that is no https address of profile 2's shape, a commit not written in
     * full, a merged branch git would not accept, and a merge commit or branch on a pull request
     * that is not merged, or missing on one that is, and a decision time on a pull request that is
     * open, or missing on one merged or closed.
     */
    public ReviewFacts {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(mergeBase, "mergeBase");
        Objects.requireNonNull(state, "state");
        if (!AdapterText.isRepository(repository)) {
            throw new IllegalArgumentException(
                    "the repository's address is https://<host>[:<port>] and 2 to 8 segments");
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
        if ((state == State.OPEN) != (decidedAt == null)) {
            throw new IllegalArgumentException(
                    "decidedAt is given exactly when the pull request is merged or closed");
        }
        if (mergeCommit != null && !mergeCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("the merge commit is not a full commit id");
        }
        if (mergedHead != null && !AdapterText.isBranch(mergedHead)) {
            throw new IllegalArgumentException("the merged branch is no branch name git accepts");
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
        if (decidedAt != null) {
            map.put("decidedAt", decidedAt.toString());
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
                optional(map, "mergedHead"),
                time(optional(map, "decidedAt")));
    }

    /** Reads a time the contract wrote, refusing one that is no RFC 3339 time in UTC. */
    private static @Nullable Instant time(final @Nullable String text) {
        if (text == null) {
            return null;
        }
        if (!UTC.matcher(text).matches()) {
            throw new IllegalArgumentException("decidedAt is no RFC 3339 time in UTC");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("decidedAt is no RFC 3339 time in UTC", ex);
        }
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
