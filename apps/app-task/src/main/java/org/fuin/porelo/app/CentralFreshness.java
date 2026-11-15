package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Whether a start given work on a host may work on the host's copy of its repository: the copy must
 * hold central's newest main, which the window's planner may have moved a moment ago.
 *
 * <p>The host is asked to follow now first, which brings a copy behind central up to it where it
 * can; then the central state that pass published is read, as the window reads it. Anything but a
 * copy that holds central's main refuses: a planned task on a copy that misses its own issue writes
 * a second one.
 */
final class CentralFreshness {
    private CentralFreshness() {}

    /** Asks the host to follow now, and answers how that ended. */
    @FunctionalInterface
    interface Follow {
        /**
         * Follows once.
         *
         * @return The host program's exit code: 0 when the pass ran.
         */
        int now();
    }

    /**
     * Returns why the start is refused, or {@code null} when the copy holds central's main.
     *
     * @param follow Asks the host to follow now.
     * @param central The host's published central state.
     * @param project The task's project.
     * @param host The host's record of the project.
     * @param repository The repository the task works on.
     * @param asked When the follow was asked for: a state read before it says nothing of now.
     * @return The refusal's words, or {@code null}.
     */
    static @Nullable String refusal(
            final Follow follow,
            final HostCentral central,
            final Project project,
            final OrganizationProjects.Placed host,
            final Repository repository,
            final java.time.Instant asked) {
        final int followed = follow.now();
        final boolean own = repository.name().equals(project.name());
        final Map<String, @Nullable String> rows = new LinkedHashMap<>();
        rows.put("", HostCentral.copyName(project.name(), host.address()));
        for (final Repository row : project.repositories()) {
            rows.put(row.name(), HostCentral.copyName(project.name(), row.central()));
        }
        final HostCentral.Answer answer =
                central.ask(project.name(), rows).get(own ? "" : repository.name());
        // Read by an earlier pass than the one asked for - a pass that skipped the project - says
        // nothing of now. The follow writes whole seconds.
        final boolean fresh =
                answer != null
                        && answer.readAt() != null
                        && !answer.readAt()
                                .isBefore(asked.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        final String state = answer == null ? "cannot-say" : fresh ? answer.state() : "cannot-say";
        final String name = "'" + repository.name() + "'";
        final String back =
                "bring it up with 'porelo host pull "
                        + project.name()
                        + " "
                        + repository.name()
                        + "' and a task that merges central/main, approved and pushed, then start again";
        // A follow that did not run leaves an older pass's state: it says nothing of now.
        return switch (followed != 0 ? "cannot-say" : state) {
            case "up-to-date", "ahead" -> null;
            case "moved-on" ->
                    "central's main of "
                            + name
                            + " holds commits this host's copy lacks, and the host did not take them"
                            + " by itself (a change of its workflows comes by a person's pull"
                            + " request); "
                            + back;
            case "diverged" ->
                    "this host's copy of "
                            + name
                            + " and central's main each hold commits the other lacks; "
                            + back
                            + ", or send the host's work to central first with 'porelo host push "
                            + project.name()
                            + " "
                            + repository.name()
                            + "' where central has nothing new";
            default ->
                    "whether this host's copy of "
                            + name
                            + " holds central's newest main is not known ("
                            + (followed != 0
                                    ? "the host could not follow central now, exit " + followed
                                    : answer != null && !fresh
                                            ? "the host's follow did not measure it now"
                                            : answer == null || answer.why() == null
                                                    ? "the host's central state says nothing of it"
                                                    : answer.why())
                            + "), and a task given work must not miss what its planner saved there;"
                            + " start again once 'porelo host follow' answers";
        };
    }
}
