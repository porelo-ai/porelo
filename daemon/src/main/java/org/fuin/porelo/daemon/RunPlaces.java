package org.fuin.porelo.daemon;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.app.ForgeAdapters;
import org.fuin.porelo.app.ForgeInstanceFile;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestRef;
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
     * What a start at a pull request asks of this machine before anything is made: the forge its
     * host names, whether that forge's adapter is installed, and whether the vault holds its token.
     *
     * <p>A stand-in answers fixed forges, adapters and entries.
     */
    interface Forges {

        /**
         * Finds the forge instance a pull request's host names.
         *
         * @param url The pull request's address.
         * @return The instance, or empty when this machine knows no forge of that host.
         * @throws ForgeFileUnreadable When the machine's forge file cannot be read.
         */
        Optional<ForgeInstance> of(URI url) throws ForgeFileUnreadable;

        /**
         * Whether a forge adapter of that name is installed.
         *
         * @param adapter Its name.
         * @return True when it is.
         */
        boolean installed(String adapter);

        /**
         * Whether the vault holds an entry, asked of its names alone.
         *
         * @param entry The entry's name.
         * @return Whether it holds it, or empty when the vault cannot tell, being shut.
         */
        Optional<Boolean> holds(String entry);

        /**
         * Returns this machine's forges, adapters and vault.
         *
         * @param context The machine.
         * @return Them.
         */
        static Forges of(final PoreloContext context) {
            final ForgeInstanceFile file = ForgeInstanceFile.of(context);
            final ForgeAdapters adapters = ForgeAdapters.of(context);
            return new Forges() {
                @Override
                public Optional<ForgeInstance> of(final URI url) throws ForgeFileUnreadable {
                    return file.of(url);
                }

                @Override
                public boolean installed(final String adapter) {
                    return adapters.installed(adapter);
                }

                @Override
                public Optional<Boolean> holds(final String entry) {
                    return context.readableCredentials().map(held -> held.containsKey(entry));
                }
            };
        }
    }

    /**
     * Told of a start at a pull request once every check here passed, before the task is made: the
     * run's start, which pins and reads the pull request, is plugged in here.
     */
    @FunctionalInterface
    interface PullRequestStarts {

        /** Told nothing: no run's start is plugged in yet. */
        PullRequestStarts NONE = (task, place, ref) -> {};

        /**
         * Hears of a start at a pull request.
         *
         * @param task The task's name within the project.
         * @param place Its recorded role and place.
         * @param ref The pull request.
         * @throws VarlinkException When the start refuses it, such as {@code NoSuchPullRequest}.
         */
        void checked(String task, RunPlace place, PullRequestRef ref);
    }

    /**
     * Returns the role and place a start names, with this machine's forges and no run's start told.
     *
     * @param context The machine.
     * @param parameters The call's parameters.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate.
     * @return The role and place, or {@code null}.
     * @throws VarlinkException As {@link #taken(PoreloContext, Map, Path, String, String, boolean,
     *     Forges, PullRequestStarts)}.
     */
    static @Nullable RunPlace taken(
            final PoreloContext context,
            final Map<String, Object> parameters,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated) {
        return taken(
                context,
                parameters,
                projectFile,
                task,
                repository,
                gated,
                Forges.of(context),
                PullRequestStarts.NONE);
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
            final Forges forges,
            final PullRequestStarts starts) {
        final String role = role(parameters);
        final Map<?, ?> place = place(parameters);
        if (role == null && place == null) {
            return null;
        }
        if (place != null && place.get("pullRequest") instanceof String url) {
            final PullRequestRef ref = pullRequest(url, gated);
            final RunPlace at =
                    RunPlace.atPullRequest(role == null ? RunPlace.AUTHOR : role, task, url);
            checkForge(ref, forges);
            starts.checked(task, at, ref);
            return at;
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
                            "the task's own pull request is not read yet; name it by its address,"
                                    + " the task's own gate push, or no place"));
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

    /** A pull request's address of the one shape the contract takes, for a task with a gate. */
    private static PullRequestRef pullRequest(final String url, final boolean gated) {
        final VarlinkException invalid =
                new VarlinkException(INVALID, Map.of("parameter", "place"));
        if (!gated) {
            // Its run is pinned and served through the task's gate, which this task has none of.
            throw invalid;
        }
        try {
            return PullRequestRef.of(new URI(url));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw invalid;
        }
    }

    /** Refuses a forge this machine does not know, cannot read, read with or hold a token for. */
    private static void checkForge(final PullRequestRef ref, final Forges forges) {
        final Optional<ForgeInstance> instance;
        try {
            instance = forges.of(ref.url());
        } catch (ForgeFileUnreadable ex) {
            throw unreadable(
                    PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                    "this machine's forge file cannot be read: " + ex.getMessage());
        }
        if (instance.isEmpty()) {
            throw new VarlinkException(
                    PoreloDaemon.INTERFACE + ".UnknownForge", Map.of("host", ref.forge()));
        }
        final String adapter = instance.get().adapter();
        if (adapter == null || !forges.installed(adapter)) {
            throw unreadable(
                    PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING,
                    adapter == null
                            ? "this machine's entry of "
                                    + ref.forge()
                                    + " names no adapter it reads"
                            : "the forge adapter " + adapter + " is not installed");
        }
        final String entry = instance.get().credential();
        final Optional<Boolean> held = forges.holds(entry);
        if (held.isEmpty() || !held.get()) {
            throw unreadable(
                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                    held.isEmpty()
                            ? "the vault is shut, so whether it holds '" + entry + "' is not known"
                            : "the vault holds no '" + entry + "'");
        }
    }

    private static VarlinkException unreadable(
            final PlaceUnreadable.Reason reason, final String detail) {
        return new VarlinkException(
                PoreloDaemon.INTERFACE + ".PlaceUnreadable",
                Map.of("reason", reason.wireName(), "detail", detail));
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
