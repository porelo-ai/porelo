package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;

/**
 * Counts what is still open in a push's review, for whoever decides it.
 *
 * <p>A stand-in answers fixed counts.
 */
public interface ReviewCounts {

    /**
     * Counts a push's open findings, open threads and runs.
     *
     * @param push The push.
     * @return The counts; no runs when nothing was reviewed.
     */
    Counts of(Push push);

    /**
     * A push's counts.
     *
     * @param openFindings Findings not yet answered.
     * @param openThreads Threads still open.
     * @param runs Each run that reviewed the push.
     */
    record Counts(int openFindings, int openThreads, List<Run> runs) {

        /** Refuses a negative count and copies the runs. */
        public Counts {
            if (openFindings < 0 || openThreads < 0) {
                throw new IllegalArgumentException("a count is negative");
            }
            runs = List.copyOf(runs);
        }
    }

    /**
     * One run that reviewed a push.
     *
     * @param name The run's name.
     * @param completeness How completely it reviewed.
     * @param findings How many findings it gave.
     */
    record Run(String name, Completeness completeness, int findings) {

        /** Refuses a missing name or word. */
        public Run {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(completeness, "completeness");
            if (findings < 0) {
                throw new IllegalArgumentException("a run's findings are negative");
            }
        }
    }
}
