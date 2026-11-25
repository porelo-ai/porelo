package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Takes the role and place {@code porelo task start} names, checked against the task it starts
 * before anything is made.
 *
 * <p>Plugged in by the binary: the check reads this machine's forges, which the task's area does
 * not depend on.
 */
@FunctionalInterface
public interface StartPlaces {

    /** Plugged in nowhere: a start naming a role or a place is refused as unavailable. */
    StartPlaces NONE =
            (context, projectFile, task, repository, gated, role, place, branch) -> {
                if (role == null && place == null) {
                    return null;
                }
                throw new Refused(69, "--role and --place are not checked by this build");
            };

    /**
     * Returns the role and place a start names.
     *
     * @param context The machine.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate.
     * @param role The role as typed, {@code author} or {@code reviewer}, or {@code null}.
     * @param place The place as typed: a pull request's https address, {@code own} or {@code
     *     push:<name>}, or {@code null}.
     * @param branch The task's branch as its launch settles it, or {@code null} when the launch
     *     refuses it; asked only for the task's own pull request.
     * @return The role and place to start at, or {@code null} for the start's default.
     * @throws Refused When the start is refused.
     */
    @Nullable RunPlace taken(
            PoreloContext context,
            Path projectFile,
            String task,
            @Nullable String repository,
            boolean gated,
            @Nullable String role,
            @Nullable String place,
            java.util.function.Supplier<@Nullable String> branch);

    /** A start refused for its role or place, with the exit code it ends with. */
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
