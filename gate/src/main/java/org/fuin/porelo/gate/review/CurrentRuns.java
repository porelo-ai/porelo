package org.fuin.porelo.gate.review;

import java.util.Optional;

/**
 * The run each task is in now, kept with the task's recorded run place, so its intake, its gate and
 * its role answer for the same pinned state after a restart of the daemon.
 *
 * <p>A stand-in keeps them in memory.
 */
public interface CurrentRuns {

    /**
     * Returns the task's current run.
     *
     * @param task The task's name.
     * @return Its run, or empty when the task runs at no place or has ended.
     */
    Optional<Run> of(String task);

    /**
     * Records that a run of the task began, replacing its earlier one.
     *
     * @param task The task's name.
     * @param run The run.
     */
    void begin(String task, Run run);

    /**
     * Records that the task's run ended; its held items stay.
     *
     * @param task The task's name.
     */
    void end(String task);
}
