package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * Takes the parts and place {@code porelo task start} names, checked against the task it starts
 * before anything is made.
 *
 * <p>Plugged in by the binary: the check reads this machine's forges, which the task's area does
 * not depend on.
 */
@FunctionalInterface
public interface StartPlaces {

    /**
     * Returns the parts and place a start names.
     *
     * @param context The machine.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param asked What the start names of the parts, and its choice of workspace.
     * @param addedCredentials Whether the start names credentials beyond the project's.
     * @param clearance What the start asks to happen with a blocked connection, or {@code null}.
     * @param place The place as typed: a pull request's https address, {@code own} or {@code
     *     push:<name>}, or {@code null}.
     * @param branch The task's branch as its launch settles it, or {@code null} when the launch
     *     refuses it; asked only at a pull request.
     * @param recorded The parts an existing task of that name recorded, or {@code null} for a new
     *     task.
     * @return The parts and place to start with.
     * @throws Refused When the start is refused.
     */
    StartParts taken(
            PoreloContext context,
            Path projectFile,
            String task,
            @Nullable String repository,
            TaskParts.Asked asked,
            boolean addedCredentials,
            @Nullable String clearance,
            @Nullable String place,
            Supplier<@Nullable String> branch,
            Supplier<@Nullable TaskParts> recorded);

    /** A start refused for its parts or place, with the exit code it ends with. */
    final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int exit;

        /**
         * Constructor.
         *
         * @param exit 64 for what was typed, 69 for what is unavailable here.
         * @param said What a person is told, the refusal's name first.
         */
        public Refused(final int exit, final String said) {
            super(Objects.requireNonNull(said, "said"), null, false, false);
            this.exit = exit;
        }

        /**
         * Returns the exit code the start ends with.
         *
         * @return It.
         */
        public int exit() {
            return exit;
        }
    }
}
