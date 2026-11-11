package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a task was started to work on: one issue and one phase of the project's own workflow, named
 * by whoever started it and kept as given.
 *
 * @param issue The issue, such as {@code ISSUE-1}.
 * @param phase A working state of the project's workflow, such as {@code Implementing}; Porelo does
 *     not know the set.
 */
public record TaskWork(String issue, String phase) {

    /** The most characters of an issue or a phase. */
    public static final int MOST = 64;

    /**
     * Reads {@code --work <issue>:<phase>}.
     *
     * @param given The option's value.
     * @return The work, split at its first colon; a value without one has an empty phase, which
     *     {@link #refusal()} refuses.
     */
    public static TaskWork parse(final String given) {
        final int colon = given.indexOf(':');
        return colon < 0
                ? new TaskWork(given, "")
                : new TaskWork(given.substring(0, colon), given.substring(colon + 1));
    }

    /**
     * Reads it as it crossed the wire.
     *
     * @param value The call's {@code work}, or {@code null}.
     * @return The work, or {@code null} when none was given.
     */
    public static @Nullable TaskWork of(final @Nullable Object value) {
        if (!(value instanceof Map<?, ?> fields)) {
            return null;
        }
        return new TaskWork(text(fields.get("issue")), text(fields.get("phase")));
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Says why this work cannot be kept with a task, or {@code null}.
     *
     * @return The refusal, a usage error; {@code null} when both are one line of 1 to {@value
     *     #MOST} characters.
     */
    public @Nullable String refusal() {
        final String issue = refused("issue", this.issue);
        return issue != null ? issue : refused("phase", phase);
    }

    private static @Nullable String refused(final String what, final String value) {
        if (value.isBlank()) {
            return "the work's " + what + " is missing; it takes <issue>:<phase>";
        }
        if (value.length() > MOST) {
            return "the work's " + what + " has " + value.length() + " characters, at most " + MOST;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "the work's " + what + " is one line, without control characters";
        }
        return null;
    }

    /**
     * Returns it as it crosses the wire and is stored.
     *
     * @return {@code issue} and {@code phase}.
     */
    public Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("issue", issue);
        map.put("phase", phase);
        return map;
    }
}
