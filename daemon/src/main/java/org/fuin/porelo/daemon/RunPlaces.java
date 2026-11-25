package org.fuin.porelo.daemon;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Takes the role and place a Start or CanStart names, and refuses one its task cannot work at,
 * before anything is made.
 */
final class RunPlaces {

    private static final String INVALID = "org.varlink.service.InvalidParameter";

    private RunPlaces() {}

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
     * @return The role and place, or {@code null} when the start names neither, or when its task
     *     has no gate and names only a role.
     * @throws VarlinkException {@code InvalidParameter}, {@code PlaceUnreadable}, {@code
     *     NotTheTasksPush} or {@code RoleNotAllowed}.
     */
    static @Nullable RunPlace taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated) {
        final String role = role(parameters);
        final Map<?, ?> place = place(parameters);
        if (role == null && place == null) {
            return null;
        }
        final Project project;
        final RunPlace own;
        try {
            project = ProjectReader.read(projectFile);
            own = RunPlace.ownPush(project.name(), repository, task);
        } catch (RuntimeException ex) {
            // A project, a name or a repository that does not read is refused by the launch, in its
            // own words, before anything is made.
            return null;
        }
        final VarlinkException refused =
                refusal(context, projectFile, project, own, role, place, gated);
        if (refused != null) {
            throw refused;
        }
        return gated ? own : null;
    }

    private static @Nullable VarlinkException refusal(
            final PoreloContext context,
            final Path projectFile,
            final Project project,
            final RunPlace own,
            final @Nullable String role,
            final @Nullable Map<?, ?> place,
            final boolean gated) {
        if (place != null && place.get("gatePush") == null) {
            return new VarlinkException(
                    PoreloDaemon.INTERFACE + ".PlaceUnreadable",
                    Map.of(
                            "reason",
                            "ForgeAdapterMissing",
                            "detail",
                            "a pull request's place is not read yet; name the task's own gate push,"
                                    + " or no place"));
        }
        if (place != null) {
            final Map<?, ?> push = (Map<?, ?>) Objects.requireNonNull(place.get("gatePush"));
            final String name = String.valueOf(push.get("name"));
            final String named =
                    push.get("repository") instanceof String given
                            // By its key or its full name, as the task's own is read.
                            ? RunPlace.otherThanOwn(
                                    project.name(), PoreloDaemon.key(projectFile, given))
                            : own.repository();
            // A task without a gate has no push of its own: every push it names is another's.
            if (!gated || !name.equals(own.name()) || !Objects.equals(named, own.repository())) {
                return new VarlinkException(
                        PoreloDaemon.INTERFACE + ".NotTheTasksPush",
                        Map.of("head", head(context, project, named, name)));
            }
        }
        if (gated && RunPlace.REVIEWER.equals(role)) {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("role", RunPlace.REVIEWER);
            fields.put("place", own.place());
            return new VarlinkException(PoreloDaemon.INTERFACE + ".RoleNotAllowed", fields);
        }
        return null;
    }

    /** The head waiting at a push of this project, or {@code ""}; never looked for elsewhere. */
    private static String head(
            final PoreloContext context,
            final Project project,
            final @Nullable String repository,
            final String name) {
        final boolean ofTheProject =
                repository == null
                        || project.allRepositories().stream()
                                .map(Repository::name)
                                .anyMatch(repository::equals);
        return ofTheProject ? RunPlace.waitingHead(context, project.name(), repository, name) : "";
    }

    private static @Nullable String role(final Map<String, Object> parameters) {
        final Object given = parameters.get("role");
        if (given == null) {
            return null;
        }
        if (RunPlace.AUTHOR.equals(given) || RunPlace.REVIEWER.equals(given)) {
            return (String) given;
        }
        throw new VarlinkException(INVALID, Map.of("parameter", "role"));
    }

    /** The place as given, with exactly one field set, or {@code null} when none was given. */
    private static @Nullable Map<?, ?> place(final Map<String, Object> parameters) {
        final Object given = parameters.get("place");
        if (given == null) {
            return null;
        }
        final VarlinkException invalid =
                new VarlinkException(INVALID, Map.of("parameter", "place"));
        if (!(given instanceof Map<?, ?> place)) {
            throw invalid;
        }
        final Object push = place.get("gatePush");
        final Object pullRequest = place.get("pullRequest");
        final Object ownPullRequest = place.get("ownPullRequest");
        final long set =
                java.util.stream.Stream.of(push, pullRequest, ownPullRequest)
                        .filter(Objects::nonNull)
                        .count();
        if (set != 1
                || (pullRequest != null && !(pullRequest instanceof String))
                || (ownPullRequest != null && !(ownPullRequest instanceof Boolean))) {
            throw invalid;
        }
        if (push != null
                && !(push instanceof Map<?, ?> fields
                        && fields.get("name") instanceof String
                        && (fields.get("repository") == null
                                || fields.get("repository") instanceof String))) {
            throw invalid;
        }
        return place;
    }
}
