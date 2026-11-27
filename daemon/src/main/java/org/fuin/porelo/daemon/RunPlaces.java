package org.fuin.porelo.daemon;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.fuin.porelo.app.OwnPullRequests;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.StartParts;
import org.fuin.porelo.app.TaskBranch;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Takes the parts and place a Start or CanStart names, and refuses one its task cannot work with,
 * before anything is made: the call's fields read here, checked by {@link RunPlaceCheck} as {@code
 * porelo task start} checks its options.
 */
final class RunPlaces {

    /** Members the contract no longer has, refused by name rather than ignored. */
    private static final List<String> GONE = List.of("role", "reads");

    private RunPlaces() {}

    /**
     * Returns the parts and place a start names, with the check every start asks.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @return The parts and place.
     * @throws VarlinkException As {@link #taken(PoreloContext, Map, Path, String, String,
     *     RunPlaceCheck.Forges, RunPlaceCheck.PullRequestStarts)}.
     */
    static StartParts taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository) {
        return taken(context, parameters, projectFile, task, repository, RunPlaceCheck.of(context));
    }

    /**
     * Returns the parts and place a start names, checked against the task it starts.
     *
     * <p>Every refusal stands whether the task runs or not: a task that stops before its launch
     * looks would otherwise start with parts or at a place it was refused. A running task asked for
     * what it may have answers as it does without them.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param forges This machine's forges, adapters and vault, for a pull request's place.
     * @param starts Told of a start at a pull request once its checks passed.
     * @return The parts and place.
     * @throws VarlinkException {@code InvalidParameter}, {@code StartRuleBroken}, {@code
     *     PlaceUnreadable}, {@code UnknownForge}, {@code NoPullRequest} or {@code NotTheTasksPush}.
     */
    static StartParts taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final RunPlaceCheck.Forges forges,
            final RunPlaceCheck.PullRequestStarts starts) {
        return taken(
                context,
                parameters,
                projectFile,
                task,
                repository,
                new RunPlaceCheck(context, forges, starts));
    }

    /**
     * Returns the parts and place a start names, a task's own pull request found as given.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param forges This machine's forges, adapters and vault, for a pull request's place.
     * @param own Finds a task's own pull request, open from its branch.
     * @param starts Told of a start at a pull request once its checks passed.
     * @return The parts and place.
     * @throws VarlinkException As the other forms.
     */
    static StartParts taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final RunPlaceCheck.Forges forges,
            final OwnPullRequests own,
            final RunPlaceCheck.PullRequestStarts starts) {
        return taken(
                context,
                parameters,
                projectFile,
                task,
                repository,
                new RunPlaceCheck(context, forges, own, starts));
    }

    private static StartParts taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final RunPlaceCheck check) {
        final TaskParts.Asked asked = asked(parameters);
        final RunPlaceCheck.Place place = place(parameters);
        final Once recorded = new Once(() -> recorded(context, projectFile, task));
        // An existing task is checked at the repository and branch it recorded, whatever the call
        // names (the launch refuses a mismatch), never at the ones its parameters would make for
        // a new task. A pull request's address uses neither but to find the task's own.
        final TaskBranch.@Nullable Recorded named =
                place != null && !(place instanceof RunPlaceCheck.Place.PullRequest)
                        ? recorded.get()
                        : null;
        final String placeRepository =
                named != null && named.repository() != null ? named.repository() : repository;
        try {
            return check.taken(
                    projectFile,
                    task,
                    placeRepository,
                    asked,
                    parameters.get("credentials") instanceof Map<?, ?> credentials
                            && !credentials.isEmpty(),
                    parameters.get("clearance") instanceof String clearance ? clearance : null,
                    place,
                    () -> {
                        final TaskBranch.@Nullable Recorded found = recorded.get();
                        return found != null && found.branch() != null
                                ? found.branch()
                                : branch(parameters, projectFile, task, placeRepository);
                    },
                    () -> {
                        final TaskBranch.@Nullable Recorded found = recorded.get();
                        // An existing task without parts that read is never taken for a new one.
                        return found == null
                                ? null
                                : found.parts() != null ? found.parts() : TaskParts.unrecorded();
                    });
        } catch (RunPlaceCheck.Refused refused) {
            throw new VarlinkException(refused.refusal().error(), refused.refusal().fields());
        }
    }

    /**
     * Returns what a Start or CanStart names of its task's parts, each member named or absent, with
     * its choice of workspace; a member the contract no longer has is refused by its name.
     *
     * @param parameters The call's parameters.
     * @return What it names.
     * @throws VarlinkException {@code InvalidParameter} naming a member of the wrong shape, or
     *     {@code role} or {@code reads}.
     */
    static TaskParts.Asked asked(final Map<String, Object> parameters) {
        for (final String gone : GONE) {
            if (parameters.containsKey(gone)) {
                throw invalid(gone);
            }
        }
        final Object writeAs = parameters.get("writeAs");
        if (writeAs != null && !"author".equals(writeAs) && !"review".equals(writeAs)) {
            throw invalid("writeAs");
        }
        final Object services = parameters.get("projectServices");
        if (services != null && !(services instanceof Boolean)) {
            throw invalid("projectServices");
        }
        return new TaskParts.Asked(
                rights(parameters.get("rights")),
                readLanded(parameters.get("readLanded")),
                writeAs instanceof String word ? word : null,
                services instanceof Boolean named ? named : null,
                Boolean.TRUE.equals(parameters.get("noGate")),
                Boolean.TRUE.equals(parameters.get("readOnly")),
                Boolean.TRUE.equals(parameters.get("allRepositories")));
    }

    private static @Nullable Set<Right> rights(final @Nullable Object given) {
        if (given == null) {
            return null;
        }
        if (!(given instanceof List<?> named)) {
            throw invalid("rights");
        }
        final Set<Right> rights = EnumSet.noneOf(Right.class);
        for (final Object each : named) {
            final Optional<Right> right =
                    each instanceof String word ? Right.named(word) : Optional.empty();
            rights.add(right.orElseThrow(() -> invalid("rights")));
        }
        return rights;
    }

    private static @Nullable List<String> readLanded(final @Nullable Object given) {
        if (given == null) {
            return null;
        }
        if (given instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            final List<String> names = list.stream().map(String.class::cast).toList();
            if (TaskLaunch.unreadable(names) == null) {
                return names;
            }
        }
        throw invalid("readLanded");
    }

    /** Looks an existing task up once, and only when asked: it lists the runtime's containers. */
    private static final class Once implements Supplier<TaskBranch.@Nullable Recorded> {

        private final Supplier<TaskBranch.@Nullable Recorded> lookup;

        private boolean asked;

        private TaskBranch.@Nullable Recorded found;

        Once(final Supplier<TaskBranch.@Nullable Recorded> lookup) {
            this.lookup = lookup;
        }

        @Override
        public TaskBranch.@Nullable Recorded get() {
            if (!asked) {
                found = lookup.get();
                asked = true;
            }
            return found;
        }
    }

    /** What an existing task recorded; a lookup that cannot be read refuses the start. */
    private static TaskBranch.@Nullable Recorded recorded(
            final PoreloContext context, final Path projectFile, final String task) {
        try {
            return TaskBranch.recorded(context, projectFile, task);
        } catch (TaskBranch.Unreadable unreadable) {
            final RunPlaceCheck.Refusal refusal =
                    RunPlaceCheck.unreadable(
                            org.fuin.porelo.gate.review.PlaceUnreadable.Reason.UNREACHABLE,
                            String.valueOf(unreadable.getMessage()));
            throw new VarlinkException(refusal.error(), refusal.fields());
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
