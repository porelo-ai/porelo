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
 */
public record Limits(
        @Nullable String memory, @Nullable String cpus, int pids, long handIn, int subagents) {

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
        return new Limits(DEFAULT_MEMORY, DEFAULT_CPUS, DEFAULT_PIDS, DEFAULT_HAND_IN, 0);
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
        this(memory, cpus, pids, handIn, 0);
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
