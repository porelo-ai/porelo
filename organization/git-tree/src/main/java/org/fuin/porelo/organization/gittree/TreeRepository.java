package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * An owned bare clone in a scratch directory: only objects come over, so nothing of a checkout's or
 * the caller's configuration, hooks or environment changes what it reads.
 */
final class TreeRepository implements AutoCloseable {

    /**
     * What git, and the forges behind it, say of a repository that is not there. Anything else that
     * fails is unreachable: never read as no.
     */
    private static final java.util.regex.Pattern NOT_THERE =
            java.util.regex.Pattern.compile(
                    "(?i)repository not found|repository does not exist"
                            + "|does not appear to be a git repository");

    /** Only what fetches an organization's repository; {@code ext::} would run a program. */
    /** What git says of a repository that lacks the branch: it was reached, and holds nothing. */
    private static final Pattern NO_BRANCH = Pattern.compile("couldn't find remote ref");

    /** What git and ssh-keygen say when a signature could not be made, as against anything else. */
    private static final Pattern NOT_SIGNED =
            Pattern.compile(
                    "(?i)failed to sign|signing failed|agent|public key|couldn't sign|load key|no such"
                            + " identity");

    private static final String PROTOCOLS = "file:ssh:https";

    private static final String BRANCH = "refs/heads/main";

    private static final int LIMIT = 1024 * 1024;

    private static final String CHANGE_ID = "Porelo-Change-Id";

    /** What the command runner writes where it dropped the start of an output. */
    private static final Pattern CUT = Pattern.compile("\\[[0-9]+ bytes cut before this\\]\\n");

    private final Path scratch;

    private final Path repository;

    private final CommandRunner runner;

    private TreeRepository(final Path scratch, final CommandRunner runner) {
        this.scratch = scratch;
        this.repository = scratch.resolve("organization.git");
        this.runner = runner;
    }

    /**
     * Fetches the repository's main branch into a new scratch clone, removed again on failure.
     *
     * @param address Where the repository is.
     * @param runner How git runs.
     * @param access What the fetch may use, lent for its length.
     * @return The clone.
     * @throws IOException When it cannot be fetched.
     */
    static TreeRepository fetch(
            final String address, final CommandRunner runner, final FetchAccess access)
            throws IOException {
        final TreeRepository clone =
                new TreeRepository(Files.createTempDirectory("porelo-organization-"), runner);
        try {
            clone.fill(address, access);
            return clone;
        } catch (IOException | RuntimeException ex) {
            clone.close();
            throw ex;
        }
    }

    /**
     * Makes a scratch clone holding nothing, for the founding commit of an empty repository, or
     * says that the repository holds its branch already.
     *
     * @param address Where the repository is.
     * @param runner How git runs.
     * @param access What the asking may use, lent for its length.
     * @return The clone, or empty when the repository holds anything.
     * @throws IOException When the repository cannot be asked.
     */
    static Optional<TreeRepository> founding(
            final String address, final CommandRunner runner, final FetchAccess access)
            throws IOException {
        final TreeRepository clone =
                new TreeRepository(Files.createTempDirectory("porelo-organization-"), runner);
        try {
            final Path template = Files.createDirectory(clone.scratch.resolve("empty-template"));
            if (!clone.git(
                            "init",
                            "-q",
                            "--bare",
                            "--template=" + template,
                            clone.repository.toString())
                    .successful()) {
                throw new IOException("cannot make the organization's clone");
            }
            try (FetchAccess.Lease lease = access.lend(address)) {
                final List<String> arguments = new ArrayList<>(lease.arguments());
                arguments.addAll(List.of("ls-remote", "--", address));
                final CommandResult asked =
                        clone.git(lease.environment(), arguments.toArray(String[]::new));
                if (NOT_THERE.matcher(asked.standardError()).find()) {
                    throw new Refused(
                            Refused.NO_REPOSITORY,
                            "the organization's repository does not exist: make it on the forge"
                                    + " first, empty",
                            null);
                }
                if (!asked.successful()) {
                    throw new GitTreeStore.Unreachable("cannot read the organization's repository");
                }
                if (!asked.standardOutput().isBlank()) {
                    clone.close();
                    return Optional.empty();
                }
            }
            return Optional.of(clone);
        } catch (IOException | RuntimeException ex) {
            clone.close();
            throw ex;
        }
    }

