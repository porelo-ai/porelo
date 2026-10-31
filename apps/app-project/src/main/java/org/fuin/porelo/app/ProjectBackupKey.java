package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.ProjectSigners;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * A followed project's side of {@code identity backup-key}: the backup key filed among the
 * project's signers, and used to replace the key it backs.
 *
 * <p>As {@code project access} writes its access file, this fetches the project's repository head
 * into a scratch directory, checks its history since the commit in force here as the follow checks
 * it, changes {@code project.yml} there as text, checks that the follow would take the commit made,
 * and pushes it onto the head it read, leased. Nothing of the clone is kept, and no git it runs
 * reads the caller's git configuration, hooks or {@code GIT_*} environment. The filing commit is
 * signed through the agent by the one signer's key; the replacing one with the backup key's file,
 * through {@code ssh-keygen}, which asks its passphrase.
 */
final class ProjectBackupKey implements IdentityBackupKeyCommand.Projects {

    private static final String FILE = "project.yml";

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How far back an id is looked for. */
    private static final int SEARCHED = 1000;

    /** The same transports a follow reaches a repository by, and no redirect. */
    private static final List<String> TRANSPORT =
            List.of(
                    "-c",
                    "http.followRedirects=false",
                    "-c",
                    "protocol.allow=never",
                    "-c",
                    "protocol.file.allow=always",
                    "-c",
                    "protocol.git.allow=always",
                    "-c",
                    "protocol.http.allow=always",
                    "-c",
                    "protocol.https.allow=always",
                    "-c",
                    "protocol.ssh.allow=always");

    /** The caller's environment, which every program run here starts from before it is chosen. */
    private final Supplier<Map<String, String>> callerEnvironment;

    /** Constructor for the binary: the caller's own environment. */
    ProjectBackupKey() {
        this(System::getenv);
    }

    /**
     * Constructor.
     *
     * @param callerEnvironment The caller's environment.
     */
    ProjectBackupKey(final Supplier<Map<String, String>> callerEnvironment) {
        this.callerEnvironment = callerEnvironment;
    }

    /** The project's repository fetched into a scratch directory, and where it was. */
    private record Fetched(
            PoreloContext context,
            String project,
            FollowedProjects.Followed followed,
            Path repository,
            String head,
            String branch) {}

