package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;

/**
 * Brings this account's copy of a project's repository up to what the project says, when the
 * project can prove it said it.
 *
 * <p><strong>Fetch, then verify, then move.</strong> Never the other way round: the working tree is
 * left where it was until the fetched commit has been checked, so a machine that is handed
 * something unsigned keeps running what it last verified rather than running what arrived. That is
 * what "refused and reported, never applied and never silently skipped" means in code.
 *
 * <p><strong>Applying points rather than copies.</strong> The project's file is used where the
 * clone holds it, so there is no second copy on the machine to drift from the first. A local edit
 * to the clone is undone by the next fetch, because the clone is state and not a working copy.
 *
 * <p><strong>What is never reconciled</strong> is as important as what is: the vault, what a person
 * held, and running tasks. Nothing here touches them, and a commit that wanted to would have
 * nothing to act on.
 */
public final class Reconcile {

    /** What one attempt did. */
    public enum Outcome {

        /** The clone holds the accepted tip, verified unless followed unverified. */
        APPLIED,

        /** The tip is the recorded commit; a missing clone or one at another HEAD is restored. */
        UNCHANGED,

        /** The project was unfollowed before this attempt acquired its clone lock. */
        NOT_FOLLOWED,

        /**
         * A commit requiring verification carries no signature. Usually somebody forgot to sign.
         */
        NOT_SIGNED,

        /**
         * The signature is not authorized by the keys in force and the revocation policy.
         *
         * <p>Its own outcome rather than one refusal with the others, because the two are different
         * events with different cures: a key that has moved, retired or been revoked, or somebody
         * putting a project file past the machine.
         */
        UNKNOWN_KEY,

        /** Nothing is pinned here, so nothing can be verified and nothing may be applied. */
        NO_ANCHOR,

        /** The commit or the repository could not be read. */
        UNREADABLE,

        /**
         * The definition says {@code offline}, and it came from an address: following fetches it
         * again in the background, so the project would connect every round. Followed from a file
         * instead, or not at all.
         */
        OFFLINE_FROM_A_URL,

        /**
         * Followed from a file, so nothing is fetched: following it again from a newer file is how
         * it changes.
         */
        FROM_A_FILE,

        /** The repository could not be reached. What was verified before still runs. */
        UNREACHABLE,

        /**
         * This account's vault is shut, so the credential a private repository needs is out of
         * reach.
         *
         * <p>Separate from {@link #UNREACHABLE} because the action is different: one is <em>unlock
         * your vault</em>, the other is <em>look at the network or the URL</em>. Reported as
         * unreachable, it was true and misleading.
         */
        VAULT_LOCKED,

        /**
         * The fetch failed, this account's vault is open, and it holds no key for a repository that
         * needs one. Told apart from {@code UNREACHABLE} because the two send a person to opposite
         * places: one is <em>store a credential</em>, the other is <em>look at the network or the
         * URL</em>. {@code detail} names the entry and the command that fills it.
         */
        NO_CREDENTIAL,

        /**
         * This machine has never met that host, so it refused rather than deciding. What it would
         * be trusting is answered with it - every key the host offers, by type and fingerprint -
         * because the only person who can say which is real is one who was told out of band.
         */
        UNKNOWN_HOST_KEY,

        /**
         * The host answered with a different key from the one this machine remembers. Said as what
         * it may be - somebody between us and the host - and never offered as something to accept
         * instead.
         */
        HOST_KEY_CHANGED,

        /**
         * The credential is there and cannot be used: a key this machine cannot sign with, or a
         * value that is not a key at all. Told apart from having none, because the two send a
         * person to opposite places - and saying "none" about a vault that holds an entry of that
         * name sent one to store again what was already stored.
         */
        UNUSABLE_VALUE,

        /** The commit verifies and its project file does not read as a project. */
        UNUSABLE,

        /**
         * The fetched tip is not a descendant of the one in force: the history moved sideways or
         * backwards. Not applied, and waiting for a person.
         */
        REWRITTEN,

        /** Something else, said in the detail. */
        FAILED
    }

    /**
     * What one attempt did.
     *
     * @param outcome What happened.
     * @param commit The commit now in force, or "" when none ever was.
     * @param detail What to tell an operator, or "".
     * @param refused The commit that was rejected, or "" when nothing was. <strong>Not the same
     *     field as {@code commit}</strong>: what is running and what was turned away are different
     *     questions, and a screen asks both.
     * @param signer The fingerprint of the key that signed the refused commit - or, once one is
     *     applied or unchanged, the commit in force - or "" when there is none to name. Only the
     *     fingerprint, which is not a secret.
     * @param accessCommit The access commit in force, or "" when none is.
     * @param accessOutcome The last access attempt's outcome name, or "" when none was tried.
     * @param refusedFile The protected project path refused, or "".
     * @param accessRefused The access commit refused, or "".
     * @param accessRefusedFile The protected access path refused, or "".
     */
    public record Result(
            Outcome outcome,
            String commit,
            String detail,
            String refused,
            String signer,
            String accessCommit,
            String accessOutcome,
            String refusedFile,
            String accessRefused,
            String accessRefusedFile) {

        /**
         * Constructor for an attempt without protected-path refusal information.
         *
         * @param outcome What happened.
         * @param commit The commit in force.
         * @param detail What to tell an operator.
         * @param refused The commit refused, or "".
         * @param signer Its signer's fingerprint, or "".
         * @param accessCommit The access commit in force, or "".
         * @param accessOutcome The access outcome, or "".
         */
        public Result(
                Outcome outcome,
                String commit,
                String detail,
                String refused,
                String signer,
                String accessCommit,
                String accessOutcome) {
            this(outcome, commit, detail, refused, signer, accessCommit, accessOutcome, "", "", "");
        }

        /**
         * Constructor for an attempt with no access result.
         *
         * @param outcome What happened.
         * @param commit The commit in force.
         * @param detail What to tell an operator.
         * @param refused The commit turned away, or "".
         * @param signer The signer's fingerprint, or "".
         */
        public Result(
                Outcome outcome, String commit, String detail, String refused, String signer) {
            this(outcome, commit, detail, refused, signer, "", "");
        }

        /**
         * Constructor for an outcome that turned nothing away.
         *
         * @param outcome What happened.
         * @param commit The commit now in force.
         * @param detail What to tell an operator.
         */
        public Result(Outcome outcome, String commit, String detail) {
            this(outcome, commit, detail, "", "");
        }

        /**
         * Tells whether this is a state a person has to do something about.
         *
         * @return {@code true} when nothing will change until somebody acts - which is not the same
         *     as a failure that the next pass may fix by itself.
         */
        public boolean needsAPerson() {
            return needsAPerson(outcome.name()) || needsAPerson(accessOutcome);
        }

        private static boolean needsAPerson(final String outcome) {
            return switch (outcome) {
                case "UNKNOWN_KEY",
                        "NOT_SIGNED",
                        "NO_ANCHOR",
                        "UNREADABLE",
                        "REWRITTEN",
                        "UNUSABLE",
                        "VAULT_LOCKED",
                        "NO_CREDENTIAL",
                        "UNKNOWN_HOST_KEY",
                        "HOST_KEY_CHANGED",
                        "UNUSABLE_VALUE" ->
                        true;
                default -> false;
            };
        }
    }

    private final PoreloContext context;

    private final SignerHandover handover;

    private final Path signers;

    private final boolean acceptRewrite;

    private final @org.jspecify.annotations.Nullable Path krl;

