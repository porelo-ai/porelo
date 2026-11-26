package org.fuin.porelo.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.jspecify.annotations.Nullable;

/**
 * Checks the role and place a start names against the task it starts, before anything is made.
 *
 * <p>One check for the daemon's Start and CanStart and for {@code porelo task start}, so each
 * refuses the same starts by the same names.
 */
public final class RunPlaceCheck {

    /** The name a parameter of the wrong shape is refused by. */
    public static final String INVALID_PARAMETER = "org.varlink.service.InvalidParameter";

    private final PoreloContext context;

    private final Forges forges;

    private final OwnPullRequests own;

    private final PullRequestStarts starts;

    /**
     * Constructor with this machine's way to an author's own pull request.
     *
     * @param context The machine.
     * @param forges Its forges, adapters and vault, for a pull request's place.
     * @param starts Told of a start at a pull request once its checks passed.
     */
    public RunPlaceCheck(
            final PoreloContext context, final Forges forges, final PullRequestStarts starts) {
        this(context, forges, OwnPullRequests.of(context), starts);
    }

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param forges Its forges, adapters and vault, for a pull request's place.
     * @param own Finds an author's own pull request, open from its branch.
     * @param starts Told of a start at a pull request once its checks passed.
     */
    public RunPlaceCheck(
            final PoreloContext context,
            final Forges forges,
            final OwnPullRequests own,
            final PullRequestStarts starts) {
        this.context = Objects.requireNonNull(context, "context");
        this.forges = Objects.requireNonNull(forges, "forges");
        this.own = Objects.requireNonNull(own, "own");
        this.starts = Objects.requireNonNull(starts, "starts");
    }

    /**
     * Returns the check every start asks: this machine's forges, and the run's start told of a
     * start at a pull request.
     *
     * @param context The machine.
     * @return The check.
     */
    public static RunPlaceCheck of(final PoreloContext context) {
        return new RunPlaceCheck(
                context, Forges.of(context), OwnPullRequests.of(context), PullRequestStarts.NONE);
    }

    /** Where a start names its task's run. */
    public sealed interface Place {

        /**
         * A gate push.
         *
         * @param repository Its repository by key or full name, or {@code null} for the task's own.
         * @param name The push's name.
         */
        record GatePush(@Nullable String repository, String name) implements Place {

            /** Constructor. */
            public GatePush {
                Objects.requireNonNull(name, "name");
            }
        }

        /**
         * A pull request.
         *
         * @param url Its address, as given.
         */
        record PullRequest(String url) implements Place {

            /** Constructor. */
            public PullRequest {
                Objects.requireNonNull(url, "url");
            }
        }

        /** The pull request that comes from the task's own branch. */
        record OwnPullRequest() implements Place {}
    }

    /**
     * A start refused, by the name its contract gives the refusal.
     *
     * @param error The refusal's full name, such as {@code org.fuin.porelo.Tasks1.UnknownForge}.
     * @param fields Its fields, as the contract names them.
     * @param typed Whether what was asked has the wrong shape, rather than being unavailable here.
     * @param said What a person is told.
     */
    public record Refusal(String error, Map<String, Object> fields, boolean typed, String said) {

        /** Constructor. */
        public Refusal {
            Objects.requireNonNull(error, "error");
            fields = Map.copyOf(fields);
            Objects.requireNonNull(said, "said");
        }

        /**
         * Returns the refusal's own name, without its interface.
         *
         * @return Such as {@code UnknownForge}.
         */
        public String name() {
            return error.substring(error.lastIndexOf('.') + 1);
        }

        /**
         * A parameter of the wrong shape.
         *
         * @param parameter The parameter's name.
         * @param said What a person is told.
         * @return The refusal.
         */
        public static Refusal invalid(final String parameter, final String said) {
            return new Refusal(INVALID_PARAMETER, Map.of("parameter", parameter), true, said);
        }