    @Override
    public IdentityBackupKeyCommand.Filed make(
            final PoreloContext machine,
            final String project,
            final PersonCard own,
            final IdentityBackupKeyCommand.KeyMaker keys,
            final @Nullable String base,
            final @Nullable String id)
            throws IdentityChange.Refused, IOException {
        final Path scratch = Files.createTempDirectory("porelo-backup-key-");
        try {
            final Fetched fetched = fetch(machine, project, scratch);
            final String digest = digest("make", project, own.fingerprint());
            final String made = madeBefore(fetched, base, id, digest);
            final String backs = own.fingerprint();
            if (made != null) {
                final String filed = filedBy(fetched, made, own);
                if (filed.isEmpty()) {
                    throw new IdentityChange.Refused(
                            65,
                            null,
                            "--id " + id + " names " + made + ", which files no backup key");
                }
                return new IdentityBackupKeyCommand.Filed(filed, made);
            }
            moved(fetched, base);
            final String text = text(fetched, fetched.head());
            final List<ProjectSigners.Signer> entries = entries(text);
            if (ProjectSigners.backups(entries).stream()
                    .anyMatch(backup -> backup.backs().equals(backs))) {
                throw new IdentityChange.Refused(
                        65,
                        "backup-exists",
                        "the signers of " + project + " name a backup key for " + backs);
            }
            final String sole = ProjectSigners.soleWithoutBackup(entries);
            if (sole == null || !ProjectSigners.fingerprint(sole).equals(backs)) {
                throw new IdentityChange.Refused(
                        65,
                        "not-sole",
                        backs
                                + " is not the one signer of "
                                + project
                                + "; another signer may replace its key");
            }
            held(machine, project, sole);
            final String key = keys.make();
            final String changed;
            try {
                changed =
                        ProjectSigners.withBackup(
                                text,
                                key,
                                backs,
                                java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
                ProjectReader.read(new java.io.StringReader(changed), FILE);
            } catch (ProjectException ex) {
                throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
            }
            final String commit =
                    commit(
                            fetched,
                            changed,
                            "Signers: file a backup key for " + backs,
                            "key::" + sole,
                            true,
                            id,
                            digest);
            followed(fetched, commit, null);
            push(fetched, commit);
            return new IdentityBackupKeyCommand.Filed(ProjectSigners.fingerprint(key), commit);
        } finally {
            deleteTree(scratch);
        }
    }

    @Override
    public IdentityBackupKeyCommand.Replaced replace(
            final PoreloContext machine,
            final String project,
            final Path file,
            final String backupKey,
            final PersonCard next,
            final @Nullable String base,
            final String id)
            throws IdentityChange.Refused, IOException {
        final Path scratch = Files.createTempDirectory("porelo-backup-key-");
        try {
            final Fetched fetched = fetch(machine, project, scratch);
            final String digest =
                    digest(
                            "replace",
                            project,
                            PersonCard.fingerprint(backupKey),
                            next.fingerprint());
            final String made = madeBefore(fetched, base, id, digest);
            if (made != null) {
                final String parent = parent(fetched, made);
                final String backed = backed(fetched, parent, backupKey);
                if (backed == null
                        || ProjectSigners.fingerprint(backed).equals(next.fingerprint())
                        || !signedBy(fetched, made, backupKey)
                        || !text(fetched, made)
                                .equals(
                                        replacing(
                                                text(fetched, parent),
                                                backed,
                                                next.key(),
                                                backupKey))) {
                    throw new IdentityChange.Refused(
                            65,
                            null,
                            "--id "
                                    + id
                                    + " names "
                                    + made
                                    + ", which replaces no key by this card's");
                }
                return new IdentityBackupKeyCommand.Replaced(
                        ProjectSigners.fingerprint(backed), next.fingerprint(), made);
            }
            moved(fetched, base);
            final String text = text(fetched, fetched.head());
            final String old = backed(fetched, fetched.head(), backupKey);
            if (old == null) {
                throw new IdentityChange.Refused(
                        65,
                        "not-the-backup",
                        "the key in "
                                + file
                                + " is no backup key the signers of "
                                + project
                                + " name");
            }
            final String changed;
            try {
                changed = ProjectSigners.replacing(text, old, next.key(), backupKey);
            } catch (ProjectException ex) {
                throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
            }
            final String why;
            try {
                why = ProjectSigners.backedOnly(text, changed, backupKey);
            } catch (ProjectException ex) {
                throw new IdentityChange.Refused(
                        65, "backup-only-own", String.valueOf(ex.getMessage()));
            }
            if (why != null) {
                throw new IdentityChange.Refused(
                        65, "backup-only-own", "the change " + why + "; a backup key may not");
            }
            final String commit =
                    commit(
                            fetched,
                            changed,
                            "Signers: replace "
                                    + ProjectSigners.fingerprint(old)
                                    + " by "
                                    + next.fingerprint()
                                    + " with its backup key",
                            file.toAbsolutePath().toString(),
                            false,
                            id,
                            digest);
            followed(fetched, commit, "backup-only-own");
            push(fetched, commit);
            return new IdentityBackupKeyCommand.Replaced(
                    ProjectSigners.fingerprint(old), next.fingerprint(), commit);
        } finally {
            deleteTree(scratch);
        }
    }

    /** The key a backup key backs at a commit, or {@code null} when it is no backup key there. */
    private @Nullable String backed(
            final Fetched fetched, final String commit, final String backupKey)
            throws IdentityChange.Refused {
        final List<ProjectSigners.Signer> entries = entries(text(fetched, commit));
        for (final ProjectSigners.Signer backup : ProjectSigners.backups(entries)) {
            if (backup.key().equals(backupKey)) {
                return ProjectSigners.keys(entries).stream()
                        .filter(key -> ProjectSigners.fingerprint(key).equals(backup.backs()))
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    /**
     * The fingerprint of the backup key an earlier commit filed for the person's own key, or ""
     * when it is not that change: signed by that key, onto a parent naming no backup key for it,
     * and adding the backup key's entry alone. A commit at or before the first follow was never
     * checked, so its trailers alone say nothing.
     */
    private String filedBy(final Fetched fetched, final String made, final PersonCard own)
            throws IdentityChange.Refused {
        final String parent = parent(fetched, made);
        final String backs = own.fingerprint();
        if (!backupOf(fetched, parent, backs).isEmpty() || !signedBy(fetched, made, own.key())) {
            return "";
        }
        final String before = text(fetched, parent);
        for (final ProjectSigners.Signer backup :
                ProjectSigners.backups(entries(text(fetched, made)))) {
            if (backup.backs().equals(backs)
                    && text(fetched, made)
                            .equals(
                                    ProjectSigners.withBackup(
                                            before, backup.key(), backs, backup.since()))) {
                return backup.fingerprint();
            }
        }
        return "";
    }

    /** The project file with the key replaced, or "" when it cannot be replaced as text. */
    private static String replacing(
            final String text, final String old, final String next, final String backupKey) {
        try {
            return ProjectSigners.replacing(text, old, next, backupKey);
        } catch (final ProjectException ex) {
            return "";
        }
    }

    /** Whether a commit's signature verifies with this key alone. */
    private boolean signedBy(final Fetched fetched, final String commit, final String key) {
        final Path krl = fetched.context().paths().projects().revocations();
        return new SignerHandover(
                        command -> run(fetched.context(), command),
                        fetched.context().paths().projects().configurationSigners(),
                        Files.notExists(krl, LinkOption.NOFOLLOW_LINKS) ? null : krl)
                .verify(fetched.repository(), fetched.project(), List.of(key), commit)
                .mayApply();
    }

    /** The fingerprint of the backup key that backs a key at a commit, or "". */
    private String backupOf(final Fetched fetched, final String commit, final String backs)
            throws IdentityChange.Refused {
        return ProjectSigners.backups(entries(text(fetched, commit))).stream()
                .filter(backup -> backup.backs().equals(backs))
                .map(ProjectSigners.Signer::fingerprint)
                .findFirst()
                .orElse("");
    }

    private static List<ProjectSigners.Signer> entries(final String text)
            throws IdentityChange.Refused {
        try {
            final List<ProjectSigners.Signer> entries = ProjectSigners.entries(text, FILE);
            if (entries == null) {
                throw new IdentityChange.Refused(
                        65,
                        "not-sole",
                        FILE + " names no project.signers, so it has no one signer");
            }
            return entries;
        } catch (ProjectException ex) {
            throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Fetches the head of the project's repository, as the follow fetches it, and refuses one the
     * follow would not take from the commit in force here.
     */
    private Fetched fetch(final PoreloContext context, final String project, final Path scratch)
            throws IdentityChange.Refused, IOException {
        final FollowedProjects.@Nullable Followed followed =
                new FollowedProjects(context.paths().projects().followed()).find(project);
        if (followed == null || followed.unverified() || followed.commit().isBlank()) {
            throw new IdentityChange.Refused(
                    66,
                    null,
                    followed == null
                            ? "this machine does not follow a project '" + project + "'"
                            : "'"
                                    + project
                                    + "' is followed unverified or has nothing in force here;"
                                    + " follow it with --signed-by first");
        }
        final Path repository = scratch.resolve("project");
        final Path template = Files.createDirectory(scratch.resolve("template"));
        local(context, scratch, "init", "-q", "--template=" + template, repository.toString());
        final String url = followed.url();
        final CommandResult listed =
                remote(
                        context,
                        project,
                        repository,
                        url,
                        List.of("ls-remote", "--symref", "--end-of-options", url, "HEAD"));
        if (!listed.successful()) {
            throw new IdentityChange.Refused(
                    69, null, "cannot reach " + url + ": " + listed.standardError().strip());
        }
        String branch = null;
        for (final String line : listed.standardOutput().lines().toList()) {
            if (line.startsWith("ref: refs/heads/") && line.endsWith("\tHEAD")) {
                branch = line.substring("ref: refs/heads/".length(), line.length() - 5);
            }
        }
        if (branch == null || !BranchScheme.refFormat(branch)) {
            throw new IdentityChange.Refused(
                    65, null, "the HEAD of " + url + " names no branch to push onto");
        }
        final CommandResult fetched =
                remote(
                        context,
                        project,
                        repository,
                        url,
                        List.of("fetch", "--quiet", "--end-of-options", url, "HEAD"));
        if (!fetched.successful()) {
            throw new IdentityChange.Refused(
                    69, null, "cannot fetch " + url + ": " + fetched.standardError().strip());
        }
        final String head =
                local(context, repository, "rev-parse", "--verify", "FETCH_HEAD^{commit}");
        final Fetched at = new Fetched(context, project, followed, repository, head, branch);
        if (!run(
                        context,
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "merge-base",
                                "--is-ancestor",
                                followed.commit(),
                                head))
                .successful()) {
            throw new IdentityChange.Refused(
                    65,
                    "base-moved",
                    "the head of "
                            + url
                            + ", "
                            + head
                            + ", does not descend from "
                            + followed.commit()
                            + ", which is in force here");
        }
        followed(at, head, null);
        return at;
    }

    /** Refuses a head another than the commit the change is made against. */
    private static void moved(final Fetched fetched, final @Nullable String base)
            throws IdentityChange.Refused {
        final String against = base != null ? base : fetched.followed().commit();
        if (!fetched.head().equals(against)) {
            throw new IdentityChange.Refused(
                    65,
                    "base-moved",
                    "the project's repository is at "
                            + fetched.head()
                            + ", and the change was made against "
                            + (base != null ? "--base " : "the commit in force, ")
                            + against
                            + "; read it again and ask anew");
        }
    }

    /** Refuses a commit the follow would not take from the commit in force here. */
    private void followed(final Fetched fetched, final String commit, final @Nullable String reason)
            throws IdentityChange.Refused {
        final Path krl = fetched.context().paths().projects().revocations();
        final SignerHandover.Handed handed =
                new SignerHandover(
                                command -> run(fetched.context(), command),
                                fetched.context().paths().projects().configurationSigners(),
                                Files.notExists(krl, LinkOption.NOFOLLOW_LINKS) ? null : krl,
                                ignored -> {},
                                SignerHandover.revokedByRegistries(fetched.context()))
                        .walk(
                                fetched.repository(),
                                fetched.project(),
                                fetched.followed().commit(),
                                commit);
        if (handed.refused() != null) {
            throw new IdentityChange.Refused(
                    65,
                    reason,
                    "the follow refuses "
                            + commit
                            + ", so nothing was pushed: "
                            + handed.refused().detail());
        }
    }

    /** Refuses unless the agent holds the key that signs the change. */
    private void held(final PoreloContext context, final String project, final String key)
            throws IdentityChange.Refused {
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        final CommandResult listed =
                socket == null || socket.isBlank()
                        ? null
                        : run(context, Command.of("ssh-add", "-L"));
        if (listed != null
                && listed.successful()
                && listed.standardOutput()
                        .lines()
                        .map(line -> line.strip().split("\\s+"))
                        .anyMatch(
                                fields ->
                                        fields.length >= 2
                                                && (fields[0] + " " + fields[1]).equals(key))) {
            return;
        }
        throw new IdentityChange.Refused(
                65,
                "not-signer",
                "the ssh agent does not hold "
                        + ProjectSigners.fingerprint(key)
                        + ", the one signer of "
                        + project);
    }

    /**
     * The commit an earlier call made for this id, when it was this same change on the same base; a
     * change under an id already used for another is refused. The head's history was checked as the
     * follow checks it, so a commit on its first parents counts.
     */
    private @Nullable String madeBefore(
            final Fetched fetched,
            final @Nullable String base,
            final @Nullable String id,
            final String digest)
            throws IdentityChange.Refused {
        if (id == null) {
            return null;
        }
        final String log =
                local(
                        fetched.context(),
                        fetched.repository(),
                        "log",
                        "-z",
                        "--first-parent",
                        "--max-count=" + SEARCHED,
                        "--format=%H%x1F%P%x1F"
                                + "%(trailers:key=Porelo-Change-Id,valueonly,unfold,"
                                + "separator=%x1E)%x1F"
                                + "%(trailers:key=Porelo-Change-Digest,valueonly,unfold,"
                                + "separator=%x1E)",
                        fetched.head(),
                        "--");
        final String place = base != null ? base : fetched.followed().commit();
        for (final String entry : log.split("\0")) {
            final String[] fields = entry.strip().split("\u001F", -1);
            if (fields.length != 4 || !fields[0].matches(COMMIT) || !fields[2].equals(id)) {
                continue;
            }
            final String parent = fields[1].isEmpty() ? "" : fields[1].split(" ")[0];
            if (fields[3].equals(digest)
                    && !parent.isEmpty()
                    && (parent.equals(place) || base == null && taken(fetched, fields[0]))
                    && projectFileAlone(fetched, parent, fields[0])) {
                return fields[0];
            }
            throw new IdentityChange.Refused(
                    65, null, "--id " + id + " names another change: " + fields[0]);
        }
        return null;
    }

    /** Whether a commit is the one in force here or before it, so the follow took it already. */
    private boolean taken(final Fetched fetched, final String commit) {
        return run(
                        fetched.context(),
                        Command.of(
                                "git",
                                "-C",
                                fetched.repository().toString(),
                                "merge-base",
                                "--is-ancestor",
                                commit,
                                fetched.followed().commit()))
                .successful();
    }

    /**
     * Whether a commit changes {@code project.yml} and nothing else: such a commit the follow's
     * walk checked against the keys in force before it, whereas one that changes no protected file
     * it never checks, so its trailers say nothing.
     */
    private boolean projectFileAlone(
            final Fetched fetched, final String parent, final String commit)
            throws IdentityChange.Refused {
        return local(
                        fetched.context(),
                        fetched.repository(),
                        "diff-tree",
                        "--no-renames",
                        "-r",
                        "--name-only",
                        "-z",
                        parent,
                        commit,
                        "--")
                .equals(FILE + "\0");
    }

    private String parent(final Fetched fetched, final String commit)
            throws IdentityChange.Refused {
        return local(
                fetched.context(), fetched.repository(), "rev-parse", "--verify", commit + "^1");
    }

    /** The project file at a commit. */
    private String text(final Fetched fetched, final String commit) throws IdentityChange.Refused {
        final CommandResult shown =
                run(
                        fetched.context(),
                        Command.of(
                                "git",
                                "-C",
                                fetched.repository().toString(),
                                "show",
                                commit + ":" + FILE));
        if (!shown.successful()) {
            throw new IdentityChange.Refused(65, null, "there is no " + FILE + " at " + commit);
        }
        return shown.standardOutput();
    }

    /** Commits the changed project file on the head, signed by the key given. */
    private String commit(
            final Fetched fetched,
            final String text,
            final String subject,
            final String signingKey,
            final boolean agent,
            final @Nullable String id,
            final String digest)
            throws IdentityChange.Refused {
        final PoreloContext context = fetched.context();
        final Path repository = fetched.repository();
        local(context, repository, "read-tree", fetched.head());
        final CommandResult stored =
                run(
                        context,
                        Command.of(
                                        "git",
                                        "-C",
                                        repository.toString(),
                                        "hash-object",
                                        "-w",
                                        "--stdin")
                                .withInput(text));
        if (!stored.successful()) {
            throw new IdentityChange.Refused(70, null, "cannot write " + FILE);
        }
        local(
                context,
                repository,
                "update-index",
                "--cacheinfo",
                "100644," + stored.standardOutput().strip() + "," + FILE);
        final String tree = local(context, repository, "write-tree");
        final String message =
                subject
                        + "\n\n"
                        + (id == null ? "" : "Porelo-Change-Id: " + id + "\n")
                        + "Porelo-Change-Digest: "
                        + digest
                        + "\n";
        final Map<String, String> chosen = chosen(context, agent);
        // No configuration of the caller's is read, so the name is always this one: the signature
        // is what the follow checks.
        final CommandResult committed =
                context.runner()
                        .run(
                                new Command(
                                                List.of(
                                                        "git",
                                                        "-c",
                                                        "core.hooksPath=/dev/null",
                                                        "-c",
                                                        "gpg.format=ssh",
                                                        "-c",
                                                        "gpg.ssh.program=ssh-keygen",
                                                        "-c",
                                                        "user.signingkey=" + signingKey,
                                                        "-c",
                                                        "user.name=Porelo",
                                                        "-c",
                                                        "user.email=porelo@localhost",
                                                        "-C",
                                                        repository.toString(),
                                                        "commit-tree",
                                                        "-S",
                                                        "-p",
                                                        fetched.head(),
                                                        "-F",
                                                        "-",
                                                        tree),
                                                null,
                                                Map.of(),
                                                message,
                                                true)
                                        .withChosenEnvironment(chosen));
        if (!committed.successful()) {
            throw new IdentityChange.Refused(
                    65,
                    agent ? "not-signer" : "wrong-passphrase",
                    (agent
                                    ? "cannot sign through the ssh agent: "
                                    : "the backup key's file did not sign: ")
                            + committed.standardError().strip());
        }
        return committed.standardOutput().strip();
    }

    private void push(final Fetched fetched, final String commit) throws IdentityChange.Refused {
        final String ref = "refs/heads/" + fetched.branch();
        final String url = fetched.followed().url();
        // Leased on the head read: a branch that moved, forward or back, since is not written.
        final CommandResult pushed =
                remote(
                        fetched.context(),
                        fetched.project(),
                        fetched.repository(),
                        url,
                        List.of(
                                "push",
                                "--porcelain",
                                "--force-with-lease=" + ref + ":" + fetched.head(),
                                "--end-of-options",
                                url,
                                commit + ":" + ref));
        if (pushed.successful()) {
            return;
        }
        if (pushed.standardOutput()
                .lines()
                .anyMatch(line -> line.startsWith("!") && line.contains("[rejected]"))) {
            throw new IdentityChange.Refused(
                    65,
                    "base-moved",
                    "the project's repository moved while the change was made against "
                            + fetched.head()
                            + "; read it again and ask anew");
        }
        throw new IdentityChange.Refused(
                69, null, "cannot push to " + url + ": " + pushed.standardError().strip());
    }

    /**
     * A git command that reaches the project's repository, with what a follow lends to reach it.
     */
    private CommandResult remote(
            final PoreloContext context,
            final String project,
            final Path repository,
            final String url,
            final List<String> arguments) {
        try (FollowCredential credential = FollowCredential.open(context, url, project)) {
            final List<String> all = new ArrayList<>(List.of("git"));
            all.addAll(TRANSPORT);
            all.addAll(credential.arguments());
            all.addAll(List.of("-C", repository.toString()));
            all.addAll(arguments);
            return run(
                    context,
                    Command.of(all).withEnvironment(credential.environmentFor(context, url)));
        }
    }

    private String local(final PoreloContext context, final Path repository, final String... args)
            throws IdentityChange.Refused {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repository.toString()));
        all.addAll(List.of(args));
        final CommandResult result = run(context, Command.of(all));
        if (!result.successful()) {
            throw new IdentityChange.Refused(
                    70,
                    null,
                    "git "
                            + args[0]
                            + " failed in the scratch clone: "
                            + result.standardError().strip());
        }
        return result.standardOutput().strip();
    }

    /** The environment a program runs with: the caller's, without git's, and the agent's socket. */
    private Map<String, String> chosen(final PoreloContext context, final boolean agent) {
        final Map<String, String> chosen = new LinkedHashMap<>();
        callerEnvironment
                .get()
                .forEach(
                        (name, value) -> {
                            if (!name.startsWith("GIT_") && !name.equals("SSH_AUTH_SOCK")) {
                                chosen.put(name, value);
                            }
                        });
        chosen.put("GIT_CONFIG_NOSYSTEM", "1");
        chosen.put("GIT_CONFIG_GLOBAL", "/dev/null");
        chosen.put("GIT_NO_REPLACE_OBJECTS", "1");
        chosen.put("GIT_PROTOCOL_FROM_USER", "0");
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        // Signing with the backup key's file asks no agent: the key is in the file alone.
        if (agent && socket != null && !socket.isBlank()) {
            chosen.put("SSH_AUTH_SOCK", socket);
        }
        return chosen;
    }

    private CommandResult run(final PoreloContext context, final Command command) {
        final List<String> arguments = new ArrayList<>(command.arguments());
        if (command.program().equals("git")) {
            arguments.addAll(1, List.of("-c", "core.hooksPath=/dev/null"));
        }
        return context.runner()
                .run(
                        new Command(
                                        arguments,
                                        command.workingDirectory(),
                                        command.environment(),
                                        command.input(),
                                        true)
                                .withChosenEnvironment(chosen(context, true)));
    }

    /** The change as one canonical text, so the same change asked twice has the same digest. */
    private static String digest(final String... parts) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    String.join("\n", parts)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void deleteTree(final Path root) {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (final IOException ex) {
                                    // A scratch directory that will not go is not worth failing.
                                }
                            });
        } catch (final IOException ex) {
            // As above.
        }
    }
}