    private void fill(final String address, final FetchAccess access) throws IOException {
        final Path template = Files.createDirectory(scratch.resolve("empty-template"));
        final CommandResult init =
                git("init", "-q", "--bare", "--template=" + template, repository.toString());
        if (!init.successful()) {
            throw new IOException("cannot make the organization's clone");
        }
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of(
                            "fetch",
                            "-q",
                            "--no-tags",
                            "--no-recurse-submodules",
                            "--no-write-fetch-head",
                            "--update-shallow",
                            "--",
                            address,
                            BRANCH + ":" + BRANCH));
            final CommandResult fetched =
                    git(lease.environment(), arguments.toArray(String[]::new));
            if (!fetched.successful() && NO_BRANCH.matcher(fetched.standardError()).find()) {
                throw new GitTreeStore.Empty("the organization's repository holds nothing yet");
            }
            if (!fetched.successful()) {
                throw new GitTreeStore.Unreachable(
                        "cannot read the organization's repository: "
                                + RegistryText.printable(fetched.standardError().strip()));
            }
        }
    }

    /**
     * Returns the first-parent line, oldest first, each with its parent.
     *
     * @param revision Where the line ends, or {@code null} for the branch's tip.
     * @return Each commit and its first parent, "" for the founding one.
     * @throws CardException When the history is shallow, and so proves nothing, or the revision is
     *     not on the line.
     */
    List<Map.Entry<String, String>> line(final @Nullable String revision) {
        if (!require("rev-parse", "--is-shallow-repository").strip().equals("false")) {
            throw new CardException("a shallow organization cannot prove its history");
        }
        final List<Map.Entry<String, String>> line = new ArrayList<>();
        for (final String entry :
                require("rev-list", "--parents", "--first-parent", "--reverse", BRANCH)
                        .lines()
                        .toList()) {
            final String[] ancestry = entry.split(" ");
            line.add(Map.entry(ancestry[0], ancestry.length > 1 ? ancestry[1] : ""));
            if (ancestry[0].equals(revision)) {
                return List.copyOf(line);
            }
        }
        if (revision != null) {
            throw new CardException("the revision asked for is not on the organization's line");
        }
        return List.copyOf(line);
    }

    /**
     * Returns the paths a commit changed against its first parent.
     *
     * @param commit The commit.
     * @param parent Its first parent, or "" for the founding one.
     * @return The paths.
     */
    List<String> changed(final String commit, final String parent) {
        final List<String> diff =
                new ArrayList<>(
                        List.of(
                                "diff-tree",
                                "--no-commit-id",
                                "--no-renames",
                                "--name-only",
                                "-r",
                                "-z"));
        if (parent.isEmpty()) {
            diff.add("--root");
        }
        diff.add("--end-of-options");
        if (!parent.isEmpty()) {
            diff.add(parent);
        }
        diff.add(commit);
        // Splitting nothing answers one empty name, which is no file changed.
        return java.util.Arrays.stream(require(diff.toArray(String[]::new)).split("\u0000"))
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Returns a commit's date, as its signer wrote it.
     *
     * @param commit The commit.
     * @return The date, ISO 8601.
     */
    String dated(final String commit) {
        return require("show", "-s", "--format=%cI", "--end-of-options", commit).strip();
    }

    /**
     * Returns a commit's subject.
     *
     * @param commit The commit.
     * @return Its first line.
     */
    String subject(final String commit) {
        return require("show", "-s", "--format=%s", "--end-of-options", commit).strip();
    }

    /**
     * Returns a commit's subject that names what was asked, so that an id asked again with anything
     * else is seen. A card or another long text goes in by its digest.
     *
     * @param subject What the change does.
     * @param asked What it was asked with.
     * @return The subject.
     */
    static String asked(final String subject, final @Nullable String... asked) {
        final StringBuilder named = new StringBuilder(subject).append(':');
        for (final String each : asked) {
            named.append(' ')
                    .append(
                            each != null && each.matches("[\\x21-\\x7e]{1,64}")
                                    ? each
                                    : digest(String.valueOf(each)));
        }
        return named.toString();
    }

    static String digest(final String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Returns the blob a regular file at this path is.
     *
     * @param commit The commit.
     * @param path The path.
     * @return The blob, or empty when the commit has no such path.
     * @throws CardException When the path holds anything but a regular file.
     */
    Optional<String> object(final String commit, final String path) {
        final String entry = require("ls-tree", "-z", "--end-of-options", commit, "--", path);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new CardException("expected a regular file");
        }
        return Optional.of(entry.substring(entry.indexOf("blob ") + 5, entry.indexOf('\t')));
    }

    /**
     * Returns a blob's text.
     *
     * @param object The blob.
     * @return Its text.
     * @throws CardException When it is too large, or no UTF-8 text.
     */
    String text(final String object) {
        final long size =
                Long.parseLong(require("cat-file", "-s", "--end-of-options", object).strip());
        if (size > LIMIT) {
            throw new CardException("the file exceeds 1 MiB");
        }
        final String text = require("cat-file", "blob", "--end-of-options", object);
        if (text.indexOf('�') >= 0) {
            throw new CardException("the file is not UTF-8 text");
        }
        return text;
    }

    /**
     * Returns the key that verifiably signed a commit.
     *
     * @param commit The commit.
     * @return The key, {@code ssh-ed25519 AAAA...}.
     * @throws CardException When it carries no signature that verifies against the key it names.
     * @throws IOException When the allowed-signers file cannot be written.
     */
    String signer(final String commit) throws IOException {
        final String key = claimedKey(require("cat-file", "commit", "--end-of-options", commit));
        final Path signers = scratch.resolve("allowed-signers");
        Files.writeString(
                signers, "signer namespaces=\"git\" " + key + "\n", StandardCharsets.UTF_8);
        final boolean verified =
                git(
                                "-c",
                                "gpg.ssh.allowedSignersFile=" + signers,
                                "verify-commit",
                                "--end-of-options",
                                commit)
                        .successful();
        if (!verified) {
            throw new CardException(
                    "the commit's signature does not verify against "
                            + PersonCard.fingerprint(key));
        }
        return key;
    }

    private static String claimedKey(final String raw) {
        final StringBuilder signature = new StringBuilder();
        boolean collecting = false;
        for (final String line : raw.lines().toList()) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("gpgsig ")) {
                collecting = true;
                signature.append(line.substring(7)).append('\n');
            } else if (collecting && line.startsWith(" ")) {
                signature.append(line.substring(1)).append('\n');
            } else {
                collecting = false;
            }
        }
        final byte[] blob = SshSignature.signerOf(signature.toString());
        if (blob == null) {
            throw new CardException("the commit has no readable SSH signature");
        }
        final String key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
        PersonCard.fingerprint(key);
        return key;
    }

    /**
     * Returns the commit on the first-parent line that a person's key signed under this id.
     *
     * <p>Only the acting key counts: anybody with write access can write the words of an id into a
     * commit, and a change asked for again must not be answered by theirs.
     *
     * @param id The change's id.
     * @param fingerprint The acting key's fingerprint.
     * @return The commit, or empty when this key made no change under the id.
     * @throws IOException When a signature cannot be read.
     */
    Optional<String> changeNamed(final String id, final String fingerprint) throws IOException {
        // A change of two commits carries its id on both: it stands at the newer one.
        String newest = null;
        for (final String row :
                require(
                                "log",
                                "--first-parent",
                                "--format=%H %(trailers:key="
                                        + CHANGE_ID
                                        + ",valueonly,separator=,)",
                                BRANCH)
                        .lines()
                        .toList()) {
            final String[] fields = row.split(" ", 2);
            if (fields.length < 2 || !List.of(fields[1].split(",")).contains(id)) {
                newest = null;
                continue;
            }
            if (newest == null) {
                newest = fields[0];
            }
            if (signedBy(fields[0], fingerprint)) {
                return Optional.of(newest);
            }
        }
        return Optional.empty();
    }

    boolean signedBy(final String commit, final String fingerprint) throws IOException {
        try {
            return PersonCard.fingerprint(signer(commit)).equals(fingerprint);
        } catch (CardException ex) {
            return false;
        }
    }

    /**
     * Makes a signed commit on the branch's tip, in this clone alone.
     *
     * @param parent The tip, or "" for the founding commit.
     * @param files What changes: a path and its new text, or {@code null} to remove it.
     * @param subject The commit's subject.
     * @param id The change's id.
     * @param signing Whose agent signs.
     * @param key The key that signs.
     * @param login Who it is written as: the signature, not this, is what a reader checks.
     * @return The commit, which the branch now names.
     * @throws IOException When a file cannot be handed to git.
     * @throws CardException When git cannot make it, an agent refusing to sign among the causes.
     */
    String commit(
            final String parent,
            final Map<String, @Nullable String> files,
            final String subject,
            final String id,
            final CommitSigning signing,
            final String key,
            final String login)
            throws IOException {
        final Map<String, String> index =
                Map.of("GIT_INDEX_FILE", scratch.resolve("index").toString());
        if (!parent.isEmpty()) {
            written(git(index, "read-tree", parent));
        }
        final Path text = scratch.resolve("file");
        for (final Map.Entry<String, @Nullable String> file : files.entrySet()) {
            if (file.getValue() == null) {
                // Git asks for a work tree here though it touches none; this one stays empty.
                final Map<String, String> removing = new java.util.HashMap<>(index);
                removing.put("GIT_WORK_TREE", scratch.toString());
                written(git(removing, "update-index", "--force-remove", "--", file.getKey()));
                continue;
            }
            Files.writeString(text, file.getValue(), StandardCharsets.UTF_8);
            final String blob =
                    written(git("hash-object", "-w", "--no-filters", "--", text.toString()))
                            .strip();
            written(
                    git(
                            index,
                            "update-index",
                            "--add",
                            "--cacheinfo",
                            "100644," + blob + "," + file.getKey()));
        }
        final String tree = written(git(index, "write-tree")).strip();
        final Map<String, String> identity = new java.util.HashMap<>(signing.environment());
        final String email = login + "@users.noreply.invalid";
        identity.put("GIT_AUTHOR_NAME", login);
        identity.put("GIT_AUTHOR_EMAIL", email);
        identity.put("GIT_COMMITTER_NAME", login);
        identity.put("GIT_COMMITTER_EMAIL", email);
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "-c",
                                "user.signingkey=" + signing.signingKey(key),
                                "commit-tree",
                                "-S"));
        if (!parent.isEmpty()) {
            arguments.addAll(List.of("-p", parent));
        }
        arguments.addAll(List.of("-m", subject + "\n\n" + CHANGE_ID + ": " + id + "\n", tree));
        final CommandResult made = git(identity, arguments.toArray(String[]::new));
        if (!made.successful() && NOT_SIGNED.matcher(made.standardError()).find()) {
            throw new Refused(
                    Refused.NO_KEY,
                    "the ssh-agent did not sign with " + PersonCard.fingerprint(key),
                    null);
        }
        final String commit = written(made).strip();
        if (parent.isEmpty()) {
            written(git("update-ref", BRANCH, commit));
        } else {
            written(git("update-ref", BRANCH, commit, parent));
        }
        return commit;
    }

    /**
     * Pushes the branch to the repository, as a plain step forward: never forced.
     *
     * @param address Where the repository is.
     * @param access What the push may use, lent for its length.
     * @return Whether it took it. Why not is not told: git's words may carry the address's secrets.
     * @throws IOException When the access cannot be lent.
     */
    boolean push(final String address, final FetchAccess access) throws IOException {
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of("push", "-q", "--no-verify", "--", address, BRANCH + ":" + BRANCH));
            return git(lease.environment(), arguments.toArray(String[]::new)).successful();
        }
    }

    /**
     * Returns the branch's tip.
     *
     * @return The commit.
     */
    String tip() {
        return require("rev-parse", "--verify", BRANCH).strip();
    }

    private static String written(final CommandResult result) {
        if (!result.successful()) {
            throw new CardException(
                    "cannot make the change: "
                            + RegistryText.printable(result.standardError().strip()));
        }
        return result.standardOutput();
    }

    private String require(final String... arguments) {
        final CommandResult result = git(arguments);
        if (!result.successful()) {
            throw new CardException(
                    "cannot read the organization's history: "
                            + RegistryText.printable(result.standardError().strip()));
        }
        final String output = result.standardOutput();
        if (CUT.matcher(output).lookingAt()) {
            // Past 16 MiB of a command's output its start is gone: paths or commits would go
            // unjudged, and the marker would be read as a commit.
            throw new OrganizationQuestions.Refused(
                    OrganizationQuestions.Refused.CANNOT_SAY,
                    "the organization's history is too long to read whole",
                    null);
        }
        return output;
    }

    private CommandResult git(final String... arguments) {
        return git(Map.of(), arguments);
    }

    private CommandResult git(final Map<String, String> variables, final String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.sshCommand=ssh",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "http.followRedirects=false",
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.program=ssh-keygen",
                                "-c",
                                "gpg.ssh.revocationFile=/dev/null"));
        if (Files.isDirectory(repository)) {
            all.add("--git-dir=" + repository);
        }
        all.addAll(List.of(arguments));
        return runner.run(
                Command.of(all)
                        .in(scratch)
                        .withEnvironment(variables)
                        .withChosenEnvironment(
                                Map.of(
                                        "PATH",
                                        System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                                        "LC_ALL",
                                        "C",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1",
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_ATTR_NOSYSTEM",
                                        "1",
                                        "GIT_NO_REPLACE_OBJECTS",
                                        "1",
                                        "GIT_NO_LAZY_FETCH",
                                        "1",
                                        "GIT_ALLOW_PROTOCOL",
                                        PROTOCOLS,
                                        "GIT_TERMINAL_PROMPT",
                                        "0")));
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(scratch)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
