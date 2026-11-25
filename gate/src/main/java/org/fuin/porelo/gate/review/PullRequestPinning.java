package org.fuin.porelo.gate.review;

/**
 * Fetches a pull request's head read-only into a task's gate and pins it for one run.
 *
 * <p>A stand-in answers a fixed push.
 */
public interface PullRequestPinning {

    /**
     * Pins the pull request's head for a run of the task.
     *
     * @param ref The pull request.
     * @param task The task's recorded container id.
     * @return The pinned push: its head the commit reviewed, its base the merge base.
     * @throws PlaceUnreadable When it cannot be read or fetched; nothing was made.
     */
    Push pin(PullRequestRef ref, String task) throws PlaceUnreadable;
}