    /**
     * Where a check takes the access repository from instead of the URL the project names, or
     * {@code null} to fetch that URL; "" when none was given, so nothing is fetched for it.
     */
    private final @org.jspecify.annotations.Nullable String accessFrom;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public Reconcile(final PoreloContext context) {
        this(context, context.paths().projects().configurationSigners());
    }

    /**
     * Constructor for a check against keys other than the ones this machine pinned - a key a person
     * names for a follow that must record nothing, not even the key.
     *
     * @param context The machine.
     * @param signers The pinned {@code allowed_signers} file to verify with.
     */
    public Reconcile(final PoreloContext context, final Path signers) {
        this(context, signers, null);
    }

    /**
     * Constructor for a check that reaches nothing it was not handed: the access repository is
     * taken from a path or bundle given, never from the URL the project names.
     *
     * @param context The machine.
     * @param signers The pinned {@code allowed_signers} file to verify with.
     * @param accessFrom The access repository's path or bundle, "" when none was given, or {@code
     *     null} to fetch the URL the project names.
     */
    public Reconcile(
            final PoreloContext context,
            final Path signers,
            final @org.jspecify.annotations.Nullable String accessFrom) {
        this(context, signers, accessFrom, revocations(context));
    }

    /**
     * Constructor for a check with an explicit revocation list.
     *
     * @param context The machine or isolated checker.
     * @param signers The pinned allowed-signers file.
     * @param accessFrom The access source, or {@code null} to fetch the declared URL.
     * @param krl The revocation list, or {@code null} when none is configured.
     */
    public Reconcile(
            final PoreloContext context,
            final Path signers,
            final @org.jspecify.annotations.Nullable String accessFrom,
            final @org.jspecify.annotations.Nullable Path krl) {
        this(context, signers, accessFrom, krl, false);
    }

    private Reconcile(
            final PoreloContext context,
            final Path signers,
            final @org.jspecify.annotations.Nullable String accessFrom,
            final @org.jspecify.annotations.Nullable Path krl,
            final boolean acceptRewrite) {
        this(context, signers, accessFrom, krl, acceptRewrite, ignored -> {});
    }

    private Reconcile(
            final PoreloContext context,
            final Path signers,
            final @org.jspecify.annotations.Nullable String accessFrom,
            final @org.jspecify.annotations.Nullable Path krl,
            final boolean acceptRewrite,
            final java.util.function.Consumer<String> verifiedSigner) {
        this.krl = krl;
        this.context = context;
        this.signers = signers;
        this.accessFrom = accessFrom;
        this.acceptRewrite = acceptRewrite;
        this.handover = new SignerHandover(context.runner(), signers, krl, verifiedSigner);
    }

    /** Accepts replacement tips while preserving the previous declaration. */
    Reconcile acceptingRewrite(final boolean accept) {
        return new Reconcile(context, signers, accessFrom, krl, accept);
    }

    private static @org.jspecify.annotations.Nullable Path revocations(
            final PoreloContext context) {
        final Path file = context.paths().projects().revocations();
        return Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? null : file;
    }

    /**
     * What a check found, and the key the commit it looked at was signed with.
     *
     * @param result What a follow would do.
     * @param key The public key that signed it, as ssh-keygen prints it, or {@code null} when it
     *     was not signed or not reached.
     * @param projectFile The text of {@code project.yml} at the commit a follow would put in force,
     *     or {@code null} unless the check found that commit good to apply.
     * @param accessFile The text of {@code access.yml} at the access commit a follow would put in
     *     force, or {@code null} unless the check found that commit good to apply.
     * @param signers The keys a follow would hold for the project afterwards, a handover walked;
     *     none unless the check found the commit good to apply.
     * @param project The project file read at that commit, its snippets from that commit, or {@code
     *     null} unless the check found that commit good to apply.
     * @param persons The access commit's card-verified persons, read before the scratch clone goes.
     * @param verifiedSigners Fingerprints that verified protected project commits in this range.
     */
    public record Checked(
            Result result,
            @org.jspecify.annotations.Nullable String key,
            @org.jspecify.annotations.Nullable String projectFile,
            @org.jspecify.annotations.Nullable String accessFile,
            List<String> signers,
            org.fuin.porelo.core.project.@org.jspecify.annotations.Nullable Project project,
            List<org.fuin.porelo.vault.AccessPersons.Person> persons,
            List<String> verifiedSigners) {

        /**
         * Constructor for a check that kept no access file.
         *
         * @param result What a follow would do.
         * @param key The key that signed it, or {@code null}.
         * @param projectFile The project file, or {@code null}.
         */
        public Checked(
                final Result result,
                final @org.jspecify.annotations.Nullable String key,
                final @org.jspecify.annotations.Nullable String projectFile) {
            this(result, key, projectFile, null, List.of(), null, List.of(), List.of());
        }

        /**
         * Constructor for a check that kept no project file.
         *
         * @param result What a follow would do.
         * @param key The key that signed it, or {@code null}.
         */
        public Checked(final Result result, final @org.jspecify.annotations.Nullable String key) {
            this(result, key, null);
        }
    }

