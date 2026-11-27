package org.fuin.porelo.app;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.fuin.porelo.gate.review.CommentGate;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.jspecify.annotations.Nullable;

/**
 * Starts the run of a task that reads another task's push waiting at the gate, once the task is
 * made and before its agent runs: {@link RunStart} pins the push's head and base into the task's
 * gate, reads the push's threads from this machine's review store and delivers them to the task. No
 * account is used and nothing leaves the machine.
 *
 * <p>Refusals are the task contract's {@code PlaceUnreadable(reason, detail)}, and {@code
 * Failed(message)} for a delivery that failed. No run of the task is current after a refusal.
 */
public final class WaitingPushRunStart {

    /**
     * Names the project a task belongs to.
     *
     * <p>A stand-in answers a fixed project.
     */
    @FunctionalInterface
    public interface Projects {

        /**
         * Returns a task's project.
         *
         * @param task The task's container name.
         * @return Its project's name.
         * @throws PlaceUnreadable As {@code NotInProject} when the task is not found.
         */
        String of(String task) throws PlaceUnreadable;
    }

    private final Projects projects;
    private final CommentIntake.Parts parts;
    private final WaitingPushPlace.Pinning pinning;
    private final ReviewHolds holds;
    private final CurrentRuns runs;
    private final Function<String, CommentGate> gates;
    private final ConversationDelivery delivery;

    /**
     * Constructor.
     *
     * @param projects Names a task's project.
     * @param parts The review's parts: the waiting pushes, the check, the store and the writer.
     * @param pinning Pins a push into a reading task's gate.
     * @param holds The held items.
     * @param runs The run each task is in now.
     * @param gates Judges each comment of a task's run by the place's reading setting, by the
     *     task's container name.
     * @param delivery Hands the task its conversation.
     */
    public WaitingPushRunStart(
            final Projects projects,
            final CommentIntake.Parts parts,
            final WaitingPushPlace.Pinning pinning,
            final ReviewHolds holds,
            final CurrentRuns runs,
            final Function<String, CommentGate> gates,
            final ConversationDelivery delivery) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.pinning = Objects.requireNonNull(pinning, "pinning");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    /**
     * Starts the task's run at the waiting push its place names.
     *
     * @param container The task's container, which keys its run, its holds and its mailbox.
     * @param place Its parts and place; one that is a pull request, or no other task's push, starts
     *     nothing here.
     * @return What the run was given, or null where nothing starts here.
     * @throws RunPlaceCheck.Refused As the class says, when the run could not start.
     */
    public RunStart.@Nullable Outcome begin(final String container, final RunPlace place) {
        if (place.pullRequest() != null || !place.beginsRun()) {
            return null;
        }
        final WaitingPushPlace at;
        try {
            at =
                    new WaitingPushPlace(
                            projects.of(container),
                            place.repository(),
                            place.name(),
                            parts,
                            pinning);
        } catch (PlaceUnreadable ex) {
            throw refused(ex.reason(), String.valueOf(ex.getMessage()));
        }
        final RunStart start =
                new RunStart(
                        holds,
                        runs,
                        gates.apply(container),
                        delivery,
                        lent -> WaitingPushPlace.NO_ACCOUNT);
        try {
            return start.start(container, at);
        } catch (RunRefused ex) {
            final PlaceUnreadable.Reason reason = ex.reason();
            if (reason == null) {
                throw new RunPlaceCheck.Refused(
                        RunPlaceCheck.Refusal.unavailable(
                                "Failed",
                                Map.of("message", String.valueOf(ex.getMessage())),
                                String.valueOf(ex.getMessage())));
            }
            throw refused(reason, String.valueOf(ex.getMessage()));
        }
    }

    private static RunPlaceCheck.Refused refused(
            final PlaceUnreadable.Reason reason, final String detail) {
        return new RunPlaceCheck.Refused(RunPlaceCheck.unreadable(reason, detail));
    }
}