        /**
         * A refusal of the task's contract for what is not available here.
         *
         * @param name The refusal's own name, such as {@code NoSuchPullRequest}.
         * @param fields Its fields.
         * @param said What a person is told.
         * @return The refusal.
         */
        public static Refusal unavailable(
                final String name, final Map<String, Object> fields, final String said) {
            return new Refusal(TasksContract.INTERFACE + "." + name, fields, false, said);
        }
    }

    /** Thrown with the refusal of a start. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Not serialized: a refusal is answered where it is thrown. */
        private final transient Refusal refusal;

        /**
         * Constructor.
         *
         * @param refusal The refusal.
         */
        public Refused(final Refusal refusal) {
            super(refusal.said(), null, false, false);
            this.refusal = refusal;
        }

        /**
         * Returns the refusal.
         *
         * @return It.
         */
        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * What a start at a pull request asks of this machine before anything is made: the forge its
     * host names, whether that forge's adapter is installed, and whether the vault holds its token.
     *
     * <p>A stand-in answers fixed forges, adapters and entries.
     */
    public interface Forges {

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
    public interface PullRequestStarts {

        /** Told nothing: no run's start is plugged in yet. */
        PullRequestStarts NONE = (task, place, ref) -> {};

        /**
         * Hears of a start at a pull request.
         *
         * @param task The task's name within the project.
         * @param place Its recorded role and place.
         * @param ref The pull request.
         * @throws Refused When the start refuses it, such as {@code NoSuchPullRequest}.
         */
        void checked(String task, RunPlace place, PullRequestRef ref);
    }

