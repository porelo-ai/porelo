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
                    "(?i)repository not found|cannot find repository|repository '[^']*' not found|repository does not exist"
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

    static final int LIMIT = 1024 * 1024;

    private static final String CHANGE_ID = "Porelo-Change-Id";

    /**
     * The trailer of a commit that changes no file and signs one anew: it counts as that file's
     * newest change, judged by its signer as any change is.
     */
    private static final String SIGNS_ANEW = "Porelo-Signs-Anew";

    /** What the command runner writes where it dropped the start of an output. */
    private static final Pattern CUT = Pattern.compile("\\[[0-9]+ bytes cut before this\\]\\n");

    private final Path scratch;

    private final Path repository;

    private final CommandRunner runner;

    private String branch = BRANCH;

    /** The git directory of the checkout a borrowing reads, or {@code null} for an own clone. */
    private @Nullable Path borrowed;

    private TreeRepository(final Path scratch, final CommandRunner runner) {
        this(scratch, scratch.resolve("organization.git"), runner);
    }

    private TreeRepository(final Path scratch, final Path repository, final CommandRunner runner) {
        this.scratch = scratch;
        this.repository = repository;
        this.runner = runner;
    }

    /**
     * Reads a repository where it stands, with nothing fetched. Closing it leaves the repository;
     * naming the directory to git means it does not mind who owns it.
     *
     * @param repository The git directory.
     * @param runner How git runs.
     * @return The repository to read.
     * @throws IOException When the scratch directory cannot be made.
     */
    static TreeRepository standing(final Path repository, final CommandRunner runner)
            throws IOException {
        return new TreeRepository(
                Files.createTempDirectory("porelo-organization-"), repository, runner);
    }

    /**
     * Returns the git directory a person's checkout keeps its branches and objects in: the
     * checkout's own, or for a {@code git worktree} the one it shares. Read from the files alone,
     * so that nothing of the checkout's configuration runs.
     *
     * @param checkout The checkout's top directory.
     * @return The git directory.
     * @throws Refused {@code usage} when it is no checkout, a shallow one, or another account's.
     * @throws IOException When it cannot be read.
     */
    static Path checkoutDirectory(final Path checkout) throws IOException {
        try {
            final java.nio.file.attribute.UserPrincipal self =
                    checkout.getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name", ""));
            final Path top = checkout.toRealPath();
            final Path dotGit = top.resolve(".git");
            ours(top, self, checkout);
            ours(dotGit, self, checkout);
            final Path own;
            if (Files.isDirectory(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                own = dotGit;
            } else if (Files.isRegularFile(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.size(dotGit) < 4096) {
                final String line = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
                if (!line.startsWith("gitdir: ")) {
                    throw noCheckout(checkout);
                }
                own = top.resolve(line.substring(8)).toRealPath();
                ours(own, self, checkout);
            } else {
                throw noCheckout(checkout);
            }
            Path common = own;
            final Path named = own.resolve("commondir");
            if (Files.isRegularFile(named, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.size(named) < 4096) {
                common =
                        own.resolve(Files.readString(named, StandardCharsets.UTF_8).strip())
                                .toRealPath();
            }
            ours(common, self, checkout);
            if (!Files.isDirectory(
                    common.resolve("objects"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw noCheckout(checkout);
            }
            if (Files.exists(common.resolve("shallow"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new Refused(
                        Refused.USAGE,
                        "the checkout at " + checkout + " is shallow and cannot prove its history",
                        null);
            }
            return common;
        } catch (java.nio.file.InvalidPathException
                | java.nio.charset.MalformedInputException
                | java.nio.file.NoSuchFileException ex) {
            throw noCheckout(checkout);
        }
    }

    /** Refuses what another account owns: a checkout signs only for whoever keeps it. */
    private static void ours(
            final Path path, final java.nio.file.attribute.UserPrincipal self, final Path checkout)
            throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw noCheckout(checkout);
        }
        if (!Files.getOwner(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(self)) {
            throw new Refused(
                    Refused.USAGE,
                    "the checkout at " + checkout + " belongs to another account",
                    null);
        }
    }

    private static Refused noCheckout(final Path checkout) {
        return new Refused(Refused.USAGE, checkout + " is no git checkout", null);
    }

    /**
     * Reads a person's checkout through a scratch repository that borrows its objects and starts at
     * its {@code main}. Git runs on the scratch repository alone, so the checkout's configuration,
     * hooks and address rewrites have no say, and nothing is written into it until {@link
     * #handBack}.
     *
     * @param checkout The checkout's git directory, from {@link #checkoutDirectory}.
     * @param runner How git runs.
     * @return The repository to read.
     * @throws Refused {@code usage} when the checkout is another account's, {@code refused-state}
     *     when it has no main.
     * @throws IOException When the scratch repository cannot be made.
     */
    static TreeRepository borrowing(final Path checkout, final CommandRunner runner)
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
                throw new IOException("cannot make the scratch repository");
            }
            final Path alternates = clone.repository.resolve("objects/info/alternates");
            Files.createDirectories(alternates.getParent());
            Files.writeString(
                    alternates, checkout.resolve("objects") + "\n", StandardCharsets.UTF_8);
            final CommandResult main =
                    new TreeRepository(clone.scratch, checkout, runner)
                            .git(
                                    "rev-parse",
                                    "--verify",
                                    "-q",
                                    "--end-of-options",
                                    BRANCH + "^{commit}");
            if (!main.successful()) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        "the checkout at " + checkout + " has no main",
                        null);
            }
            clone.require("update-ref", BRANCH, main.standardOutput().strip());
            clone.borrowed = checkout;
            return clone;
        } catch (IOException | RuntimeException ex) {
            clone.close();
            throw ex;
        }
    }

    /**
     * Returns the address the borrowed checkout's {@code origin} names.
     *
     * @return It, or "" when it names none.
     */
    String origin() {
        final CommandResult named =
                new TreeRepository(scratch, borrowed(), runner)
                        .git("config", "--no-includes", "--get-all", "remote.origin.url");
        // Git fetches from the first of several.
        return named.successful()
                ? named.standardOutput().lines().findFirst().orElse("").strip()
                : "";
    }

    /**
     * Puts a commit made here into the borrowed checkout and moves its {@code main} to it, if main
     * still stands where it was read. Only the one object and the one ref are written.
     *
     * @param commit The commit, made here on the checkout's main.
     * @param read Where the checkout's main stood when it was read.
     * @return Whether the checkout's main moved; it did not when the person moved it meanwhile.
     * @throws IOException When the commit cannot be written.
     */
    boolean handBack(final String commit, final String read) throws IOException {
        final Path object = scratch.resolve("commit");
        Files.writeString(
                object,
                require("cat-file", "commit", "--end-of-options", commit),
                StandardCharsets.UTF_8);
        final TreeRepository there = new TreeRepository(scratch, borrowed(), runner);
        // A checkout moved meanwhile gets nothing, not even the object: a fetch brings it.
        final CommandResult standing = there.git("rev-parse", "--verify", "-q", BRANCH);
        if (!standing.successful() || !standing.standardOutput().strip().equals(read)) {
            return false;
        }
        final String written =
                written(
                                there.git(
                                        "hash-object",
                                        "-t",
                                        "commit",
                                        "-w",
                                        "--no-filters",
                                        "--",
                                        object.toString()))
                        .strip();
        if (!written.equals(commit)) {
            throw new IOException("the signed commit did not come over whole");
        }
        // Its reflog says what moved main, as git's own commands do.
        return there.git("update-ref", "-m", "porelo: signed porelo.yml anew", BRANCH, commit, read)
                .successful();
    }

    /**
     * Returns where a repository's {@code main} stands, without fetching it.
     *
     * @param address The repository.
     * @param access What the question may use.
     * @return Its commit, or "" when it has no main.
     * @throws GitTreeStore.Unreachable When it does not answer.
     */
    String mainAt(final String address, final FetchAccess access) throws IOException {
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(List.of("ls-remote", "--", address, BRANCH));
            final CommandResult asked = git(lease.environment(), arguments.toArray(String[]::new));
            if (!asked.successful()) {
                throw GitTreeStore.Unreachable.fromGit(
                        "the repository cannot be reached", asked.standardError());
            }
            return asked.standardOutput()
                    .lines()
                    .map(line -> line.split("\t", 2)[0])
                    .findFirst()
                    .orElse("");
        }
    }

    /**
     * Returns the commits on this main that a commit does not hold, newest first.
     *
     * @param other The commit.
     * @return Them, or empty when the commit is not here at all.
     */
    Optional<List<String>> onlyHere(final String other) {
        if (!git("cat-file", "-e", "--end-of-options", other + "^{commit}").successful()) {
            return Optional.empty();
        }
        return Optional.of(
                require("rev-list", "--end-of-options", branch, "^" + other, "--")
                        .lines()
                        .toList());
    }

    private Path borrowed() {
        final Path checkout = borrowed;
        if (checkout == null) {
            throw new IllegalStateException("no checkout is borrowed");
        }
        return checkout;
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

    /** Fetches the repository's declared default branch, rather than the organization's main. */
    static TreeRepository fetchDefault(
            final String address, final CommandRunner runner, final FetchAccess access)
            throws IOException {
        final TreeRepository clone =
                new TreeRepository(Files.createTempDirectory("porelo-organization-"), runner);
        try {
            try (FetchAccess.Lease lease = access.lend(address)) {
                final List<String> arguments = new ArrayList<>(lease.arguments());
                arguments.addAll(List.of("ls-remote", "--symref", "--", address, "HEAD"));
                final CommandResult head =
                        clone.git(lease.environment(), arguments.toArray(String[]::new));
                final String ref =
                        head.standardOutput()
                                .lines()
                                .filter(
                                        line ->
                                                line.startsWith("ref: refs/heads/")
                                                        && line.endsWith("\tHEAD"))
                                .map(line -> line.substring(5, line.length() - 5))
                                .findFirst()
                                .orElse("");
                if (!head.successful()
                        || ref.isEmpty()
                        || !clone.git("check-ref-format", ref).successful()) {
                    throw new GitTreeStore.Unreachable(
                            "the repository cannot name its default branch");
                }
                clone.branch = ref;
            }
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
                if (notThere(asked.standardError())) {
                    throw new Refused(
                            Refused.NO_REPOSITORY,
                            "the organization's repository does not exist: make it on the forge"
                                    + " first, empty",
                            null);
                }
                if (!asked.successful()) {
                    throw GitTreeStore.Unreachable.fromGit(
                            "cannot read the organization's repository", asked.standardError());
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
                            branch + ":" + branch));
            final CommandResult fetched =
                    git(lease.environment(), arguments.toArray(String[]::new));
            if (!fetched.successful() && NO_BRANCH.matcher(fetched.standardError()).find()) {
                throw new GitTreeStore.Empty("the organization's repository holds nothing yet");
            }
            if (!fetched.successful()) {
                throw GitTreeStore.Unreachable.fromGit(
                        "cannot read the organization's repository", fetched.standardError());
            }
        }
    }

    /** Whether what git wrote says the repository is not there, in any forge's words. */
    static boolean notThere(final String said) {
        return NOT_THERE.matcher(said).find();
    }

    /**
     * Leaves out what the forge said through git: it was just lent a token and can say it back.
     *
     * @param said What git wrote on its standard error.
     * @return Git's own lines.
     */
    static String gitsOwnWords(final String said) {
        return said.lines()
                .filter(line -> !line.stripLeading().startsWith("remote:"))
                .filter(line -> !line.contains("remote error:"))
                // Git quotes what the server answered, and a forge may answer with what it was
                // sent.
                .filter(line -> !line.contains("invalid server response"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
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
        return line(branch, revision, null);
    }

    /**
     * Returns the first-parent line of the given branch after a commit, oldest first.
     *
     * @param branch Where the line ends, a branch or its resolved tip.
     * @param since The commit whose history is excluded, or {@code null} for the whole line.
     * @return Each commit and its first parent, "" for the founding one.
     * @throws CardException When the history is shallow and so proves nothing.
     */
    List<Map.Entry<String, String>> lineAfter(final String branch, final @Nullable String since) {
        return line(branch, null, since);
    }

    private List<Map.Entry<String, String>> line(
            final String branch, final @Nullable String revision, final @Nullable String since) {
        if (!require("rev-parse", "--is-shallow-repository").strip().equals("false")) {
            throw new CardException("a shallow organization cannot prove its history");
        }
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "rev-list",
                                "--parents",
                                "--first-parent",
                                "--reverse",
                                "--end-of-options",
                                branch));
        if (since != null) {
            arguments.add("^" + since);
        }
        arguments.add("--");
        final List<Map.Entry<String, String>> line = new ArrayList<>();
        for (final String entry : require(arguments.toArray(String[]::new)).lines().toList()) {
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
                                        + ",valueonly,separator=%x00)",
                                branch)
                        .lines()
                        .toList()) {
            final String[] fields = row.split(" ", 2);
            if (fields.length < 2 || !List.of(fields[1].split("\u0000")).contains(id)) {
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

    /**
     * Returns the commits on the first-parent line that sign a file anew. A trailer anyone can
     * write: it only says which signature to judge, never that one is good.
     *
     * @param path The file.
     * @return The commits whose trailer names it.
     */
    java.util.Set<String> signingAnew(final String path) {
        return signingAnew(branch, path);
    }

    /**
     * Returns the commits on the given branch's first-parent line that sign a file anew.
     *
     * @param branch The branch or its resolved tip.
     * @param path The file.
     * @return The commits whose trailer names it; their signatures are still to be judged.
     */
    java.util.Set<String> signingAnew(final String branch, final String path) {
        // Each value framed by a NUL, which no trailer holds: a ',' in git's format ends the option
        // and leaves no separator, so two values ran together and a comma of a value's own split
        // it.
        final java.util.Set<String> commits = new java.util.HashSet<>();
        for (final String row :
                require(
                                "log",
                                "--first-parent",
                                "--format=%H %(trailers:key="
                                        + SIGNS_ANEW
                                        + ",valueonly,separator=%x00)",
                                "--end-of-options",
                                branch,
                                "--")
                        .lines()
                        .toList()) {
            final String[] fields = row.split(" ", 2);
            if (fields.length == 2 && List.of(fields[1].split("\u0000")).contains(path)) {
                commits.add(fields[0]);
            }
        }
        return commits;
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
        return commit(parent, files, subject, id, signing, key, login, null);
    }

    /**
     * Makes a signed commit as {@link #commit(String, Map, String, String, CommitSigning, String,
     * String)} does, and with no file changed, one that signs a file anew.
     *
     * @param parent The tip.
     * @param files What changes; empty to sign anew.
     * @param subject The commit's subject.
     * @param id The change's id.
     * @param signing Whose agent signs.
     * @param key The key that signs.
     * @param login Who it is written as.
     * @param signsAnew The file this commit signs anew, or {@code null}.
     * @return The commit, which the branch now names.
     * @throws IOException When a file cannot be handed to git.
     * @throws CardException When git cannot make it.
     */
    String commit(
            final String parent,
            final Map<String, @Nullable String> files,
            final String subject,
            final String id,
            final CommitSigning signing,
            final String key,
            final String login,
            final @Nullable String signsAnew)
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
        arguments.addAll(
                List.of(
                        "-m",
                        subject
                                + "\n\n"
                                + CHANGE_ID
                                + ": "
                                + id
                                + "\n"
                                + (signsAnew == null ? "" : SIGNS_ANEW + ": " + signsAnew + "\n"),
                        tree));
        final CommandResult made = git(identity, arguments.toArray(String[]::new));
        if (!made.successful() && NOT_SIGNED.matcher(made.standardError()).find()) {
            throw new Refused(
                    Refused.NO_KEY,
                    "the ssh-agent did not sign with " + PersonCard.fingerprint(key),
                    null);
        }
        final String commit = written(made).strip();
        if (parent.isEmpty()) {
            written(git("update-ref", branch, commit));
        } else {
            written(git("update-ref", branch, commit, parent));
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
        return push(address, access, null);
    }

    /** Pushes only while the remote still stands at the base read, including across a rewind. */
    boolean push(final String address, final FetchAccess access, final @Nullable String base)
            throws IOException {
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(List.of("push", "-q", "--no-verify"));
            if (base != null) {
                arguments.add("--force-with-lease=" + branch + ":" + base);
            }
            arguments.addAll(List.of("--", address, branch + ":" + branch));
            return git(lease.environment(), arguments.toArray(String[]::new)).successful();
        }
    }

    /** Fetches an organization's pull ref, never a URL supplied by a pull request. */
    String proposal(final String address, final long number, final FetchAccess access)
            throws IOException {
        if (number < 1) throw new IllegalArgumentException("expected a pull request number");
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of(
                            "fetch",
                            "-q",
                            "--no-tags",
                            "--",
                            address,
                            "+refs/pull/" + number + "/head:refs/porelo/proposal"));
            if (!git(lease.environment(), arguments.toArray(String[]::new)).successful()) {
                throw new GitTreeStore.Unreachable("the proposal's commit cannot be fetched");
            }
        }
        return tip("refs/porelo/proposal");
    }

    /** The full set of parents, so a merge can never masquerade as a person's single commit. */
    List<String> parents(final String commit) {
        final String text =
                require("show", "-s", "--format=%P", "--end-of-options", commit, "--").strip();
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }

    /** Counts every commit the proposed head adds to main, including second-parent history. */
    long commitsBeyond(final String commit, final String main) {
        return Long.parseLong(
                require("rev-list", "--count", "--end-of-options", commit, "^" + main, "--")
                        .strip());
    }

    /** Points only this owned scratch clone at the commit a reader must judge. */
    void pointAt(final String commit) {
        require("update-ref", branch, commit);
    }

    /**
     * Reads a remote branch before a conditional update; absence and an unreachable forge differ.
     */
    String remoteTip(final String address, final String ref, final FetchAccess access)
            throws IOException {
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(List.of("ls-remote", "--", address, ref));
            final CommandResult result = git(lease.environment(), arguments.toArray(String[]::new));
            if (!result.successful())
                throw new GitTreeStore.Unreachable("the remote branch cannot be read");
            return result.standardOutput()
                    .lines()
                    .map(line -> line.split("\t", 2)[0])
                    .findFirst()
                    .orElse("");
        }
    }

    /**
     * Pushes the exact object only while the target still names the judged object (or is absent).
     */
    boolean pushLeased(
            final String address,
            final String commit,
            final String ref,
            final String expected,
            final FetchAccess access)
            throws IOException {
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of(
                            "push",
                            "-q",
                            "--no-verify",
                            "--force-with-lease=" + ref + ":" + expected,
                            "--",
                            address,
                            commit + ":" + ref));
            return git(lease.environment(), arguments.toArray(String[]::new)).successful();
        }
    }

    /**
     * Returns the branch's tip.
     *
     * @return The commit.
     */
    String tip() {
        return tip(branch);
    }

    /**
     * Returns the given branch's tip.
     *
     * @param branch The branch.
     * @return The commit.
     */
    String tip(final String branch) {
        return require("rev-parse", "--verify", "--end-of-options", branch).strip();
    }

    /**
     * Whether a commit's history contains another commit, through any parent, including itself.
     *
     * @param commit The tip whose history is read.
     * @param ancestor The commit to find, which may no longer be held in the repository.
     * @return Whether it is an ancestor.
     */
    boolean hasAncestor(final String commit, final String ancestor) {
        return require("rev-list", "--end-of-options", commit, "--")
                .lines()
                .anyMatch(ancestor::equals);
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
