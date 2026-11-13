package org.fuin.porelo.core.project;

import org.jspecify.annotations.Nullable;

/**
 * What one task container may consume.
 *
 * <p>An agent in YOLO mode runs commands nobody reviewed, so a runaway build is a normal outcome
 * rather than an attack. Memory is the one that takes the host down; podman already caps processes
 * at 2048, which is pinned here so the protection does not depend on a distribution default.
 *
 * @param memory Value for {@code --memory}, for example {@code 8g}, or {@code null} for no limit.
 * @param cpus Value for {@code --cpus}, for example {@code 2.0}, or {@code null} for no limit.
 * @param pids Value for {@code --pids-limit}.
 * @param handIn Bytes one file handed to a running task may have.
 * @param subagents The most subagents a task's agent may run at the same time; 0 for none.
 * @param passOn How long an online task's gate waits for the forge to take a push it passes on.
 */
public record Limits(
        @Nullable String memory,
        @Nullable String cpus,
        int pids,
        long handIn,
        int subagents,
        java.time.Duration passOn) {

    /** Memory a task gets unless the project says otherwise. */
    public static final String DEFAULT_MEMORY = "8g";

    /** CPUs a task gets unless the project says otherwise. */
    public static final String DEFAULT_CPUS = "4";

    /** How a project opts out of a memory or CPU limit on purpose. */
    public static final String NONE = "none";

    /** Processes a task gets unless the project says otherwise, matching podman's own default. */
    public static final int DEFAULT_PIDS = 2048;

    /**
     * Bytes one file handed to a running task may have unless the project says otherwise: a build
     * log or a screenshot fits, a dataset is asked for on purpose.
     */
    public static final long DEFAULT_HAND_IN = 64L * 1024 * 1024;

    /**
     * How long a gate waits for the forge to take a push unless the project says otherwise: long
     * enough for a large push to a forge that answers slowly, short of holding one of the gate's
     * slots for minutes.
     */
    public static final java.time.Duration DEFAULT_PASS_ON = java.time.Duration.ofMinutes(2);

    /** The shortest pass-on limit a project may set. */
    public static final java.time.Duration SHORTEST_PASS_ON = java.time.Duration.ofSeconds(10);

    /** The longest pass-on limit a project may set; there is no 'none', since a push must end. */
    public static final java.time.Duration LONGEST_PASS_ON = java.time.Duration.ofHours(1);

    /** A time as the project file writes it: a whole number with s, m or h. */
    private static final java.util.regex.Pattern TIME =
            java.util.regex.Pattern.compile("(\\d{1,9})([smh])");

    /**
     * A size as the project file writes it: a whole number, optionally with k, m or g (powers of
     * 1024).
     */
    private static final java.util.regex.Pattern SIZE =
            java.util.regex.Pattern.compile("(\\d+)([kKmMgG]?)");

    /**
     * Returns the limits a project gets when it declares none.
     *
     * @return Defaults.
     */
    public static Limits defaults() {
        return new Limits(
                DEFAULT_MEMORY, DEFAULT_CPUS, DEFAULT_PIDS, DEFAULT_HAND_IN, 0, DEFAULT_PASS_ON);
    }

    /**
     * Returns the thread hint for a CPU limit, shared by tasks and image builds.
     *
     * @param cpus The CPU limit.
     * @return The limit rounded down, at least one, for {@code OMP_NUM_THREADS}.
     */
    public static String threadHint(String cpus) {
        return new java.math.BigDecimal(cpus)
                .setScale(0, java.math.RoundingMode.DOWN)
                .max(java.math.BigDecimal.ONE)
                .toPlainString();
    }

    /**
     * Constructor.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     * @param handIn Bytes one file handed to a running task may have.
     * @param subagents The most subagents at the same time.
     * @param passOn How long a gate waits for the forge to take a push.
     */
    public Limits {
        if (pids < 1) {
            throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
        }
        if (handIn < 1) {
            throw new ProjectException("A hand-in limit of " + handIn + " bytes lets no file in");
        }
        if (subagents < 0) {
            throw new ProjectException("A subagent limit of " + subagents + " is below none");
        }
        if (passOn.compareTo(SHORTEST_PASS_ON) < 0 || passOn.compareTo(LONGEST_PASS_ON) > 0) {
            throw new ProjectException(
                    "A pass-on limit of "
                            + passOn.toSeconds()
                            + " s is outside ten seconds to one hour");
        }
    }

    /**
     * Constructor for limits whose gate waits the default for the forge.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     * @param handIn Bytes one file handed to a running task may have.
     * @param subagents The most subagents at the same time.
     */
    public Limits(
            @Nullable String memory, @Nullable String cpus, int pids, long handIn, int subagents) {
        this(memory, cpus, pids, handIn, subagents, DEFAULT_PASS_ON);
    }

    /**
     * Constructor for limits that allow no subagents.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     * @param handIn Bytes one file handed to a running task may have.
     */
    public Limits(@Nullable String memory, @Nullable String cpus, int pids, long handIn) {
        this(memory, cpus, pids, handIn, 0, DEFAULT_PASS_ON);
    }

    /**
     * Returns the time a time names.
     *
     * @param time What the project file says, such as {@code "90s"}, {@code "2m"} or {@code "1h"}.
     * @param key The key it was written under, for the refusal.
     * @return The time.
     * @throws ProjectException When it is not a time.
     */
    public static java.time.Duration duration(final String time, final String key) {
        final java.util.regex.Matcher matcher = TIME.matcher(time.strip());
        if (!matcher.matches()) {
            throw new ProjectException(
                    "'" + key + "' must be a time such as 90s, 2m or 1h, not '" + time + "'");
        }
        final long count = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> java.time.Duration.ofSeconds(count);
            case "m" -> java.time.Duration.ofMinutes(count);
            default -> java.time.Duration.ofHours(count);
        };
    }

    /**
     * Returns the pass-on limit a time names, refused outside ten seconds to one hour.
     *
     * @param time What the project file says.
     * @param key The key it was written under, for the refusal.
     * @return The limit.
     * @throws ProjectException When it is not a time, or outside the bounds.
     */
    public static java.time.Duration passOn(final String time, final String key) {
        final java.time.Duration limit = duration(time, key);
        if (limit.compareTo(SHORTEST_PASS_ON) < 0 || limit.compareTo(LONGEST_PASS_ON) > 0) {
            throw new ProjectException(
                    "'" + key + "' must lie between 10s and 1h, not '" + time + "'");
        }
        return limit;
    }

    /**
     * Returns the bytes a size names.
     *
     * @param size What the project file says, such as {@code "64m"}.
     * @param key The key it was written under, for the refusal.
     * @return The bytes.
     * @throws ProjectException When it is not a size.
     */
    public static long bytes(final String size, final String key) {
        final java.util.regex.Matcher matcher = SIZE.matcher(size.strip());
        if (!matcher.matches()) {
            throw new ProjectException(
                    "'" + key + "' must be a size such as 64m, not '" + size + "'");
        }
        final long unit =
                switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                    case "k" -> 1024L;
                    case "m" -> 1024L * 1024;
                    case "g" -> 1024L * 1024 * 1024;
                    default -> 1L;
                };
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), unit);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new ProjectException("'" + key + "' is too large: '" + size + "'");
        }
    }
}