    /**
     * Returns the role and place a start names, checked against the task it starts.
     *
     * <p>Every refusal stands whether the task runs or not: a task that stops before its launch
     * looks would otherwise start in a role or at a place it was refused.
     *
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param gated Whether the task works through a gate: not with noGate, readOnly or
     *     allRepositories.
     * @param role {@link RunPlace#AUTHOR}, {@link RunPlace#REVIEWER} or {@code null}.
     * @param place The place, or {@code null}.
     * @param branch The task's branch at the forge as its start settles it, or {@code null} when
     *     the start refuses it; asked only for the task's own pull request.
     * @return The role and place, or {@code null} when the start names neither, or when its task
     *     has no gate and names only a role.
     * @throws Refused As {@code InvalidParameter}, {@code PlaceUnreadable}, {@code UnknownForge},
     *     {@code NoPullRequest}, {@code NotTheTasksPush} or {@code RoleNotAllowed}.
     */
    public @Nullable RunPlace taken(
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final @Nullable String role,
            final @Nullable Place place,
            final Supplier<@Nullable String> branch) {
        if (role == null && place == null) {
            return null;
        }
        if (role != null && !RunPlace.AUTHOR.equals(role) && !RunPlace.REVIEWER.equals(role)) {
            throw new Refused(Refusal.invalid("role", "a run's role is author or reviewer"));
        }
        if (place instanceof Place.PullRequest(String url)) {
            final PullRequestRef ref = pullRequest(url, gated);
            final RunPlace at =
                    RunPlace.atPullRequest(role == null ? RunPlace.AUTHOR : role, task, url);
            forge(ref.url(), ref.forge());
            if (!RunPlace.REVIEWER.equals(role)) {
                author(projectFile, ref);
            }
            starts.checked(task, at, ref);
            return at;
        }
        if (place instanceof Place.OwnPullRequest) {
            return ownPullRequest(projectFile, task, repository, gated, role, branch);
        }
        if (place instanceof Place.GatePush(String ignored, String name) && !pushName(name)) {
            throw new Refused(Refusal.invalid("place", "'" + name + "' is no gate push's name"));
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
        final Refusal refused = refusal(project, own, role, place, gated);
        if (refused != null) {
            throw new Refused(refused);
        }
        return gated ? own : null;
    }

    private @Nullable Refusal refusal(
            final Project project,
            final RunPlace own,
            final @Nullable String role,
            final @Nullable Place place,
            final boolean gated) {
        if (place instanceof Place.GatePush(String given, String name)) {
            final String named =
                    given != null
                            // By its key or its full name, as the task's own is read.
                            ? RunPlace.otherThanOwn(project.name(), key(project, given))
                            : own.repository();
            // A task without a gate has no push of its own: every push it names is another's.
            if (!gated || !name.equals(own.name()) || !Objects.equals(named, own.repository())) {
                return Refusal.unavailable(
                        "NotTheTasksPush",
                        Map.of("head", head(project, named, name)),
                        "the gate push '" + name + "' is not this task's own");
            }
        }
        if (gated && RunPlace.REVIEWER.equals(role)) {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("role", RunPlace.REVIEWER);
            fields.put("place", own.place());
            return new Refusal(
                    TasksContract.INTERFACE + ".RoleNotAllowed",
                    fields,
                    true,
                    "a reviewer does not review its own task's gate push");
        }
        return null;
    }

    /**
     * An author at the pull request open from its branch, asked of the forge each start and never
     * kept; a reviewer of its own work is refused before the forge is asked.
     */
    private @Nullable RunPlace ownPullRequest(
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final @Nullable String role,
            final Supplier<@Nullable String> branch) {
        if (RunPlace.REVIEWER.equals(role)) {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("role", RunPlace.REVIEWER);
            fields.put("place", Map.of("ownPullRequest", true));
            throw new Refused(
                    new Refusal(
                            TasksContract.INTERFACE + ".RoleNotAllowed",
                            fields,
                            true,
                            "a reviewer does not review its own task's pull request"));
        }
        if (!gated) {
            throw new Refused(
                    Refusal.invalid(
                            "place", "a task without a gate has no pull request of its own"));
        }
        final @Nullable String upstream;
        try {
            upstream = upstream(ProjectReader.read(projectFile), repository);
        } catch (RuntimeException ex) {
            // Refused by the launch, in its own words, before anything is made.
            return null;
        }
        final @Nullable String name = branch.get();
        if (name == null) {
            // A branch the start refuses is refused by the launch, in its own words.
            return null;
        }
        final @Nullable String host = upstream == null ? null : GitCredentialNames.hostOf(upstream);
        if (upstream == null || host == null) {
            throw new Refused(
                    noPullRequest(
                            name,
                            "no pull request is open from "
                                    + name
                                    + ": the task's repository names no upstream at a forge"));
        }
        final ForgeInstance instance = forge(URI.create("https://" + host + "/"), host);
        final PullRequestRef ref;
        try {
            ref = own.of(instance, upstream, name);
        } catch (PlaceUnreadable ex) {
            throw new Refused(unreadable(ex.reason(), String.valueOf(ex.getMessage())));
        } catch (OwnPullRequests.NoneOpen ex) {
            throw new Refused(noPullRequest(name, "no pull request is open from " + name));
        }
        final RunPlace at = RunPlace.atPullRequest(RunPlace.AUTHOR, task, ref.url().toString());
        author(projectFile, ref);
        starts.checked(task, at, ref);
        return at;
    }

    /** The repository the task's branch is pushed to, as the builds of its push are read. */
    private static @Nullable String upstream(
            final Project project, final @Nullable String repository) {
        final String own = project.upstream();
        if (repository == null) {
            return own;
        }
        final String named = GateSupport.repository(project, repository).upstream();
        return named != null ? named : own;
    }

    private static Refusal noPullRequest(final String branch, final String said) {
        return Refusal.unavailable("NoPullRequest", Map.of("branch", branch), said);
    }

    /** Whether a gate push may carry the name, as its record takes one. */
    private static boolean pushName(final String name) {
        try {
            return new RunPlace(RunPlace.AUTHOR, null, name).name().equals(name);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** A repository named by its key or its full name, as the project names it. */
    private static String key(final Project project, final String repository) {
        if (repository.isEmpty()) {
            return repository;
        }
        try {
            return GateSupport.repository(project, repository).name();
        } catch (RuntimeException ex) {
            return repository;
        }
    }

    /** A pull request's address of the one shape the contract takes, for a task with a gate. */
    private static PullRequestRef pullRequest(final String url, final boolean gated) {
        if (!gated) {
            // Its run is pinned and served through the task's gate, which this task has none of.
            throw new Refused(
                    Refusal.invalid(
                            "place", "a task without a gate is started at no pull request"));
        }
        try {
            return PullRequestRef.of(new URI(url));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw new Refused(
                    Refusal.invalid(
                            "place",
                            "a pull request is named by its https address, without user, query"
                                    + " or fragment"));
        }
    }

    /**
     * Returns the forge at a host, refusing one this machine does not know, cannot read, read with
     * or hold a token for.
     */
    private ForgeInstance forge(final URI url, final String host) {
        final Optional<ForgeInstance> instance;
        try {
            instance = forges.of(url);
        } catch (ForgeFileUnreadable ex) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                            "this machine's forge file cannot be read: " + ex.getMessage()));
        }
        if (instance.isEmpty()) {
            throw new Refused(
                    Refusal.unavailable(
                            "UnknownForge",
                            Map.of("host", host),
                            "this machine knows no forge at "
                                    + host
                                    + "; 'porelo forge add "
                                    + host
                                    + " --adapter <adapter>' adds it"));
        }
        final String adapter = instance.get().adapter();
        if (adapter == null || !forges.installed(adapter)) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING,
                            adapter == null
                                    ? "this machine's entry of "
                                            + host
                                            + " names no adapter it reads"
                                    : "the forge adapter " + adapter + " is not installed"));
        }
        final String entry = instance.get().credential();
        final Optional<Boolean> held = forges.holds(entry);
        if (held.isEmpty() || !held.get()) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                            held.isEmpty()
                                    ? "the vault is shut, so whether it holds '"
                                            + entry
                                            + "' is not known"
                                    : "the vault holds no '" + entry + "'"));
        }
        return instance.get();
    }

    /**
     * Refuses an author at a pull request that has no account of its own to write its items as: off
     * a host, with a project that names no builds credential, the vault must hold the entry {@code
     * git.author.<forge host>}. The project's builds and a host's task account are the review's to
     * check when the items go out, as before.
     */
    private void author(final Path projectFile, final PullRequestRef ref) {
        if (Files.isRegularFile(context.paths().hostProgram()) || builds(projectFile)) {
            return;
        }
        final String entry = TaskReviewRole.authorEntry(ref);
        final Optional<Boolean> held = forges.holds(entry);
        if (held.isEmpty() || !held.get()) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                            (held.isEmpty()
                                            ? "the vault is shut, so whether it holds '"
                                                    + entry
                                                    + "' is not known"
                                            : "the vault holds no '" + entry + "'")
                                    + ", the token of the account that opened the pull request,"
                                    + " which an author writes its items as; store it with"
                                    + " 'porelo vault put "
                                    + entry
                                    + "'"));
        }
    }

    /** Whether the project names a builds credential of its own; one that does not read, none. */
    private static boolean builds(final Path projectFile) {
        try {
            return ProjectReader.read(projectFile).builds() != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Refusal unreadable(final PlaceUnreadable.Reason reason, final String detail) {
        return Refusal.unavailable(
                "PlaceUnreadable",
                Map.of("reason", reason.wireName(), "detail", detail),
                reason.wireName() + ", " + detail);
    }

    /** The head waiting at a push of this project, or {@code ""}; never looked for elsewhere. */
    private String head(
            final Project project, final @Nullable String repository, final String name) {
        final boolean ofTheProject =
                repository == null
                        || project.allRepositories().stream()
                                .map(Repository::name)
                                .anyMatch(repository::equals);
        return ofTheProject ? RunPlace.waitingHead(context, project.name(), repository, name) : "";
    }
}
