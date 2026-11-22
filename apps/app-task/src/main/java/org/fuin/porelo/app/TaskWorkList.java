package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Every issue and phase a task was started to work on, in the order given: the first is the one its
 * gate judges by and its guide names, as a task given one pair has it.
 *
 * @param pairs The pairs, as given.
 */
public record TaskWorkList(List<TaskWork> pairs) {

    /** The most pairs one start names. */
    public static final int MOST = 16;

    /** Keeps the pairs unchangeable. */
    public TaskWorkList {
        pairs = List.copyOf(pairs);
    }

    /**
     * Reads {@code --work <issue>:<phase>} given once or more.
     *
     * @param given The option's values, in their order.
     * @return The work, each pair as {@link TaskWork#parse} reads it.
     */
    public static TaskWorkList parse(final List<String> given) {
        return new TaskWorkList(given.stream().map(TaskWork::parse).toList());
    }

    /**
     * Reads it as it crossed the wire or is kept in a record: a list of {@code issue} and {@code
     * phase}, or one such pair as an older client sends and an older record keeps it.
     *
     * @param value The value, or {@code null}.
     * @return The work, or {@code null} when none was given.
     * @throws IllegalArgumentException If it is neither, or a pair's issue or phase is no string:
     *     refused, never taken as no work.
     */
    public static @Nullable TaskWorkList of(final @Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> pair) {
            return new TaskWorkList(List.of(pair(pair)));
        }
        if (!(value instanceof List<?> given)) {
            throw new IllegalArgumentException("work is a list of issue and phase");
        }
        final List<TaskWork> pairs = new ArrayList<>();
        for (final Object item : given) {
            if (!(item instanceof Map<?, ?> pair)) {
                throw new IllegalArgumentException("each work is an issue and a phase");
            }
            pairs.add(pair(pair));
        }
        return new TaskWorkList(pairs);
    }

    private static TaskWork pair(final Map<?, ?> fields) {
        if (!(fields.get("issue") instanceof String issue)
                || !(fields.get("phase") instanceof String phase)) {
            throw new IllegalArgumentException("a work's issue and phase are strings");
        }
        return new TaskWork(issue, phase);
    }

    /**
     * Says why this work cannot be kept with a task, or {@code null}.
     *
     * @return The refusal, a usage error: for an empty list, more than {@value #MOST} pairs, a pair
     *     {@link TaskWork#refusal()} refuses, or an issue named twice.
     */
    public @Nullable String refusal() {
        if (pairs.isEmpty()) {
            return "the work names no issue; it takes <issue>:<phase>, once or more";
        }
        if (pairs.size() > MOST) {
            return "the work names " + pairs.size() + " issues, at most " + MOST;
        }
        final Set<String> seen = new HashSet<>();
        for (final TaskWork pair : pairs) {
            final String refused = pair.refusal();
            if (refused != null) {
                return refused;
            }
            if (!seen.add(pair.issue())) {
                return "the work names the issue " + pair.issue() + " twice; name each once";
            }
        }
        return null;
    }

    /**
     * Returns the pair the task's gate judges by and its guide names.
     *
     * @return The first pair.
     * @throws IllegalStateException For an empty list, which {@link #refusal()} refuses.
     */
    public TaskWork first() {
        if (pairs.isEmpty()) {
            throw new IllegalStateException("the work names no issue");
        }
        return pairs.getFirst();
    }

    /**
     * Returns every issue, in order.
     *
     * @return The issues.
     */
    public Set<String> issues() {
        final Set<String> issues = new LinkedHashSet<>();
        pairs.forEach(pair -> issues.add(pair.issue()));
        return issues;
    }

    /**
     * Returns it as it crosses the wire and is stored.
     *
     * @return One {@code issue} and {@code phase} per pair.
     */
    public List<Map<String, Object>> asList() {
        return pairs.stream().map(TaskWork::asMap).toList();
    }

    /**
     * Returns it as the command line takes it.
     *
     * @return One {@code --work <issue>:<phase>} per pair, joined by a space.
     */
    public String options() {
        return String.join(
                " ",
                pairs.stream().map(pair -> "--work " + pair.issue() + ":" + pair.phase()).toList());
    }
}
