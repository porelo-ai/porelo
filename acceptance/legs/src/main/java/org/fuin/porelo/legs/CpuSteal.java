package org.fuin.porelo.legs;

/**
 * One reading of a machine's CPU time, from the first line of {@code /proc/stat}: how many ticks
 * passed in all, and how many of them the host gave to others while this machine had work (steal).
 *
 * <p>Read before and after a leg's build, it says whether a slow build was a slow host: on a
 * shared-vCPU type a build whose threads are all busy can still get a fraction of the CPU.
 *
 * @param total Every tick counted, steal included; negative when the line could not be read.
 * @param steal The ticks stolen.
 */
record CpuSteal(long total, long steal) {

    /** What prints the line the leg reads. */
    static final String READ = "head -1 /proc/stat";

    /**
     * Reads the first line of {@code /proc/stat}.
     *
     * @param line The line.
     * @return The reading; one with a negative total when the line is not a cpu line.
     */
    static CpuSteal parse(String line) {
        final String[] fields = line.strip().split("\\s+");
        if (fields.length < 9 || !"cpu".equals(fields[0])) {
            return new CpuSteal(-1, 0);
        }
        try {
            long total = 0;
            // user nice system idle iowait irq softirq steal; guest and guest_nice are counted in
            // user and nice.
            for (int i = 1; i <= 8; i++) {
                total += Long.parseLong(fields[i]);
            }
            return new CpuSteal(total, Long.parseLong(fields[8]));
        } catch (final NumberFormatException ex) {
            return new CpuSteal(-1, 0);
        }
    }

    /**
     * Says what share of the ticks between two readings was stolen.
     *
     * @param before The reading before.
     * @param after The reading after.
     * @return The line the leg prints.
     */
    static String between(CpuSteal before, CpuSteal after) {
        final long ticks = after.total - before.total;
        if (before.total < 0 || after.total < 0 || ticks <= 0) {
            return "the host's steal could not be read";
        }
        final long stolen = after.steal - before.steal;
        return stolen
                + " of "
                + ticks
                + " ticks stolen by the host ("
                + String.format(java.util.Locale.ROOT, "%.1f", 100.0 * stolen / ticks)
                + " %)";
    }
}
