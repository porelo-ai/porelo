package org.fuin.porelo.daemon;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.app.OwnPullRequests;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.TaskBranch;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Takes the role and place a Start or CanStart names, and refuses one its task cannot work at,
 * before anything is made: the call's fields read here, checked by {@link RunPlaceCheck} as {@code
 * porelo task start} checks its options.
 */
final class RunPlaces {

    private RunPlaces() {}

    /**
     * Returns the role and place a start names, with the check every start asks.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate.
     * @return The role and place, or {@code null}.
     * @throws VarlinkException As {@link #taken(PoreloContext, Map, Path, String, String, boolean,
     *     RunPlaceCheck.Forges, RunPlaceCheck.PullRequestStarts)}.
     */
    static @Nullable RunPlace taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated) {
        return taken(parameters, projectFile, task, repository, gated, RunPlaceCheck.of(context));
    }

    /**
     * Returns the role and place a start names, checked against the task it starts.
     *
     * <p>Every refusal stands whether the task runs or not: a task that stops before its launch
     * looks would otherwise start in a role or at a place it was refused. A running task asked for
     * what it may have answers as it does without them.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate: not with noGate, readOnly or
     *     allRepositories.
     * @param forges This machine's forges, adapters and vault, for a pull request's place.
     * @param starts Told of a start at a pull request once its checks passed.
     * @return The role and place, or {@code null} when the start names neither, or when its task
     *     has no gate and names only a role.
     * @throws VarlinkException {@code InvalidParameter}, {@code PlaceUnreadable}, {@code
     *     UnknownForge}, {@code NotTheTasksPush} or {@code RoleNotAllowed}.
     */
    static @Nullable RunPlace taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final RunPlaceCheck.Forges forges,
            final RunPlaceCheck.PullRequestStarts starts) {
        return taken(
                parameters,
                projectFile,
                task,
                repository,
                gated,
                new RunPlaceCheck(context, forges, starts));
    }

    /**
     * Returns the role and place a start names, an author's own pull request found as given.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate.
     * @param forges This machine's forges, adapters and vault, for a pull request's place.
     * @param own Finds an author's own pull request, open from its branch.
     * @param starts Told of a start at a pull request once its checks passed.
     * @return The role and place, or {@code null}.
     * @throws VarlinkException As the other forms.
     */
    static @Nullable RunPlace taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final RunPlaceCheck.Forges forges,
            final OwnPullRequests own,
            final RunPlaceCheck.PullRequestStarts starts) {
        return taken(
                parameters,
                projectFile,
                task,
                repository,
                gated,
                new RunPlaceCheck(context, forges, own, starts));
    }

    private static @Nullable RunPlace taken(
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final RunPlaceCheck check) {
        final String role = role(parameters);
        final RunPlaceCheck.Place place = place(parameters);
        try {
            return check.taken(
                    projectFile,
                    task,
                    repository,
                    gated,
                    role,
                    place,
                    () -> branch(parameters, projectFile, task, repository));
        } catch (RunPlaceCheck.Refused refused) {
            throw new VarlinkException(refused.refusal().error(), refused.refusal().fields());
        }
    }

    /** The task's branch as the launch settles it from the same call, or null when it does not. */
    private static @Nullable String branch(
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository) {
        try {
            return TaskBranch.of(
                    projectFile,
                    repository,
                    task,
                    PoreloDaemon.values(parameters),
                    PoreloDaemon.empty(parameters, "branch"));
        } catch (RuntimeException ex) {
            // Values the launch refuses are refused by it, in its own words.
            return null;
        }
    }

    private static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                RunPlaceCheck.INVALID_PARAMETER, Map.of("parameter", parameter));
    }

    private static @Nullable String role(final Map<String, Object> parameters) {
        final Object given = parameters.get("role");
        if (given == null) {
            return null;
        }
        if (RunPlace.AUTHOR.equals(given) || RunPlace.REVIEWER.equals(given)) {
            return (String) given;
        }
        throw invalid("role");
    }

    /** The place as given, with exactly one field set, or {@code null} when none was given. */
    private static RunPlaceCheck.@Nullable Place place(final Map<String, Object> parameters) {
        final Object given = parameters.get("place");
        if (given == null) {
            return null;
        }
        if (!(given instanceof Map<?, ?> place)) {
            throw invalid("place");
        }
        final Object push = place.get("gatePush");
        final Object pullRequest = place.get("pullRequest");
        final Object ownPullRequest = place.get("ownPullRequest");
        final long set =
                java.util.stream.Stream.of(push, pullRequest, ownPullRequest)
                        .filter(Objects::nonNull)
                        .count();
        if (set != 1) {
            throw invalid("place");
        }
        if (pullRequest instanceof String url) {
            return new RunPlaceCheck.Place.PullRequest(url);
        }
        if (ownPullRequest instanceof Boolean) {
            return new RunPlaceCheck.Place.OwnPullRequest();
        }
        if (push instanceof Map<?, ?> fields
                && fields.get("name") instanceof String name
                && (fields.get("repository") == null
                        || fields.get("repository") instanceof String)) {
            return new RunPlaceCheck.Place.GatePush((String) fields.get("repository"), name);
        }
        throw invalid("place");
    }
}
