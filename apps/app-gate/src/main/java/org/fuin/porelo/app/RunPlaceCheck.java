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
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.jspecify.annotations.Nullable;

/**
 * Checks the parts and place a start names against the task it starts, before anything is made.
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
         * @param place Its recorded parts and place.
         * @param ref The pull request.
         * @throws Refused When the start refuses it, such as {@code NoSuchPullRequest}.
         */
        void checked(String task, RunPlace place, PullRequestRef ref);
    }

    /**
     * Returns the parts and place a start names, checked against the task it starts.
     *
     * <p>The place alone decides between the task's own work and another task's: a pull request is
     * the task's own exactly when it is the one open from the task's own branch at its forge, and
     * every other pull request is another task's work, whatever the caller says; for a task with a
     * gate, a gate push other than its own is another task's work too, taken only where it waits at
     * the gate of the task's own project. Porelo's own start rules are held against the parts that
     * follow, before the forge of the place is asked and before anything is made.
     *
     * <p>Every refusal stands whether the task runs or not: a task that stops before its launch
     * looks would otherwise start with parts or at a place it was refused.
     *
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @param repository The task's repository, or {@code null} when the start names none.
     * @param asked What the start names of the parts, and its choice of workspace.
     * @param addedCredentials Whether the start names credentials beyond the project's.
     * @param clearance What the start asks to happen with a blocked connection, or {@code null}.
     * @param place The place, or {@code null}.
     * @param branch The task's branch at the forge as its start settles it, or {@code null} when
     *     the start refuses it; asked only at a pull request.
     * @param recorded The parts an existing task of that name recorded, or {@code null} for a new
     *     task; asked only at another task's work for a start that names no rights.
     * @return The parts and place.
     * @throws Refused As {@code InvalidParameter}, {@code StartRuleBroken}, {@code
     *     PlaceUnreadable}, {@code UnknownForge}, {@code NoPullRequest} or {@code NotTheTasksPush};
     *     another task's push read in another project as {@code PlaceUnreadable} ({@code
     *     NotInProject}), and a name nothing waits under as {@code PlaceUnreadable} ({@code
     *     NoSuchPush}).
     */
    public StartParts taken(
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final TaskParts.Asked asked,
            final boolean addedCredentials,
            final @Nullable String clearance,
            final @Nullable Place place,
            final Supplier<@Nullable String> branch,
            final Supplier<@Nullable TaskParts> recorded) {
        final boolean gated = asked.gated();
        if (asked.writeAs() != null
                && !(place instanceof Place.PullRequest || place instanceof Place.OwnPullRequest)) {
            throw new Refused(
                    Refusal.invalid(
                            "writeAs", "an account to write as is taken at a pull request"));
        }
        if (place instanceof Place.PullRequest(String url)) {
            final PullRequestRef ref = pullRequest(url, gated);
            // Of the task's own project, before any token is lent for it or anything is fetched.
            ofTheProject(projectFile, ref);
            final boolean anothers =
                    !ownPullRequestIs(projectFile, repository, ref, branch, recorded);
            final TaskParts parts = anothers ? anothersWork(asked, recorded) : asked.atOwnWork();
            keeps(parts, addedCredentials, clearance);
            final RunPlace at = RunPlace.atPullRequest(parts, task, url);
            forge(ref.url(), ref.forge());
            if (parts.writesAsOpener()) {
                author(projectFile, ref);
            }
            starts.checked(task, at, ref);
            return new StartParts(asked, parts, at);
        }
        if (gated && place instanceof Place.GatePush(String given, String name) && pushName(name)) {
            final @Nullable StartParts anothers =
                    atAnothersPush(
                            projectFile,
                            task,
                            repository,
                            asked,
                            given,
                            name,
                            recorded,
                            parts -> keeps(parts, addedCredentials, clearance));
            if (anothers != null) {
                return anothers;
            }
        }
        final TaskParts parts = gated ? asked.atOwnWork() : asked.withoutGate();
        keeps(parts, addedCredentials, clearance);
        if (place == null) {
            return new StartParts(asked, parts, null);
        }
        if (place instanceof Place.OwnPullRequest) {
            return new StartParts(
                    asked,
                    parts,
                    ownPullRequest(projectFile, task, repository, gated, parts, branch));
        }
        if (place instanceof Place.GatePush(String ignored, String name) && !pushName(name)) {
            throw new Refused(Refusal.invalid("place", "'" + name + "' is no gate push's name"));
        }
        final Project project;
        final RunPlace own;
        try {
            project = ProjectReader.read(projectFile);
            own = new RunPlace(parts, RunPlace.otherThanOwn(project.name(), repository), task);
        } catch (RuntimeException ex) {
            // A project, a name or a repository that does not read is refused by the launch, in its
            // own words, before anything is made.
            return new StartParts(asked, parts, null);
        }
        final Refusal refused = refusal(project, own, place, gated);
        if (refused != null) {
            throw new Refused(refused);
        }
        return new StartParts(asked, parts, gated ? own : null);
    }

    /**
     * The parts of a start at another task's work: a start that names no rights there has no
     * default, but a task brought back that recorded another task's work keeps its parts.
     */
    private static TaskParts anothersWork(
            final TaskParts.Asked asked, final Supplier<@Nullable TaskParts> recorded) {
        final Optional<TaskParts.Broken> unnamed = asked.namesNoRights();
        if (unnamed.isEmpty()) {
            return asked.atAnothersWork();
        }
        final @Nullable TaskParts kept = recorded.get();
        if (kept != null && kept.readsAnothersWork() && !asked.changes(kept)) {
            return kept;
        }
        throw new Refused(broken(unnamed.get()));
    }

    /** Refuses parts that break one of Porelo's own start rules. */
    private static void keeps(
            final TaskParts parts,
            final boolean addedCredentials,
            final @Nullable String clearance) {
        StartRules.broken(parts, addedCredentials, clearance)
                .ifPresent(
                        rule -> {
                            throw new Refused(broken(rule));
                        });
    }

    /**
     * Returns the refusal of a start that breaks one of Porelo's own rules, said as {@code <rule>
     * (<detail>)}.
     *
     * @param broken The rule and the part that breaks it.
     * @return The refusal, of what was asked rather than of what is unavailable here.
     */
    public static Refusal broken(final TaskParts.Broken broken) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rule", broken.rule());
        fields.put("detail", broken.detail());
        return new Refusal(
                TasksContract.INTERFACE + ".StartRuleBroken",
                fields,
                true,
                broken.rule() + " (" + broken.detail() + ")");
    }

    /**
     * Whether a pull request is the task's own work, decided from what can be shown and never from
     * a lookup that failed: it is when it is the one open from the task's own branch at its forge.
     * It is another task's when that lookup names another pull request, when the task is new and so
     * has no work of its own, when the task recorded another task's work, or when the pull request
     * is at another repository than the task's. A task that recorded its own work, whose branch has
     * no pull request open, is refused: which it is cannot be told. So is a start whose project
     * file does not read or whose branch cannot be settled, as {@code PlaceUnreadable}.
     */
    private boolean ownPullRequestIs(
            final Path projectFile,
            final @Nullable String repository,
            final PullRequestRef ref,
            final Supplier<@Nullable String> branch,
            final Supplier<@Nullable TaskParts> recorded) {
        final @Nullable String upstream;
        try {
            upstream = upstream(ProjectReader.read(projectFile), repository);
        } catch (RuntimeException ex) {
            // Never taken as its own work, whose defaults hold the project's services and push.
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "the project file or the task's repository does not read, so whether "
                                    + ref.url()
                                    + " is this task's own work cannot be told"));
        }
        if (upstream == null || !within(upstream, ref)) {
            return false;
        }
        final @Nullable String name = branch.get();
        if (name == null) {
            // Never taken as its own work without the branch it would be told by.
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "the task's branch cannot be settled, so whether "
                                    + ref.url()
                                    + " is this task's own work cannot be told"));
        }
        try {
            return openFrom(upstream, name).url().normalize().equals(ref.url().normalize());
        } catch (Refused refused) {
            if (!"NoPullRequest".equals(refused.refusal().name())) {
                throw refused;
            }
            final @Nullable TaskParts kept = recorded.get();
            if (kept == null || kept.readsAnothersWork()) {
                return false;
            }
            throw new Refused(
                    noPullRequest(
                            name,
                            "no pull request is open from "
                                    + name
                                    + ", so whether "
                                    + ref.url()
                                    + " is this task's own work cannot be told"));
        }
    }

    /**
     * Refuses a pull request at no repository of the task's project, before any token is lent for
     * it.
     */
    private static void ofTheProject(final Path projectFile, final PullRequestRef ref) {
        final Project project;
        try {
            project = ProjectReader.read(projectFile);
        } catch (RuntimeException ex) {
            // Refused by the launch, in its own words, before anything is made.
            return;
        }
        final boolean named =
                within(project.upstream(), ref)
                        || project.allRepositories().stream()
                                .anyMatch(each -> within(each.upstream(), ref));
        if (!named) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "the pull request is at no repository of project " + project.name()));
        }
    }

    /**
     * Whether a pull request is one of a repository's, as its upstream names it: at the same host,
     * its address under the repository's path.
     */
    static boolean within(final @Nullable String upstream, final PullRequestRef ref) {
        final @Nullable String host = GitCredentialNames.hostOf(upstream);
        if (upstream == null || host == null || !host.equalsIgnoreCase(ref.forge())) {
            return false;
        }
        final String trimmed = upstream.strip();
        final int scheme = trimmed.indexOf("://");
        final String rest;
        if (scheme >= 0) {
            final String after = trimmed.substring(scheme + "://".length());
            final int slash = after.indexOf('/');
            rest = slash < 0 ? "" : after.substring(slash);
        } else {
            rest = trimmed.substring(trimmed.indexOf(':') + 1);
        }
        String path = rest;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        final String at = ref.url().normalize().getPath();
        return !path.isEmpty()
                && at != null
                && at.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("/" + path.toLowerCase(java.util.Locale.ROOT) + "/");
    }

    private @Nullable Refusal refusal(
            final Project project, final RunPlace own, final Place place, final boolean gated) {
        if (place instanceof Place.GatePush(String given, String name)) {
            final String named = named(project, own.repository(), given);
            // A task without a gate has no push of its own: every push it names is another's.
            if (!gated || !ownPush(own, named, name)) {
                return Refusal.unavailable(
                        "NotTheTasksPush",
                        Map.of("head", head(project, named, name)),
                        "the gate push '" + name + "' is not this task's own");
            }
        }
        return null;
    }

    /**
     * The parts and place of a start at another task's push, or {@code null} when the push named is
     * the task's own: another task's work, with no default rights, taken only where the push waits
     * at the gate of the task's own project, its parts held to Porelo's own rules first.
     */
    private @Nullable StartParts atAnothersPush(
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final TaskParts.Asked asked,
            final @Nullable String given,
            final String name,
            final Supplier<@Nullable TaskParts> recorded,
            final java.util.function.Consumer<TaskParts> rules) {
        final Project project;
        final @Nullable String ownRepository;
        try {
            project = ProjectReader.read(projectFile);
            ownRepository = RunPlace.otherThanOwn(project.name(), repository);
        } catch (RuntimeException ex) {
            if (name.equals(task)) {
                // Its own push, by its name: the launch refuses what does not read, in its words.
                return null;
            }
            // Another task's push is read only where its project is known to wait for it.
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "the project file or the task's repository does not read, so no"
                                    + " waiting push of it is known"));
        }
        final @Nullable String pushed = named(project, ownRepository, given);
        if (name.equals(task) && Objects.equals(pushed, ownRepository)) {
            return null;
        }
        final TaskParts parts = anothersWork(asked, recorded);
        // Porelo's own rules before the gate is asked what waits there.
        rules.accept(parts);
        return new StartParts(asked, parts, anothersPush(project, parts, pushed, name));
    }

    /** The repository a gate push names, by its key or its full name: null for the project's. */
    private static @Nullable String named(
            final Project project,
            final @Nullable String ownRepository,
            final @Nullable String given) {
        return given != null
                // By its key or its full name, as the task's own is read.
                ? RunPlace.otherThanOwn(project.name(), key(project, given))
                : ownRepository;
    }

    /** Whether a gate push is the task's own: its name and its repository. */
    private static boolean ownPush(
            final RunPlace own, final @Nullable String repository, final String name) {
        return name.equals(own.name()) && Objects.equals(repository, own.repository());
    }

    /**
     * Another task's push as a task's place, taken only where the push waits at the gate of the
     * task's own project: a repository of another project, or a name nothing waits under, is
     * refused before anything is made.
     */
    private RunPlace anothersPush(
            final Project project,
            final TaskParts parts,
            final @Nullable String repository,
            final String name) {
        if (!ofTheProject(project, repository)) {
            // The name given is not repeated: it is none of the project's.
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "the repository named is none of project "
                                    + project.name()
                                    + "'s; a task reads a waiting push of its own project only"));
        }
        if (RunPlace.waitingHead(context, project.name(), repository, name).isEmpty()
                || !recordedPusher(project, name)) {
            throw new Refused(
                    unreadable(
                            PlaceUnreadable.Reason.NO_SUCH_PUSH,
                            "nothing waits at the gate of project "
                                    + project.name()
                                    + " under '"
                                    + name
                                    + "'"));
        }
        return new RunPlace(parts, repository, name);
    }

    /**
     * Whether a task is recorded for the push, as the run's place asks before it pins: a push no
     * task is known for is refused here, before anything is made, rather than at the run's begin.
     */
    private boolean recordedPusher(final Project project, final String name) {
        try {
            CommentIntake.recorded(context, project.name(), name);
            return true;
        } catch (org.fuin.porelo.gate.review.ReviewRefused ex) {
            return false;
        }
    }

    /** Whether a repository, as a gate push names it, is one of the project's. */
    private static boolean ofTheProject(final Project project, final @Nullable String repository) {
        return repository == null
                || project.allRepositories().stream()
                        .map(Repository::name)
                        .anyMatch(repository::equals);
    }

    /**
     * A task at the pull request open from its branch, asked of the forge each start, never kept.
     */
    private @Nullable RunPlace ownPullRequest(
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final TaskParts parts,
            final Supplier<@Nullable String> branch) {
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
        final PullRequestRef ref = openFrom(upstream, name);
        final RunPlace at = RunPlace.atPullRequest(parts, task, ref.url().toString());
        if (parts.writesAsOpener()) {
            author(projectFile, ref);
        }
        starts.checked(task, at, ref);
        return at;
    }

    /**
     * Returns the pull request open from a task's branch at its forge, asked of the forge's adapter
     * with the forge's read credential lent for the call alone: the one lookup a start at the own
     * pull request and a review open of it share.
     *
     * @param upstream The repository the branch is pushed to, as the project file names it, or null
     *     when the task's repository names none.
     * @param branch The task's branch at the forge.
     * @return The pull request.
     * @throws Refused As {@code NoPullRequest} when none is open, {@code UnknownForge} or {@code
     *     PlaceUnreadable} as the forge and its adapter answer.
     */
    public PullRequestRef openFrom(final @Nullable String upstream, final String branch) {
        final @Nullable String host = upstream == null ? null : GitCredentialNames.hostOf(upstream);
        if (upstream == null || host == null) {
            throw new Refused(
                    noPullRequest(
                            branch,
                            "no pull request is open from "
                                    + branch
                                    + ": the task's repository names no upstream at a forge"));
        }
        final ForgeInstance instance = forge(URI.create("https://" + host + "/"), host);
        try {
            return own.of(instance, upstream, branch);
        } catch (PlaceUnreadable ex) {
            throw new Refused(unreadable(ex.reason(), String.valueOf(ex.getMessage())));
        } catch (OwnPullRequests.NoneOpen ex) {
            throw new Refused(noPullRequest(branch, "no pull request is open from " + branch));
        }
    }

    /** The repository the task's branch is pushed to, as the builds of its push are read. */
    static @Nullable String upstream(final Project project, final @Nullable String repository) {
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
            return new RunPlace(TaskParts.ownWork(), null, name).name().equals(name);
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
        final String entry = RecordedTaskRights.authorEntry(ref);
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

    /**
     * A place that could not be read.
     *
     * @param reason Why.
     * @param detail A short cause for a person; never a secret.
     * @return The refusal.
     */
    public static Refusal unreadable(final PlaceUnreadable.Reason reason, final String detail) {
        return Refusal.unavailable(
                "PlaceUnreadable",
                Map.of("reason", reason.wireName(), "detail", detail),
                reason.wireName() + ", " + detail);
    }

    /** The head waiting at a push of this project, or {@code ""}; never looked for elsewhere. */
    private String head(
            final Project project, final @Nullable String repository, final String name) {
        return ofTheProject(project, repository)
                ? RunPlace.waitingHead(context, project.name(), repository, name)
                : "";
    }
}