    /**
     * Brings one followed project up to date.
     *
     * @param followed What this account knows about it.
     * @return What this attempt did.
     */
    public Result run(final FollowedProjects.Followed followed) {
        final Path lock = context.paths().projects().followedCloneLock(followed.name());
        // One at a time per project, across the daemon's watch and a follow at the terminal:
        // unlocked, the watch checked descent against the commit it had read, and reset the clone
        // back under one a follow had applied.
        try {
            final Result result =
                    FileLocks.holding(
                            lock,
                            () -> {
                                final FollowedProjects.Followed current =
                                        new FollowedProjects(context.paths().projects().followed())
                                                .find(followed.name());
                                return current == null
                                        ? FileLocks.holdingThenRemoving(
                                                lock,
                                                () ->
                                                        new Result(
                                                                Outcome.NOT_FOLLOWED,
                                                                "",
                                                                "this account no longer follows "
                                                                        + followed.name()))
                                        : reconcilePrepared(current);
                            });
            return result;
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private Result reconcilePrepared(final FollowedProjects.Followed followed) {
        try (final Prepared prepared = prepare(followed, null)) {
            final Result result = prepared.checked().result();
            if (result.outcome() == Outcome.APPLIED || result.outcome() == Outcome.UNCHANGED) {
                if (prepared.checked().project() == null) {
                    return new Result(
                            Outcome.UNREADABLE,
                            followed.commit(),
                            "cannot read the prepared project before publication");
                }
                final boolean changed =
                        Files.exists(signers)
                                ? Files.mismatch(signers, prepared.signers()) != -1
                                : Files.size(prepared.signers()) > 0;
                try (final SignerHandover.Staged staged =
                        changed
                                ? prepared.stageKeys(context.runner(), signers, followed.name())
                                : null) {
                    prepared.publishClones(context, followed.name());
                    if (staged != null) {
                        staged.publish();
                    }
                }
            }
            return result;
        } catch (final IOException ex) {
            return new Result(
                    Outcome.UNREADABLE,
                    followed.commit(),
                    "cannot prepare or publish the followed project: " + ex.getMessage());
        }
    }

    /**
     * Says what following this project would do, and does none of it.
     *
     * <p><strong>Everything the real thing does, somewhere else.</strong> It fetches, verifies,
     * reads the file and checks the egress sets - against a scratch clone that is deleted
     * afterwards, so nothing this account follows moves and nothing is recorded. The answer is
     * {@code APPLIED} when following would work, and otherwise the same named reason the real
     * follow would give.
     *
     * <p>Asked before anything is written, because a follow that cannot apply used to leave a
     * half-made project behind - one was met, with no way to want it gone.
     *
     * @param followed What would be followed, including the commits already in force.
     * @return What a follow would do.
     */
    public Result check(final FollowedProjects.Followed followed) {
        return checked(followed).result();
    }

    /**
     * Says what following this project would do, and which key signed what it found.
     *
     * <p>The key is read out of the scratch clone before it goes, so a person who named only a
     * fingerprint can be answered without a second fetch - and it is only ever used once its
     * fingerprint is the one they named.
     *
     * @param followed What would be followed.
     * @return What a follow would do, and the key.
     */
    public Checked checked(final FollowedProjects.Followed followed) {
        // On a copy of the live clones, as the follow prepares itself: a check on an empty clone
        // said "ready" where the follow refused a shallow one, and refused a bundle the follow
        // took on top of what it had.
        // A check records nothing, not even the follow directory: its copy lives in the temp dir.
        try (Prepared prepared = prepare(followed, null, false)) {
            return prepared.checked();
        } catch (final IOException ex) {
            return new Checked(
                    new Result(
                            Outcome.UNREADABLE,
                            followed.commit(),
                            "cannot prepare the check: " + ex.getMessage()),
                    null);
        }
    }

    /**
     * Says what following this project from the given commits on would do, and does none of it:
     * {@link #checked} with the project's commit and the access commit taken as the ones in force,
     * so a commit that does not descend from them is refused as a follow refuses it.
     *
     * @param followed What would be followed, with the commits in force.
     * @return What a follow would do, the key, and both files when good.
     */
    public Checked verified(final FollowedProjects.Followed followed) {
        return scratchCheck(followed);
    }

    private Checked scratchCheck(final FollowedProjects.Followed followed) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("porelo-follow-check-");
            final Path clone = scratch.resolve("clone");
            // A handover a check walks pins into a copy; with no file there is nothing to pin, and
            // the refusal names the file a person must write.
            final Path checkSigners =
                    Files.notExists(signers, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            ? signers
                            : Files.copy(signers, scratch.resolve("signers"));
            final java.util.Set<String> verifiedSigners = new java.util.LinkedHashSet<>();
            final Result result =
                    new Reconcile(
                                    context,
                                    checkSigners,
                                    accessFrom,
                                    krl,
                                    acceptRewrite,
                                    verifiedSigners::add)
                            .run(followed, clone, scratch.resolve("access"));
            return checkedResult(
                    clone,
                    scratch.resolve("access"),
                    checkSigners,
                    followed,
                    result,
                    List.copyOf(verifiedSigners));
        } catch (final IOException ex) {
            return new Checked(
                    new Result(
                            Outcome.UNREADABLE,
                            followed.commit(),
                            "cannot make a scratch clone to check with: " + ex.getMessage()),
                    null);
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
        }
    }

    private Checked checkedResult(
            final Path clone,
            final Path accessClone,
            final Path checkSigners,
            final FollowedProjects.Followed followed,
            final Result result,
            final List<String> verifiedSigners) {
        final String projectFile = projectFile(clone, result);
        final String accessText = accessFile(clone, accessClone, result);
        final org.fuin.porelo.core.project.Project project =
                projectFile == null ? null : project(clone, result.commit());
        // Read before the scratch clone goes, from the clone the access file came from.
        final List<org.fuin.porelo.vault.AccessPersons.Person> persons =
                accessText == null || project == null
                        ? List.of()
                        : org.fuin.porelo.vault.AccessPersons.read(
                                context.runner(),
                                project.access()
                                                instanceof
                                                org.fuin.porelo.core.project.Access.InRepository
                                        ? accessClone
                                        : clone,
                                result.accessCommit(),
                                AccessFile.read(new java.io.StringReader(accessText), Access.FILE));
        return new Checked(
                result,
                result.refused().isEmpty()
                        ? result.signer().isEmpty()
                                ? null
                                : SignedBy.keyOf(context.runner(), clone, result.commit())
                        : SignedBy.keyOf(context.runner(), clone, result.refused()),
                projectFile,
                accessText,
                projectFile == null
                        ? List.of()
                        : new SignerHandover(context.runner(), checkSigners)
                                .pinned(followed.name()),
                project,
                persons,
                verifiedSigners);
    }

    /** A checked snapshot retained until the caller accepts or refuses publication. */
    record Prepared(
            Path directory, Path repository, Path accessClone, Path signers, Checked checked)
            implements AutoCloseable {
        void publishClones(final PoreloContext context, final String name) throws IOException {
            final ProjectPaths paths = context.paths().projects();
            if (checked.result().outcome() == Outcome.APPLIED
                    || checked.result().outcome() == Outcome.UNCHANGED
                            && !atCommit(
                                    context.runner(),
                                    paths.followedClone(name),
                                    checked.result().commit())) {
                replace(
                        repository,
                        paths.followedClone(name),
                        directory.resolve("previous-project"));
            }
            if ((Outcome.APPLIED.name().equals(checked.result().accessOutcome())
                            || Outcome.UNCHANGED.name().equals(checked.result().accessOutcome())
                                    && !atCommit(
                                            context.runner(),
                                            paths.followedAccessClone(name),
                                            checked.result().accessCommit()))
                    && Files.isDirectory(accessClone)) {
                replace(
                        accessClone,
                        paths.followedAccessClone(name),
                        directory.resolve("previous-access"));
            }
        }

        SignerHandover.Staged stageKeys(
                final CommandRunner runner, final Path destination, final String name)
                throws IOException {
            final List<String> lines =
                    Files.notExists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            ? new ArrayList<>()
                            : new ArrayList<>(AllowedSigner.read(runner, destination));
            lines.removeIf(line -> name.equals(principalOf(line)));
            AllowedSigner.read(runner, signers).stream()
                    .filter(line -> name.equals(principalOf(line)))
                    .forEach(lines::add);
            Files.createDirectories(destination.getParent());
            final Path staged =
                    Files.createTempFile(destination.getParent(), ".project-signers-", "");
            try {
                Files.write(staged, lines, StandardCharsets.UTF_8);
                AllowedSigner.read(runner, staged);
                return new SignerHandover.Staged(staged, destination, "");
            } catch (final IOException | RuntimeException ex) {
                Files.deleteIfExists(staged);
                throw ex;
            }
        }

        private static String principalOf(final String line) {
            final String[] fields = line.strip().split("\\s+", 2);
            return fields[0];
        }

        private static boolean atCommit(
                final CommandRunner runner, final Path clone, final String commit) {
            // A bare or linked clone answers HEAD too, and is no work tree to keep.
            if (!holdsRepository(clone)) {
                return false;
            }
            final CommandResult head =
                    runner.run(
                            Command.of(
                                    "git",
                                    "-C",
                                    clone.toString(),
                                    "rev-parse",
                                    "--verify",
                                    "HEAD"));
            return head.successful() && head.standardOutput().strip().equals(commit);
        }

        private static void replace(final Path source, final Path destination, final Path backup)
                throws IOException {
            // A link, dangling or not, is moved aside as itself, never followed.
            final boolean existed =
                    Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (existed) {
                Files.move(destination, backup, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException ex) {
                if (existed) {
                    Files.move(backup, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
                throw ex;
            }
        }

        @Override
        public void close() {
            deleteTree(directory);
        }
    }

    /**
     * Prepares as {@link #prepare(FollowedProjects.Followed, String)} for a check only, in the temp
     * directory, so it records nothing, not even the follow directory.
     */
    Prepared prepareCheck(
            final FollowedProjects.Followed followed,
            final @org.jspecify.annotations.Nullable String fingerprint)
            throws IOException {
        return prepare(followed, fingerprint, false);
    }

    /** Fetches once and checks only that snapshot, keeping all changes private until accepted. */
    Prepared prepare(
            final FollowedProjects.Followed followed,
            final @org.jspecify.annotations.Nullable String fingerprint)
            throws IOException {
        return prepare(followed, fingerprint, true);
    }

    /**
     * Prepares as {@link #prepare(FollowedProjects.Followed, String)}, beside the live clones when
     * they are to be replaced by it, else in the temp directory.
     */
    private Prepared prepare(
            final FollowedProjects.Followed followed,
            final @org.jspecify.annotations.Nullable String fingerprint,
            final boolean toPublish)
            throws IOException {
        final Path scratch;
        if (toPublish) {
            final Path parent =
                    context.paths().projects().followedClone(followed.name()).getParent();
            Files.createDirectories(parent);
            scratch = Files.createTempDirectory(parent, ".follow-prepared-");
        } else {
            scratch = Files.createTempDirectory("porelo-follow-check-");
        }
        boolean prepared = false;
        try {
            final Path clone = scratch.resolve("clone");
            final Path accessClone = scratch.resolve("access");
            copyClone(context.paths().projects().followedClone(followed.name()), clone);
            copyClone(context.paths().projects().followedAccessClone(followed.name()), accessClone);
            final Path checkSigners = scratch.resolve("signers");
            if (Files.notExists(signers, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.writeString(checkSigners, "");
            } else {
                Files.copy(signers, checkSigners);
            }
            final java.util.Set<String> verifiedSigners = new java.util.LinkedHashSet<>();
            final Reconcile staged =
                    new Reconcile(
                            context,
                            checkSigners,
                            accessFrom,
                            krl,
                            acceptRewrite,
                            verifiedSigners::add);
            final Result failed = staged.fetch(followed, clone);
            final Result result;
            if (failed != null) {
                result = failed;
            } else {
                final Result first = staged.run(followed, clone, accessClone, true);
                if (fingerprint != null
                        && (first.outcome() == Outcome.UNKNOWN_KEY
                                || first.outcome() == Outcome.NO_ANCHOR)) {
                    final String key =
                            SignedBy.keyOf(
                                    context.runner(),
                                    clone,
                                    first.refused().isEmpty()
                                            ? headOf(context.runner(), clone)
                                            : first.refused());
                    if (key != null && fingerprint.equals(SignedBy.fingerprintOf(key))) {
                        final List<String> lines =
                                new java.util.ArrayList<>(Files.readAllLines(checkSigners));
                        final String line = followed.name() + " " + key;
                        if (!lines.contains(line)) {
                            lines.add(line);
                            Files.write(checkSigners, lines);
                        }
                        verifiedSigners.clear();
                        result = staged.run(followed, clone, accessClone, true);
                    } else {
                        result = first;
                    }
                } else {
                    result = first;
                }
            }
            final Checked checked =
                    staged.checkedResult(
                            clone,
                            accessClone,
                            checkSigners,
                            followed,
                            result,
                            List.copyOf(verifiedSigners));
            prepared = true;
            return new Prepared(scratch, clone, accessClone, checkSigners, checked);
        } finally {
            if (!prepared) {
                deleteTree(scratch);
            }
        }
    }

    /**
     * Reads the project file a follow would put in force, from the scratch clone before it goes,
     * and only when the check found that commit good: a caller must never read a file that failed
     * its signature.
     */
    private @org.jspecify.annotations.Nullable String projectFile(
            final Path clone, final Result result) {
        if ((result.outcome() != Outcome.APPLIED && result.outcome() != Outcome.UNCHANGED)
                || result.commit().isEmpty()) {
            return null;
        }
        final CommandResult shown =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        clone.toString(),
                                        "show",
                                        result.commit() + ":project.yml"));
        return shown.successful() ? shown.standardOutput() : null;
    }

    /**
     * Reads the project file at a commit found good, with the snippets that commit holds: the
     * scratch clone's tree is moved there first, so nothing is read from wherever the caller runs.
     */
    private org.fuin.porelo.core.project.@org.jspecify.annotations.Nullable Project project(
            final Path clone, final String commit) {
        final CommandResult moved =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        clone.toString(),
                                        "reset",
                                        "--hard",
                                        "--quiet",
                                        commit));
        if (!moved.successful()) {
            return null;
        }
        try {
            return readAt(context.runner(), clone, commit, clone.resolve("project.yml"));
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * Reads the access file a follow would put in force, from where the check verified it, and only
     * when it found that commit good.
     */
    private @org.jspecify.annotations.Nullable String accessFile(
            final Path clone, final Path accessClone, final Result result) {
        if (projectFile(clone, result) == null
                || result.accessCommit().isEmpty()
                || (!Outcome.APPLIED.name().equals(result.accessOutcome())
                        && !Outcome.UNCHANGED.name().equals(result.accessOutcome()))) {
            return null;
        }
        try {
            final Access access =
                    readAt(context.runner(), clone, result.commit(), clone.resolve("project.yml"))
                            .access();
            final Path where = access instanceof Access.InRepository ? accessClone : clone;
            final CommandResult shown =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            where.toString(),
                                            "show",
                                            result.accessCommit() + ":" + Access.FILE));
            return shown.successful() ? shown.standardOutput() : null;
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private static void copyClone(final Path source, final Path destination) throws IOException {
        if (!holdsRepository(source)) {
            return;
        }
        try (final var paths = Files.walk(source)) {
            for (final Path path : paths.toList()) {
                final Path copy = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(
                            path,
                            copy,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Whether a clone directory holds what a follow keeps there: a work tree with its own {@code
     * .git} directory holding its HEAD, objects and refs. A link is not followed, and a bare
     * repository, a {@code .git} file or one left half deleted is not one: each is made again
     * rather than fetched into.
     */
    private static boolean holdsRepository(final Path clone) {
        final java.nio.file.LinkOption noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        final Path git = clone.resolve(".git");
        return !Files.isSymbolicLink(clone)
                && Files.isDirectory(git, noFollow)
                && Files.isRegularFile(git.resolve("HEAD"), noFollow)
                && Files.isDirectory(git.resolve("objects"), noFollow)
                && Files.isDirectory(git.resolve("refs"), noFollow);
    }

    private static void deleteTree(final Path root) {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (final IOException ex) {
                                    // A scratch directory that will not go is not worth failing a
                                    // check over.
                                    return;
                                }
                            });
        } catch (final IOException ex) {
            // Same.
            return;
        }
    }

    private Result run(
            final FollowedProjects.Followed followed, final Path clone, final Path accessClone) {
        return run(followed, clone, accessClone, false);
    }

    private Result run(
            final FollowedProjects.Followed followed,
            final Path clone,
            final Path accessClone,
            final boolean projectFetched) {
        final Result project = reconcileProject(followed, clone, projectFetched);
        if (project.outcome() != Outcome.APPLIED && project.outcome() != Outcome.UNCHANGED) {
            return new Result(
                    project.outcome(),
                    project.commit(),
                    project.detail(),
                    project.refused(),
                    project.signer(),
                    followed.accessCommit(),
                    followed.accessOutcome(),
                    project.refusedFile(),
                    followed.accessRefused(),
                    followed.accessRefusedFile());
        }
        String inForce = acceptRewrite ? "" : followed.accessCommit();
        try {
            // The verified commit alone may select a source of access settings.
            final Access access =
                    readAt(context.runner(), clone, project.commit(), clone.resolve("project.yml"))
                            .access();
            if (access == null) {
                return project;
            }
            // An access commit belongs to the place it came from: once the project names another,
            // none is in force there, so neither is the old one served nor its descent asked for.
            if (!namedBefore(clone, followed.commit(), project.commit(), access)) {
                inForce = "";
            }
            final Result applied =
                    switch (access) {
                        case Access.BesideProject beside ->
                                readAccess(clone, project.commit(), inForce);
                        case Access.InRepository repository ->
                                accessFrom == null
                                        ? reconcileAccess(
                                                followed, repository.url(), accessClone, inForce)
                                        : accessFrom.isEmpty()
                                                ? new Result(
                                                        Outcome.FAILED,
                                                        inForce,
                                                        "the access file is in "
                                                                + repository.url()
                                                                + ", and no copy of that"
                                                                + " repository was given to check")
                                                : reconcileAccess(
                                                        followed, accessFrom, accessClone, inForce);
                    };
            return withAccess(project, applied);
        } catch (final RuntimeException ex) {
            return withAccess(
                    project,
                    new Result(Outcome.UNUSABLE, inForce, String.valueOf(ex.getMessage())));
        }
    }

    /** Whether the project file in force before named the access file where this one does. */
    private boolean namedBefore(
            final Path clone, final String before, final String now, final Access access) {
        if (before.isEmpty() || before.equals(now)) {
            return true;
        }
        try {
            return access.equals(
                    readAt(context.runner(), clone, before, clone.resolve("project.yml")).access());
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    private static Result withAccess(final Result project, final Result access) {
        final String detail =
                access.detail().isEmpty()
                        ? project.detail()
                        : (project.detail().isEmpty() ? "" : project.detail() + "; ")
                                + "access file: "
                                + access.detail();
        return new Result(
                project.outcome(),
                project.commit(),
                detail,
                project.refused(),
                project.signer(),
                access.commit(),
                access.outcome().name(),
                project.refusedFile(),
                access.refused(),
                access.refusedFile());
    }

    private Result reconcileAccess(
            final FollowedProjects.Followed followed,
            final String url,
            final Path clone,
            final String inForce) {
        final FollowedProjects.Followed access =
                new FollowedProjects.Followed(followed.name(), url, inForce, "", "", "");
        final Result failed = fetch(access, clone);
        if (failed != null) {
            return new Result(
                    failed.outcome(),
                    failed.commit(),
                    failed.detail()
                            + "; keeping access commit "
                            + (access.commit().isEmpty() ? "(none)" : access.commit()));
        }
        try {
            final String commit = headOf(context.runner(), clone);
            if (!access.commit().isEmpty() && !descends(clone, access.commit(), commit)) {
                return new Result(
                        Outcome.REWRITTEN,
                        access.commit(),
                        commit
                                + " is not a descendant of access commit "
                                + access.commit()
                                + ", which is in force. Accept the rewrite with 'porelo projects"
                                + " follow "
                                + followed.name()
                                + " "
                                + followed.url()
                                + " --accept-rewrite'.",
                        commit,
                        "");
            }
            if (!followed.unverified()) {
                final SignerHandover.Handed checked =
                        handover.access(clone, followed.name(), inForce, commit);
                if (checked.refused() != null) {
                    return refused(access, checked.refused(), checked.refusedFile());
                }
            }
            final Result read = readAccess(clone, commit, access.commit());
            if (read.outcome() != Outcome.APPLIED && read.outcome() != Outcome.UNCHANGED) {
                return read;
            }
            // A signature and a readable access file must both precede the working tree's move.
            final CommandResult moved =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            clone.toString(),
                                            "reset",
                                            "--hard",
                                            "--quiet",
                                            commit));
            return moved.successful()
                    ? read
                    : new Result(
                            Outcome.FAILED,
                            access.commit(),
                            "have "
                                    + commit
                                    + " and could not check it out: "
                                    + moved.standardError().strip());
        } catch (final RuntimeException ex) {
            return new Result(Outcome.FAILED, access.commit(), String.valueOf(ex.getMessage()));
        }
    }

    private Result readAccess(final Path clone, final String commit, final String inForce) {
        try {
            final CommandResult shown =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            clone.toString(),
                                            "show",
                                            commit + ":" + Access.FILE));
            if (!shown.successful()) {
                return new Result(
                        Outcome.UNUSABLE,
                        inForce,
                        "cannot read "
                                + Access.FILE
                                + " at "
                                + commit
                                + ": "
                                + shown.standardError().strip(),
                        commit,
                        "",
                        "",
                        "",
                        Access.FILE,
                        "",
                        "");
            }
            AccessFile.read(
                    new java.io.StringReader(shown.standardOutput()),
                    Access.FILE + " at " + commit);
            return new Result(
                    commit.equals(inForce) ? Outcome.UNCHANGED : Outcome.APPLIED, commit, "");
        } catch (final RuntimeException ex) {
            return new Result(
                    Outcome.UNUSABLE,
                    inForce,
                    Access.FILE
                            + " at "
                            + commit
                            + " does not read as an access file: "
                            + ex.getMessage(),
                    commit,
                    "",
                    "",
                    "",
                    Access.FILE,
                    "",
                    "");
        }
    }

    private static Result refused(
            final FollowedProjects.Followed followed,
            final ConfigurationGate.Verdict verdict,
            final String file) {
        return new Result(
                switch (verdict.outcome()) {
                    case NOT_SIGNED -> Outcome.NOT_SIGNED;
                    case UNKNOWN_KEY -> Outcome.UNKNOWN_KEY;
                    case NO_ANCHOR -> Outcome.NO_ANCHOR;
                    default -> Outcome.UNREADABLE;
                },
                followed.commit(),
                verdict.detail(),
                verdict.commit(),
                verdict.signer(),
                "",
                "",
                file,
                "",
                "");
    }

    private @org.jspecify.annotations.Nullable Result fetch(
            final FollowedProjects.Followed followed, final Path clone) {
        final CommandRunner runner = context.runner();
        try {
            if (!holdsRepository(clone)) {
                // A directory left empty, or with its .git gone, is made again rather than fetched
                // into: git would look upwards for a repository and fetch into whatever it finds.
                Files.createDirectories(clone.getParent());
                final CommandResult made =
                        runner.run(Command.of("git", "init", "-q", clone.toString()));
                if (!made.successful()) {
                    return new Result(
                            Outcome.FAILED,
                            followed.commit(),
                            "cannot make " + clone + ": " + made.standardError().strip());
                }
            }
            final CommandResult fetched;
            final boolean lent;
            final boolean vaultShut;
            // The key is lent for the length of the fetch and taken back, so a private repository
            // is reachable by what the vault holds rather than by what the account happens to have
            // lying in ~/.ssh.
            try (FollowCredential credential =
                    FollowCredential.open(context, followed.url(), followed.name())) {
                lent = credential.lent();
                vaultShut = credential.vaultShut();
                final java.util.List<String> arguments =
                        new java.util.ArrayList<>(java.util.List.of("git"));
                arguments.addAll(credential.arguments());
                arguments.addAll(
                        java.util.List.of(
                                "-C",
                                clone.toString(),
                                "fetch",
                                "--quiet",
                                "--end-of-options",
                                followed.url(),
                                "HEAD"));
                fetched =
                        runner.run(
                                Command.of(arguments)
                                        .withEnvironment(
                                                credential.environmentFor(
                                                        context, followed.url())));
            }
            // The source first: a shallow source makes the clone shallow, and the refusal names
            // what a person must fix, not the scratch copy.
            final Path source =
                    followed.url().startsWith("file:")
                            ? fileSource(followed.url())
                            : Path.of(followed.url());
            if (Files.isDirectory(source)) {
                SignerHandover.requireFullHistory(runner, source);
            }
            SignerHandover.requireFullHistory(runner, clone);
            if (!fetched.successful()) {
                // Unreachable is not a fault of the project's and not a reason to stop: what was
                // verified before is still what this machine runs.
                //
                // A shut vault is its own outcome rather than a sentence appended to this one.
                // Reported as unreachable it was true and misleading: nothing is wrong with the
                // network or the URL, and the one thing that helps is unlocking. A vault that
                // EXISTS and is shut. A machine with no vault at all has no credential locked away,
                // so nothing about it explains a failed fetch - saying "unlock your vault" there
                // would send somebody to a vault they have not made.
                final String said = fetched.standardError().strip();
                if (HostKeys.refusedTheHost(said)) {
                    // Asked before anything about credentials: git failing on the host's key says
                    // nothing at all about what this machine holds, and answering NO_CREDENTIAL
                    // there sent somebody to store a key they had just stored.
                    final String host = HostKeys.nameOf(followed.url());
                    if (HostKeys.changed(said)) {
                        return new Result(
                                Outcome.HOST_KEY_CHANGED,
                                followed.commit(),
                                host
                                        + " answered with a different key from the one this machine"
                                        + " remembers. That is what somebody between you and it"
                                        + " would look like. Nothing was fetched.");
                    }
                    return new Result(
                            Outcome.UNKNOWN_HOST_KEY,
                            followed.commit(),
                            "this machine has never met "
                                    + host
                                    + ", so it stopped rather than"
                                    + " deciding for you. See what it offers with 'porelo"
                                    + " credentials trust-host "
                                    + host
                                    + "', confirm one against"
                                    + " what you were told, and follow again.");
                }
                if (vaultShut) {
                    return new Result(
                            Outcome.VAULT_LOCKED,
                            followed.commit(),
                            "cannot fetch "
                                    + followed.url()
                                    + " while this account's vault is"
                                    + " shut, so the credential a private repository needs is out"
                                    + " of reach: "
                                    + fetched.standardError().strip());
                }
                if (!lent && FollowCredential.needsACredential(followed.url())) {
                    // The vault is open or absent and holds nothing for this host, and the URL is
                    // one only a credential opens. Saying "unreachable" here sent people to look at
                    // their network for a credential they had never stored.
                    return new Result(
                            Outcome.NO_CREDENTIAL,
                            followed.commit(),
                            "cannot fetch "
                                    + followed.url()
                                    + " and this account's vault holds no"
                                    + " credential for it. Store one with: '"
                                    + FollowCredential.storeCommand(followed.url())
                                    + "'. What git said: "
                                    + fetched.standardError().strip());
                }
                return new Result(
                        Outcome.UNREACHABLE,
                        followed.commit(),
                        "cannot fetch "
                                + followed.url()
                                + ": "
                                + fetched.standardError().strip()
                                + identityHint(followed.url()));
            }
            return null;
        } catch (final ProjectException ex) {
            return new Result(
                    Outcome.UNREADABLE, followed.commit(), String.valueOf(ex.getMessage()));
        } catch (final IOException | RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    private static Path fileSource(final String url) {
        try {
            return Path.of(java.net.URI.create(url));
        } catch (final IllegalArgumentException ex) {
            throw new ProjectException("cannot read local repository " + url, ex);
        }
    }

    private Result reconcileProject(
            final FollowedProjects.Followed followed, final Path clone, final boolean fetched) {
        final CommandRunner runner = context.runner();
        final Result failed = fetched ? null : fetch(followed, clone);
        if (failed != null) {
            return failed;
        }
        try {
            final String tip = headOf(runner, clone);
            if (followed.unverified()) {
                return applied(runner, clone, followed, tip);
            }
            if (!acceptRewrite
                    && !followed.commit().isEmpty()
                    && !descends(clone, followed.commit(), tip)) {
                return new Result(
                        Outcome.REWRITTEN,
                        followed.commit(),
                        tip
                                + " is not a descendant of "
                                + followed.commit()
                                + ", which is in force. The history was rewritten, or an older"
                                + " configuration is being served. Accept it with 'porelo projects"
                                + " follow "
                                + followed.name()
                                + " "
                                + followed.url()
                                + " --accept-rewrite'.");
            }
            final SignerHandover.Handed handed =
                    handover.walk(
                            clone,
                            followed.name(),
                            acceptRewrite ? "" : followed.commit(),
                            tip,
                            acceptRewrite && !followed.commit().isEmpty()
                                    ? new SignerHandover.Rewrite(
                                            context.paths()
                                                    .projects()
                                                    .followedClone(followed.name()),
                                            followed.commit())
                                    : null);
            if (handed.refused() != null) {
                return refused(followed, handed.refused(), handed.refusedFile());
            }
            final String signer =
                    handed.tip() == null ? "" : signerOf(runner, clone, handed.tip().commit());
            if (tip.equals(followed.commit())) {
                return new Result(Outcome.UNCHANGED, followed.commit(), "", "", signer);
            }
            try (final SignerHandover.Staged staged =
                    handed.changed() ? handover.stage(followed.name(), handed) : null) {
                final Result applied = applied(runner, clone, followed, tip);
                if (applied.outcome() != Outcome.APPLIED
                        && applied.outcome() != Outcome.UNCHANGED) {
                    return applied;
                }
                // Validation precedes the checkout; publication waits until the configuration
                // applies.
                final String pinned = staged == null ? "" : staged.publish();
                return new Result(
                        applied.outcome(),
                        applied.commit(),
                        applied.detail().isEmpty() || pinned.isEmpty()
                                ? applied.detail() + pinned
                                : applied.detail() + "; " + pinned,
                        applied.refused(),
                        signer);
            } catch (final IOException ex) {
                return refused(
                        followed,
                        new ConfigurationGate.Verdict(
                                ConfigurationGate.Outcome.UNREADABLE,
                                tip,
                                signer,
                                "cannot record the signer handover at "
                                        + tip
                                        + ": "
                                        + ex.getMessage()),
                        "project.yml");
            }
        } catch (final RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Writes a commit applied into the account's event log: what this machine's tasks run under
     * from now on, and whose key vouched for it. Called where the follow stands, never by a check:
     * a check applies nothing.
     *
     * @param context The machine.
     * @param followed The project followed.
     * @param result What the reconcile did; nothing is written unless it applied a commit.
     */
    public static void recordApplied(
            final PoreloContext context,
            final FollowedProjects.Followed followed,
            final Result result) {
        if (result.outcome() == Outcome.APPLIED
                || (Outcome.APPLIED.name().equals(result.accessOutcome())
                        && !result.accessCommit().equals(followed.accessCommit()))) {
            keepRoom(context, followed.name());
        }
        if (result.outcome() == Outcome.APPLIED) {
            EventLog.record(
                    context,
                    EventLog.PROJECT_APPLIED,
                    followed.name(),
                    "",
                    "commit "
                            + result.commit()
                            + (followed.unverified()
                                    ? ", unverified"
                                    : result.signer().isEmpty()
                                            ? ""
                                            : ", signed by " + result.signer())
                            + (result.detail().isEmpty() ? "" : "; " + result.detail()));
        }
        if ((result.outcome() != Outcome.APPLIED && result.outcome() != Outcome.UNCHANGED)
                || !Outcome.APPLIED.name().equals(result.accessOutcome())
                || result.accessCommit().equals(followed.accessCommit())) {
            return;
        }
        try {
            final Path clone = context.paths().projects().followedClone(followed.name());
            final Access access =
                    readAt(context.runner(), clone, result.commit(), clone.resolve("project.yml"))
                            .access();
            final String location;
            final Path repository;
            switch (access) {
                case Access.InRepository inRepository -> {
                    location =
                            "access file in "
                                    + inRepository.url()
                                    + " commit "
                                    + result.accessCommit();
                    repository = context.paths().projects().followedAccessClone(followed.name());
                }
                case Access.BesideProject beside -> {
                    location = "access file beside project.yml, commit " + result.accessCommit();
                    repository = clone;
                }
                case null ->
                        throw new IllegalStateException("an applied access commit has a source");
            }
            final ConfigurationGate.Verdict verdict =
                    new ConfigurationGate(
                                    context.runner(),
                                    context.paths().projects().configurationSigners(),
                                    revocations(context))
                            .verify(repository, result.accessCommit(), followed.name());
            final String signer =
                    verdict.mayApply()
                            ? signerOf(context.runner(), repository, verdict.commit())
                            : "";
            EventLog.record(
                    context,
                    EventLog.PROJECT_APPLIED,
                    followed.name(),
                    "",
                    location
                            + (followed.unverified()
                                    ? ", unverified"
                                    : signer.isEmpty() ? "" : ", signed by " + signer));
        } catch (final RuntimeException ex) {
            System.err.println(
                    "porelo: the event log could not record the access file: " + ex.getMessage());
        }
    }

    /**
     * Keeps the project's room to its admitted persons list, where this account made the room, and
     * logs what changed; a room that cannot be kept is said, never a reason to undo the commit.
     */
    private static void keepRoom(final PoreloContext context, final String name) {
        try {
            final java.util.Map<?, ?> kept =
                    MachineTransports.keepMembers(context, GateSupport.byName(context, name));
            if (kept != null) {
                EventLog.record(
                        context,
                        EventLog.PROJECT_APPLIED,
                        name,
                        "",
                        "room kept to the list: invited "
                                + TalkRoomCommand.listed(kept.get("invited"))
                                + ", removed "
                                + TalkRoomCommand.listed(kept.get("removed"))
                                + (kept.containsKey("persons_invited")
                                                || kept.containsKey("persons_removed")
                                        ? "; persons invited "
                                                + TalkRoomCommand.listed(
                                                        kept.get("persons_invited"))
                                                + ", removed "
                                                + TalkRoomCommand.listed(
                                                        kept.get("persons_removed"))
                                        : ""));
            }
        } catch (final Conversations.Refused | RuntimeException ex) {
            System.err.println(
                    "porelo: the room of " + name + " is not kept to the list: " + ex.getMessage());
        }
    }

    private static String signerOf(final CommandRunner runner, final Path clone) {
        return signerOf(runner, clone, "FETCH_HEAD");
    }

    private static String signerOf(final CommandRunner runner, final Path clone, final String ref) {
        final String key = SignedBy.keyOf(runner, clone, ref);
        final String fingerprint = key == null ? null : SignedBy.fingerprintOf(key);
        return fingerprint == null ? "" : fingerprint;
    }

    /**
     * Returns who this machine logs in as at that host, when a fetch over ssh was refused.
     *
     * <p>The asking itself is {@link ForgeIdentity}, because the better moment to ask is when
     * somebody DECLARES a credential rather than when a fetch has already failed - and a question
     * asked at two moments belongs in neither of them.
     *
     * @param url What could not be fetched.
     * @return A sentence beginning with a space, or "".
     */
    private String identityHint(final String url) {
        if (GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY) {
            return "";
        }
        final StringBuilder said = new StringBuilder();
        if (ForgeIdentity.userIn(url) == null) {
            final String host = GitCredentialNames.hostOf(url);
            said.append(" The address names no user, so ssh logs in as this account;")
                    .append(" a forge expects git@")
                    .append(host == null ? "the host" : host)
                    .append('.');
        }
        final String greeting = ForgeIdentity.of(context, url, "identity");
        if (greeting != null) {
            said.append(" That host says: \"").append(greeting).append("\"");
        }
        return said.toString();
    }

    /**
     * Whether a commit's project file says {@code offline}; one that cannot be read is judged where
     * it is read.
     */
    private static boolean offline(
            final CommandRunner runner, final Path clone, final String commit) {
        final CommandResult shown =
                runner.run(
                        Command.of("git", "-C", clone.toString(), "show", commit + ":project.yml"));
        if (!shown.successful()) {
            return false;
        }
        try {
            return ProjectReader.read(
                                    new java.io.StringReader(shown.standardOutput()), "project.yml")
                            .securityClass()
                    == org.fuin.porelo.core.project.SecurityClass.OFFLINE;
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /**
     * Moves the working tree to a commit and says what it replaced.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @param followed What is recorded.
     * @param commit What to move to.
     * @return What happened.
     */
    private Result applied(
            final CommandRunner runner,
            final Path clone,
            final FollowedProjects.Followed followed,
            final String commit) {
        if (commit.equals(followed.commit())) {
            return new Result(Outcome.UNCHANGED, followed.commit(), "");
        }
        if (!FollowedProjects.fromAFile(followed.url()) && offline(runner, clone, commit)) {
            // Before anything of it is used: what was in force stays, and a first follow records
            // nothing.
            return new Result(
                    Outcome.OFFLINE_FROM_A_URL,
                    followed.commit(),
                    followed.name()
                            + " is offline, and an offline project never connects, not even to"
                            + " fetch its own settings: follow it from a file, a bundle of its"
                            + " repository or a directory on this machine, 'porelo project follow "
                            + followed.name()
                            + " <file>'");
        }
        try {
            // Read before the reset, because afterwards there is nothing left to see. The rule is
            // stated rather than discovered: the repository wins for what it covers, and a local
            // edit to something reconciled is replaced - but the machine says it replaced it rather
            // than doing it quietly. A file the repository does not cover is untracked, and 'reset
            // --hard' does not touch it.
            final List<String> replaced = locallyChanged(runner, clone);

            // The file is judged as the commit holds it, before the working tree moves: the clone's
            // file is the one in force, so a commit refused here after the reset stayed in force
            // under a record naming the commit before it.
            final Path file = clone.resolve("project.yml");
            String shape = "";
            try {
                final org.fuin.porelo.core.project.Project read =
                        readAt(runner, clone, commit, file);
                if (!read.repositories().isEmpty()) {
                    // Said before following, so the first refused start is not how a person finds
                    // out.
                    shape =
                            "works in "
                                    + String.join(", ", read.workRepositoryNames())
                                    + "; '"
                                    + read.name()
                                    + "' holds the project file and is not worked in";
                }
                if (!read.name().equals(followed.name())) {
                    // Following it as one name while the file calls itself another would give the
                    // machine a project whose containers, images and mirrors are all named after
                    // the file - and a name in 'project following' that resolves to none of them.
                    return new Result(
                            Outcome.UNUSABLE,
                            followed.commit(),
                            "this is followed as '"
                                    + followed.name()
                                    + "' and the file calls"
                                    + " itself '"
                                    + read.name()
                                    + "'. Follow it under its own"
                                    + " name, or change the name in the file.");
                }
                // The rest of what a project file has to survive on THIS machine: an egress set it
                // does not have. Checked here because here is where the file arrives - the
                // alternative was a call that checked before anything was written, which is a
                // second place the same question could be answered differently.
                //
                // Every other check is the reader's: the name's shape, the security class, the base
                // image, and an online project without an upstream.
                for (final org.fuin.porelo.core.project.Repository repository :
                        read.allRepositories()) {
                    context.paths()
                            .egress()
                            .egressSets()
                            .origins(read.egressFor(repository).sets());
                }
            } catch (final ProjectException ex) {
                // Accepted and still not a project. Its own outcome, because "somebody committed
                // something broken" is a different problem from "somebody signed nothing", and only
                // one of them is about trust.
                return new Result(
                        Outcome.UNUSABLE,
                        followed.commit(),
                        file.getFileName() + " does not read as a project: " + ex.getMessage());
            } catch (final org.fuin.porelo.shield.EgressSetException ex) {
                // Refused here rather than at the first task start, which is minutes later and
                // somewhere else - and would read as a broken build rather than as a project file
                // naming something this machine has never had.
                return new Result(
                        Outcome.UNUSABLE,
                        followed.commit(),
                        file.getFileName()
                                + " names an egress set this machine does not have: "
                                + ex.getMessage());
            }

            // Only now does the working tree move. Everything above this line is reversible and
            // changes nothing an operator would notice.
            final CommandResult moved =
                    runner.run(
                            Command.of(
                                    "git",
                                    "-C",
                                    clone.toString(),
                                    "reset",
                                    "--hard",
                                    "--quiet",
                                    commit));
            if (!moved.successful()) {
                return new Result(
                        Outcome.FAILED,
                        followed.commit(),
                        "have "
                                + commit
                                + " and could not check it out: "
                                + moved.standardError().strip());
            }
            final String edits =
                    replaced.isEmpty()
                            ? ""
                            : "replaced a local edit to " + String.join(", ", replaced);
            return new Result(
                    Outcome.APPLIED,
                    commit,
                    edits.isEmpty() || shape.isEmpty() ? edits + shape : edits + "; " + shape);
        } catch (final RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Reads the project file a commit holds, without checking it out.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @param commit The commit.
     * @param file Where the file lies once the commit is checked out, named in messages.
     * @return The project.
     * @throws ProjectException If the commit holds no project file, or one that does not read.
     */
    private static org.fuin.porelo.core.project.Project readAt(
            final CommandRunner runner, final Path clone, final String commit, final Path file) {
        final CommandResult shown =
                runner.run(
                        Command.of("git", "-C", clone.toString(), "show", commit + ":project.yml"));
        if (!shown.successful()) {
            throw new ProjectException(
                    "no project file at "
                            + file
                            + " in "
                            + commit
                            + ": "
                            + shown.standardError().strip());
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("porelo-project-read-");
            for (final String path : ProtectedFiles.at(runner, clone, commit)) {
                final CommandResult mode =
                        runner.run(
                                Command.of(
                                        "git",
                                        "-C",
                                        clone.toString(),
                                        "ls-tree",
                                        commit,
                                        "--",
                                        ":(literal)" + path));
                if (mode.successful()
                        && mode.standardOutput().isEmpty()
                        && (path.equals("machine-signers")
                                || path.equals("access.yml")
                                || path.equals("porelo.yml")
                                || Path.of(path)
                                        .getFileName()
                                        .toString()
                                        .equals(".gitattributes"))) {
                    continue;
                }
                if (!mode.successful()
                        || !(mode.standardOutput().startsWith("100644 ")
                                || mode.standardOutput().startsWith("100755 "))) {
                    throw new ProjectException(
                            "protected input must be a regular committed file: " + path);
                }
                final CommandResult snippet =
                        runner.run(
                                Command.of(
                                        "git",
                                        "-C",
                                        clone.toString(),
                                        "show",
                                        commit + ":" + path));
                if (!snippet.successful()) {
                    throw new ProjectException(
                            "cannot read image snippet " + path + " at " + commit);
                }
                final Path target = scratch.resolve(path);
                Files.createDirectories(target.getParent());
                Files.writeString(target, snippet.standardOutput());
            }
            return ProjectReader.read(
                    new java.io.StringReader(shown.standardOutput()),
                    scratch.resolve("project.yml").toString());
        } catch (final IOException ex) {
            throw new ProjectException("cannot read " + file + " at " + commit, ex);
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
        }
    }

    /**
     * Returns the commit a fetch brought, without asking whether anybody signed it.
     *
     * <p>Only the unverified path needs this: the gate reads the commit on its way to a verdict,
     * and a machine following without an anchor never asks for one.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @return The commit, or "" when it cannot be read.
     */
    private static String headOf(final CommandRunner runner, final Path clone) {
        final CommandResult found =
                runner.run(Command.of("git", "-C", clone.toString(), "rev-parse", "FETCH_HEAD"));
        return found.successful() ? found.standardOutput().strip() : "";
    }

    /**
     * Returns the tracked files a reset will overwrite, so the machine can say it did.
     *
     * <p>Tracked only: an untracked file is one the repository does not cover, and {@code reset
     * --hard} leaves it where it is. Reporting it as replaced would be a lie in the direction that
     * makes somebody look for work that is still there.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @return Paths, empty when nothing local was changed - or when git cannot be asked, because a
     *     reconciliation must not fail over a question about what it is about to replace.
     */
    private static List<String> locallyChanged(final CommandRunner runner, final Path clone) {
        final CommandResult status =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "status",
                                "--porcelain",
                                "--untracked-files=no"));
        if (!status.successful()) {
            return List.of();
        }
        return status.standardOutput()
                .lines()
                .map(String::strip)
                .filter(line -> line.length() > 3)
                .map(line -> line.substring(2).strip())
                .sorted()
                .toList();
    }

    /**
     * Says whether one commit descends from another.
     *
     * @param clone The repository.
     * @param older The commit in force.
     * @param newer The fetched tip.
     * @return {@code true} when moving from one to the other only goes forwards. An older commit
     *     this clone no longer has counts as not descending - the safe answer.
     */
    private boolean descends(final Path clone, final String older, final String newer) {
        try {
            return context.runner()
                    .run(
                            Command.of(
                                    "git",
                                    "-C",
                                    clone.toString(),
                                    "merge-base",
                                    "--is-ancestor",
                                    older,
                                    newer))
                    .successful();
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /**
     * Records the attempt against the project, so the state survives a restart.
     *
     * @param followed What was known before.
     * @param result What the attempt did.
     * @return What is now known.
     */
    public static FollowedProjects.Followed after(
            final FollowedProjects.Followed followed, final Result result) {
        // 'unverified' is carried, not defaulted. It was dropped here: a follow written with the
        // flag was rewritten by this method one line later, so a project taken WITHOUT an anchor
        // reported itself as one whose signature had been checked - the single field that tells a
        // person that whoever can push there decides what this machine runs. Found through the
        // interface, whose dialog shows it on every project.
        return new FollowedProjects.Followed(
                followed.name(),
                followed.url(),
                result.commit(),
                Instant.now().toString(),
                result.outcome().name(),
                result.detail(),
                result.refused(),
                result.signer(),
                followed.unverified(),
                result.accessCommit(),
                result.accessOutcome(),
                result.refusedFile(),
                result.accessRefused(),
                result.accessRefusedFile());
    }
}
