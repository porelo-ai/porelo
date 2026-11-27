package org.fuin.porelo.gate.review;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The four parts a task's start recorded, fixed for the task's life: its workspace, its equipment,
 * its actions and its judgments. The one class that reads a part's value: every other part of
 * Porelo asks a question here and names no kind, account or right.
 *
 * @param workspace What the task sees and where it works.
 * @param readLanded The tasks whose landed work it also reads.
 * @param projectServices Whether it gets the project's credentials, package repositories, host
 *     services and egress, as one switch.
 * @param account The account its items are written as at a pull request.
 * @param rights What it may do at its place and through its endpoint, and its judgments.
 */
public record TaskParts(
        Workspace workspace,
        List<String> readLanded,
        boolean projectServices,
        Account account,
        Set<Right> rights) {

    /** What a task sees and where it works. */
    public enum Workspace {
        /** Its own branch and a gate that takes its pushes. */
        OWN("own"),
        /** Another task's push or pull request, frozen at its head and read only. */
        FOREIGN("foreign"),
        /** One repository read only, with no gate. */
        READ_ONLY("readOnly"),
        /** Every repository of the project read only, with no gate. */
        ALL_REPOSITORIES("allRepositories"),
        /** An empty directory and no gate. */
        NONE("none");

        private final String word;

        Workspace(final String word) {
            this.word = word;
        }
    }

    /** The account a task's items are written as at a pull request. */
    public enum Account {
        /** The account that opened the pull request. */
        AUTHOR("author"),
        /** The forge instance's account. */
        REVIEW("review");

        private final String word;

        Account(final String word) {
            this.word = word;
        }
    }

    /** Copies the lists, in a fixed order, so equal parts are equal records. */
    public TaskParts {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(account, "account");
        readLanded = List.copyOf(readLanded);
        rights =
                rights.isEmpty()
                        ? Set.of()
                        : java.util.Collections.unmodifiableSet(EnumSet.copyOf(rights));
    }

    /**
     * Returns what a start naming no part gets: its own work, the project's services, push and
     * reply, and the account that opened its pull request.
     *
     * @return The parts.
     */
    public static TaskParts ownWork() {
        return new TaskParts(
                Workspace.OWN, List.of(), true, Account.AUTHOR, Set.of(Right.PUSH, Right.REPLY));
    }

    /**
     * Returns the parts of a task that reads another task's work, frozen: none of the project's
     * services, the forge instance's account, and the rights named.
     *
     * @param rights Its rights; a start there has no default.
     * @return The parts.
     */
    public static TaskParts anothersWork(final Collection<Right> rights) {
        return new TaskParts(
                Workspace.FOREIGN, List.of(), false, Account.REVIEW, Set.copyOf(rights));
    }

    /**
     * Returns what a start without a gate gets when it names no part: the project's services and no
     * right, as it has no place to act at.
     *
     * @param allRepositories Whether it reads every repository of its project.
     * @param readOnly Whether it reads its one repository.
     * @return The parts: read only for one or all repositories, else no workspace.
     */
    public static TaskParts withoutGate(final boolean allRepositories, final boolean readOnly) {
        final Workspace kind =
                allRepositories
                        ? Workspace.ALL_REPOSITORIES
                        : readOnly ? Workspace.READ_ONLY : Workspace.NONE;
        return new TaskParts(kind, List.of(), true, Account.AUTHOR, Set.of());
    }

    /**
     * Returns what a task's row shows for a task whose start recorded no parts, as one made by an
     * older Porelo: no workspace, no service and no right, which is what it is given.
     *
     * @return The parts.
     */
    public static TaskParts unrecorded() {
        return new TaskParts(Workspace.NONE, List.of(), false, Account.AUTHOR, Set.of());
    }

    /**
     * Returns these parts reading other tasks' landed work.
     *
     * @param tasks The tasks.
     * @return A copy.
     */
    public TaskParts readingLanded(final List<String> tasks) {
        return new TaskParts(workspace, tasks, projectServices, account, rights);
    }

    /**
     * What a start names of its task's parts, each member as given or {@code null} where the start
     * names none, beside the workspace it chooses with {@code noGate}, {@code readOnly} or {@code
     * allRepositories}. Named and absent differ: an absent member takes its default for the
     * workspace, a named one is taken as it is, an empty list of rights too.
     *
     * @param rights The rights named, or {@code null} when the start names none.
     * @param readLanded The tasks whose landed work it reads, or {@code null} when it names none.
     * @param writeAs The account named, {@code author} or {@code review}, or {@code null}.
     * @param projectServices Whether it asks for the project's services, or {@code null}.
     * @param noGate Whether it asks for no workspace.
     * @param readOnly Whether it asks to read its one repository.
     * @param allRepositories Whether it asks to read every repository of its project.
     */
    public record Asked(
            @Nullable Set<Right> rights,
            @Nullable List<String> readLanded,
            @Nullable String writeAs,
            @Nullable Boolean projectServices,
            boolean noGate,
            boolean readOnly,
            boolean allRepositories) {

        /**
         * Copies the lists and refuses an account no start names.
         *
         * @throws IllegalArgumentException For a {@code writeAs} other than {@code author} or
         *     {@code review}.
         */
        public Asked {
            rights =
                    rights == null
                            ? null
                            : rights.isEmpty()
                                    ? Set.of()
                                    : java.util.Collections.unmodifiableSet(EnumSet.copyOf(rights));
            readLanded =
                    readLanded == null
                            ? null
                            : List.copyOf(new java.util.LinkedHashSet<>(readLanded));
            if (writeAs != null) {
                account(writeAs);
            }
        }

        /**
         * A start that names no part and chooses its own work.
         *
         * @return It.
         */
        public static Asked nothing() {
            return new Asked(null, null, null, null, false, false, false);
        }

        /**
         * Whether the task works through a gate: not with {@code noGate}, {@code readOnly} or
         * {@code allRepositories}.
         *
         * @return True for a start whose workspace may be its own work or another task's.
         */
        public boolean gated() {
            return !noGate && !readOnly && !allRepositories;
        }

        /**
         * Whether the start names any of the four parts.
         *
         * @return False for a start that names none.
         */
        public boolean namesAny() {
            return rights != null
                    || readLanded != null
                    || writeAs != null
                    || projectServices != null;
        }

        /**
         * Returns the parts of a start at its own work: absent rights are push and reply, absent
         * services are on, the account is the one that opened its pull request.
         *
         * @return The parts.
         */
        public TaskParts atOwnWork() {
            return parts(Workspace.OWN, Set.of(Right.PUSH, Right.REPLY), true, Account.AUTHOR);
        }

        /**
         * Returns the parts of a start at another task's work, for a start that names its rights:
         * services are off unless the start asks them on, which one of Porelo's own rules then
         * refuses, and its items are written as the account it names, the one that opened the pull
         * request when it names none: the account is the choice of an identity, never a right.
         *
         * @return The parts.
         * @throws IllegalStateException When the start names no rights: that start is refused
         *     ({@link #namesNoRights()}) and has no parts.
         */
        public TaskParts atAnothersWork() {
            if (rights == null) {
                throw new IllegalStateException("a start at another task's work names its rights");
            }
            return parts(Workspace.FOREIGN, Set.of(), false, Account.AUTHOR);
        }

        /**
         * Returns the rule a start at another task's work breaks when it names no rights: it has no
         * default.
         *
         * @return The broken rule, or empty when the start names its rights.
         */
        public Optional<Broken> namesNoRights() {
            return rights == null
                    ? Optional.of(new Broken("foreignWorkNamesNoRights", "rights"))
                    : Optional.empty();
        }

        /**
         * Returns the parts of a start without a gate: read only on one or every repository, or no
         * workspace. Absent rights are none, absent services are on.
         *
         * @return The parts.
         */
        public TaskParts withoutGate() {
            return parts(
                    allRepositories
                            ? Workspace.ALL_REPOSITORIES
                            : readOnly ? Workspace.READ_ONLY : Workspace.NONE,
                    Set.of(),
                    true,
                    Account.AUTHOR);
        }

        /**
         * Whether a start that brings a task back names other parts than the task recorded; a
         * member it does not name keeps the recorded one.
         *
         * @param recorded The parts the task recorded.
         * @return True when a named member differs from the recorded one.
         */
        public boolean changes(final TaskParts recorded) {
            return rights != null && !rights.equals(recorded.rights)
                    || readLanded != null && !readLanded.equals(recorded.readLanded)
                    || writeAs != null && account(writeAs) != recorded.account
                    || projectServices != null
                            && projectServices.booleanValue() != recorded.projectServices;
        }

        private TaskParts parts(
                final Workspace workspace,
                final Set<Right> byDefault,
                final boolean servicesByDefault,
                final Account accountByDefault) {
            return new TaskParts(
                    workspace,
                    readLanded == null ? List.of() : readLanded,
                    projectServices == null ? servicesByDefault : projectServices.booleanValue(),
                    writeAs == null ? accountByDefault : account(writeAs),
                    rights == null ? byDefault : rights);
        }
    }

    /**
     * Whether the task holds a right.
     *
     * @param right The right.
     * @return True when its start recorded it.
     */
    public boolean holds(final Right right) {
        return rights.contains(right);
    }

    /**
     * Whether the task's workspace is its own work: its branch and a gate that can take pushes.
     *
     * @return True for own work.
     */
    public boolean worksOnItsOwn() {
        return workspace == Workspace.OWN;
    }

    /**
     * Whether the task's workspace is another task's work, frozen at its head and read only: work
     * nobody has reviewed, so nothing that leads out goes with it.
     *
     * @return True for another task's work.
     */
    public boolean readsAnothersWork() {
        return workspace == Workspace.FOREIGN;
    }

    /**
     * Whether the task has a gate at all: its own, or one that serves the frozen work it reads.
     *
     * @return False for a task that only reads landed work or has no workspace.
     */
    public boolean gated() {
        return workspace == Workspace.OWN || workspace == Workspace.FOREIGN;
    }

    /**
     * Whether the task's items go out as the account that opened the pull request, rather than as
     * the forge instance's account.
     *
     * @return True for the opening account.
     */
    public boolean writesAsOpener() {
        return account == Account.AUTHOR;
    }

    /**
     * Returns the rule of Porelo's own these parts break, whatever a start asks.
     *
     * @return The broken rule with the part that breaks it, or empty when they break none.
     */
    public Optional<Broken> broken() {
        // A judgment beside push is named as such wherever it is asked, another task's work too.
        if (holds(Right.REJECT) && holds(Right.PUSH)) {
            return Optional.of(new Broken("rejectWithPush", Right.PUSH.word()));
        }
        if (readsAnothersWork() && projectServices) {
            return Optional.of(new Broken("foreignWorkLeadsOut", "projectServices"));
        }
        if (readsAnothersWork() && holds(Right.PUSH)) {
            return Optional.of(new Broken("foreignWorkLeadsOut", Right.PUSH.word()));
        }
        // Another task's waiting push is a reject's only place: own work, landed work, all
        // repositories and no workspace have none it could discard.
        if (holds(Right.REJECT) && !readsAnothersWork()) {
            return Optional.of(new Broken("rejectOverOwnWork", workspace.word));
        }
        if (holds(Right.PUSH) && !worksOnItsOwn()) {
            return Optional.of(new Broken("pushWithoutOwnWork", workspace.word));
        }
        return Optional.empty();
    }

    /**
     * One of Porelo's own start rules, broken.
     *
     * @param rule The rule's name, as the contract's {@code StartRuleBroken} gives it.
     * @param detail The part that breaks it.
     */
    public record Broken(String rule, String detail) {

        /** Constructor. */
        public Broken {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * Returns the parts as a task's record keeps them.
     *
     * @return The document.
     */
    public Map<String, Object> document() {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("workspace", workspace.word);
        document.put("readLanded", readLanded);
        document.put("projectServices", Boolean.valueOf(projectServices));
        document.put("writeAs", account.word);
        document.put("rights", rights.stream().map(Right::word).toList());
        return document;
    }

    /**
     * Reads the parts a task's record keeps.
     *
     * @param kept What the record holds under its parts.
     * @return The parts.
     * @throws IllegalArgumentException When it is no parts' document: a member is missing, of
     *     another shape, or a word no start records. Never read as any default.
     */
    public static TaskParts of(final Object kept) {
        if (!(kept instanceof Map<?, ?> document)
                || !(document.get("workspace") instanceof String kind)
                || !(document.get("readLanded") instanceof List<?> landed)
                || !(document.get("projectServices") instanceof Boolean services)
                || !(document.get("writeAs") instanceof String writeAs)
                || !(document.get("rights") instanceof List<?> held)) {
            throw new IllegalArgumentException("no record of a task's parts");
        }
        final List<String> tasks = new ArrayList<>();
        for (final Object each : landed) {
            if (!(each instanceof String task)) {
                throw new IllegalArgumentException("no task's name among the landed work read");
            }
            tasks.add(task);
        }
        final Set<Right> rights = EnumSet.noneOf(Right.class);
        for (final Object each : held) {
            rights.add(
                    Right.named(String.valueOf(each))
                            .orElseThrow(() -> new IllegalArgumentException("no right: " + each)));
        }
        return new TaskParts(
                workspace(kind), tasks, services.booleanValue(), account(writeAs), rights);
    }

    private static Workspace workspace(final String word) {
        for (final Workspace each : Workspace.values()) {
            if (each.word.equals(word)) {
                return each;
            }
        }
        throw new IllegalArgumentException("no workspace: " + word);
    }

    private static Account account(final String word) {
        for (final Account each : Account.values()) {
            if (each.word.equals(word)) {
                return each;
            }
        }
        throw new IllegalArgumentException("no account: " + word);
    }

    /** The rule a task's start breaks when it asks more than the task that starts it holds. */
    public static final String EXCEEDS_PARENT = "exceedsParent";

    /**
     * Returns the first part in which a task started by another gets more than that task holds: a
     * child sees, reaches and may do at most what its parent does.
     *
     * <p>Its workspace is its parent's, none, or, under a parent at its own work or one that reads
     * every repository, one that reads one repository; never another task's work under a parent
     * that does not read it, and never every repository under a parent that does not. The project's
     * services only where the parent has them; the parent's own account; its rights and the landed
     * work it reads each a part of the parent's.
     *
     * @param parent The parts of the task that starts it.
     * @return {@link #EXCEEDS_PARENT} with the part and its value, or empty when these parts are
     *     within the parent's.
     */
    public Optional<Broken> exceeds(final TaskParts parent) {
        if (!within(workspace, parent.workspace)) {
            return Optional.of(new Broken(EXCEEDS_PARENT, "workspace " + workspace.word));
        }
        if (projectServices && !parent.projectServices) {
            return Optional.of(new Broken(EXCEEDS_PARENT, "projectServices"));
        }
        if (account != parent.account) {
            return Optional.of(new Broken(EXCEEDS_PARENT, "writeAs " + account.word));
        }
        for (final Right right : rights) {
            if (!parent.rights.contains(right)) {
                return Optional.of(new Broken(EXCEEDS_PARENT, "rights " + right.word()));
            }
        }
        for (final String task : readLanded) {
            if (!parent.readLanded.contains(task)) {
                return Optional.of(new Broken(EXCEEDS_PARENT, "readLanded " + task));
            }
        }
        return Optional.empty();
    }

    /** Whether a child's workspace shows at most what its parent's does. */
    private static boolean within(final Workspace child, final Workspace parent) {
        if (child == parent || child == Workspace.NONE) {
            return true;
        }
        return switch (parent) {
            case OWN, ALL_REPOSITORIES -> child == Workspace.READ_ONLY;
            case FOREIGN, READ_ONLY, NONE -> false;
        };
    }
}
