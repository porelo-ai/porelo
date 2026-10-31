package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.jspecify.annotations.Nullable;

/**
 * The keys a followed project's configuration is taken from, handed on by the keys before them.
 *
 * <p>A person names the first key at the follow, out of band. After that the project's own file
 * says whose commits change it, under {@code project.signers}: a commit that changes that list
 * counts only when it is signed by a key in force before it, so the keys pinned here hand on to the
 * ones it names - and a key never vouches for the commit that adds it. Every commit since the one
 * in force that changes the list is checked in order, so a key added and removed again in between
 * is followed through. A missing list differs from an empty list and cannot replace a declared
 * list.
 */
final class SignerHandover {

    private static final String FILE = "project.yml";

    private static final Pattern CUT = Pattern.compile("^\\[\\d+ bytes cut before this]\\n");

    /**
     * What the commits since the one in force hand on.
     *
     * @param keys The keys in force after the last of them, each as type and key.
     * @param changed Whether they differ from the ones pinned here.
     * @param tip The verdict when the fetched commit changed a protected file, checked with the
     *     keys before it; {@code null} when no signature was required at the tip.
     * @param retirements The keys retired along the range, with their handover times.
     * @param refusedFile The protected path refused, or "".
     * @param refused The verdict on a commit that changed the list without being signed by a key in
     *     force, or {@code null}.
     */
    record Handed(
            List<String> keys,
            boolean changed,
            ConfigurationGate.@Nullable Verdict tip,
            ConfigurationGate.@Nullable Verdict refused,
            String refusedFile,
            List<Retired> retirements) {}

    /** A key kept for history until the handover commit's committer time. */
    record Retired(String key, String before) {}

    /** The declaration in force before a rewrite. */
    record Rewrite(Path repository, String commit) {}

    private final CommandRunner runner;
    private final Path signers;
    private final @Nullable Path krl;

    private final java.util.function.Consumer<String> verifiedSigner;

    /**
     * Constructor.
     *
     * @param runner How git is run.
     * @param signers The pinned keys, in OpenSSH's allowed_signers format, by project.
     */
    SignerHandover(final CommandRunner runner, final Path signers) {
        this(runner, signers, null);
    }

    /** Constructs a walker with the revocation policy shared by every signature check. */
    SignerHandover(final CommandRunner runner, final Path signers, final @Nullable Path krl) {
        this(runner, signers, krl, ignored -> {});
    }

    /** Keeps signature evidence from this walk, before later handovers retire its signers. */
    SignerHandover(
            final CommandRunner runner,
            final Path signers,
            final @Nullable Path krl,
            final java.util.function.Consumer<String> verifiedSigner) {
        this.runner = runner;
        this.signers = signers;
        this.krl = krl;
        this.verifiedSigner = verifiedSigner;
    }

    /**
     * Follows the keys from the commit in force to the one fetched.
     *
     * @param clone The followed clone.
     * @param project The project, the principal its keys are pinned under.
     * @param inForce The commit in force, or "" before the first.
     * @param tip The commit fetched.
     * @return What they hand on.
     */
    Handed walk(final Path clone, final String project, final String inForce, final String tip) {
        return walk(clone, project, inForce, tip, null);
    }

    /** Checks a replacement tip against the declaration that was in force before the rewrite. */
    Handed walk(
            final Path clone,
            final String project,
            final String inForce,
            final String tip,
            final @Nullable Rewrite rewrite) {
        return walk(clone, project, inForce, tip, false, rewrite);
    }

    /** Checks an access repository's protected changes with the project's current keys. */
    Handed access(final Path clone, final String project, final String inForce, final String tip) {
        return walk(clone, project, inForce, tip, true, null);
    }

    private Handed walk(
            final Path clone,
            final String project,
            final String inForce,
            final String tip,
            final boolean access,
            final @Nullable Rewrite rewrite) {
        List<String> keys = List.of();
        final List<Retired> retirements = new ArrayList<>();
        String checking = tip;
        String file = "";
        try {
            requireFullHistory(runner, clone);
            final List<String> pinned = pinned(project);
            keys = pinned;
            ConfigurationGate.requireRevocations(runner, krl);
            if (tip.isEmpty()) {
                throw new ProjectException("the fetched commit cannot be read");
            }
            if (rewrite != null) {
                requireCommit(rewrite.repository(), rewrite.commit());
                requireFullHistory(runner, rewrite.repository());
            }
            List<String> last =
                    access
                            ? null
                            : rewrite != null
                                    ? at(rewrite.repository(), rewrite.commit())
                                    : inForce.isEmpty() ? before(clone, tip) : at(clone, inForce);
            final List<String> commits =
                    inForce.isEmpty() ? List.of(tip) : between(clone, inForce, tip);
            ConfigurationGate.Verdict tipVerdict = null;
            for (final String commit : commits) {
                checking = commit;
                file = access ? "access.yml" : FILE;
                final List<String> parents = parents(clone, commit);
                if (!inForce.isEmpty()) {
                    if (parents.isEmpty()) {
                        throw new ProjectException("no first parent at " + commit);
                    }
                    final String parent = parents.getFirst();
                    final Set<String> changed = changed(clone, parent, commit);
                    final Set<String> protectedPaths = new LinkedHashSet<>();
                    if (access) {
                        protectedPaths.add("access.yml");
                    } else {
                        protectedPaths.addAll(ProtectedFiles.at(runner, clone, parent));
                        // project.yml itself is always protected, including its deletion or an
                        // unreadable replacement. Check its signature before reading its new paths.
                        if (!changed.contains(FILE) && !changed.contains("porelo.yml")) {
                            protectedPaths.addAll(ProtectedFiles.at(runner, clone, commit));
                        }
                    }
                    file =
                            changed.stream()
                                    .filter(protectedPaths::contains)
                                    .sorted()
                                    .findFirst()
                                    .orElse("");
                    if (file.isEmpty()) {
                        continue;
                    }
                }
                final List<String> named = access ? null : at(clone, commit);
                if (!access && last != null && named == null) {
                    file = FILE;
                    throw new ProjectException(
                            "project.signers cannot be removed; change its list instead");
                }
                final List<String> verificationKeys =
                        !access && inForce.isEmpty() && rewrite == null && last != null
                                ? keys.stream().filter(last::contains).toList()
                                : keys;
                final ConfigurationGate.Verdict verdict =
                        verify(clone, project, verificationKeys, commit);
                if (!verdict.mayApply()) {
                    return new Handed(
                            keys,
                            false,
                            null,
                            new ConfigurationGate.Verdict(
                                    verdict.outcome(),
                                    verdict.commit(),
                                    verdict.signer(),
                                    commit
                                            + " ("
                                            + file
                                            + ") changes "
                                            + change(clone, commit, file, keys)
                                            + ": "
                                            + verdict.detail()),
                            file,
                            List.of());
                }
                if (!access) {
                    final String signingKey = SignedBy.keyOf(runner, clone, commit);
                    final String fingerprint =
                            signingKey == null ? null : SignedBy.fingerprintOf(signingKey);
                    if (fingerprint != null) {
                        verifiedSigner.accept(fingerprint);
                    }
                    if (!java.util.Objects.equals(named, last)) {
                        final List<String> next =
                                java.util.Objects.requireNonNull(
                                        named,
                                        "removing a declared list was refused before verification");
                        for (final String key : keys) {
                            if (!next.contains(key)) {
                                final Retired retired = new Retired(key, timeOf(clone, commit));
                                final Path check = Files.createTempFile("porelo-retirement-", "");
                                try {
                                    Files.writeString(
                                            check, retirementLine(project, retired) + "\n");
                                    AllowedSigner.read(runner, check);
                                } finally {
                                    Files.deleteIfExists(check);
                                }
                                retirements.add(retired);
                            }
                        }
                        keys = next;
                    } else if (named != null) {
                        final Set<String> next = new LinkedHashSet<>(keys);
                        next.addAll(named);
                        keys = List.copyOf(next);
                    }
                    last = named;
                }
                if (commit.equals(tip)) {
                    tipVerdict = verdict;
                }
            }
            if (commits.isEmpty()) {
                final ConfigurationGate.Verdict verdict = verify(clone, project, keys, tip);
                tipVerdict = verdict.mayApply() ? verdict : null;
            }
            return new Handed(
                    keys,
                    !retirements.isEmpty()
                            || !new LinkedHashSet<>(keys).equals(new LinkedHashSet<>(pinned)),
                    tipVerdict,
                    null,
                    "",
                    List.copyOf(retirements));
        } catch (final IOException | RuntimeException ex) {
            return new Handed(
                    keys,
                    false,
                    null,
                    new ConfigurationGate.Verdict(
                            ConfigurationGate.Outcome.UNREADABLE,
                            checking,
                            "",
                            "cannot check " + checking + " " + file + ": " + ex.getMessage()),
                    file,
                    List.of());
        }
    }

    private @Nullable List<String> before(final Path clone, final String tip) {
        final List<String> commits = parents(clone, tip);
        return commits.isEmpty() ? null : at(clone, commits.getFirst());
    }

    private List<String> parents(final Path clone, final String tip) {
        final CommandResult parents =
                runner.run(Command.of("git", "-C", clone.toString(), "cat-file", "commit", tip));
        if (!parents.successful() || parents.standardOutput().isBlank()) {
            throw new ProjectException("cannot read the first parent at " + tip);
        }
        final List<String> commits =
                parents.standardOutput()
                        .lines()
                        .takeWhile(line -> !line.isEmpty())
                        .filter(line -> line.startsWith("parent "))
                        .map(line -> line.substring("parent ".length()))
                        .toList();
        for (final String parent : commits) {
            requireCommit(clone, parent);
        }
        return commits;
    }

    /** A shallow boundary must never hide a declaration that retired an anchor. */
    static void requireFullHistory(final CommandRunner runner, final Path repository) {
        final CommandResult shallow =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "rev-parse",
                                "--is-shallow-repository"));
        if (!shallow.successful() || !shallow.standardOutput().strip().equals("false")) {
            throw new ProjectException(
                    "cannot judge in shallow repository or unreadable repository " + repository);
        }
    }

    private void requireCommit(final Path clone, final String commit) {
        final CommandResult readable =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "cat-file",
                                "-e",
                                commit + "^{commit}"));
        if (!readable.successful()) {
            throw new ProjectException("cannot read commit " + commit + " in " + clone);
        }
    }

    private String change(
            final Path clone, final String commit, final String file, final List<String> keys) {
        if (file.equals(FILE)) {
            try {
                final List<String> named = at(clone, commit);
                if (named != null
                        && !new LinkedHashSet<>(named).equals(new LinkedHashSet<>(keys))) {
                    return "project.signers";
                }
            } catch (final RuntimeException ex) {
                return file;
            }
        }
        return file;
    }

    private Set<String> changed(final Path clone, final String parent, final String commit) {
        final CommandResult diff =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "diff-tree",
                                "--no-renames",
                                "-r",
                                "--name-only",
                                "-z",
                                parent,
                                commit,
                                "--"));
        final String output = diff.standardOutput();
        if (!diff.successful()
                || CUT.matcher(output).find()
                || (!output.isEmpty() && !output.endsWith("\0"))) {
            throw new ProjectException(
                    "cannot obtain a complete list of changed files at " + commit);
        }
        return new LinkedHashSet<>(List.of(output.split("\\x00")));
    }

    /**
     * Checks a commit against the given keys.
     *
     * @param clone The followed clone.
     * @param project The principal.
     * @param keys The keys.
     * @param ref What to check.
     * @return The verdict.
     */
    ConfigurationGate.Verdict verify(
            final Path clone, final String project, final List<String> keys, final String ref) {
        if (keys.isEmpty() && !hasProject(project)) {
            return new ConfigurationGate.Verdict(
                    ConfigurationGate.Outcome.NO_ANCHOR,
                    ref,
                    "",
                    "no key is pinned for "
                            + project
                            + ", so no configuration can be applied here");
        }
        Path own = null;
        try {
            own = Files.createTempFile("porelo-signers-", "");
            final List<String> lines = new ArrayList<>();
            for (final String key : keys) {
                lines.add(activeLine(project, key));
            }
            Files.write(own, lines);
            return new ConfigurationGate(runner, own, krl).verifyAfterRevocations(clone, ref);
        } catch (final IOException ex) {
            return new ConfigurationGate.Verdict(
                    ConfigurationGate.Outcome.UNREADABLE,
                    "",
                    "",
                    "the keys in force could not be written down: " + ex.getMessage());
        } finally {
            if (own != null) {
                try {
                    Files.deleteIfExists(own);
                } catch (final IOException ignored) {
                    // A temporary file of public keys; nothing secret is left.
                }
            }
        }
    }

    /**
     * Pins the keys handed on for one project, in place of the ones it had, and says what changed.
     *
     * @param project The project.
     * @param handed The keys in force and retirements verified along the range.
     * @return What was pinned and unpinned, by fingerprint, for a person to read; "" when nothing
     *     changed.
     * @throws IOException If the file cannot be written.
     */
    String pin(final String project, final Handed handed) throws IOException {
        try (final Staged staged = stage(project, handed)) {
            return staged.publish();
        }
    }

    /** A validated trust file that is published only after the configuration can be applied. */
    record Staged(Path file, Path destination, String detail) implements AutoCloseable {
        String publish() throws IOException {
            Files.move(
                    file,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return detail;
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(file);
        }
    }

    /** Prepares and validates the shared file before any working tree moves. */
    Staged stage(final String project, final Handed handed) throws IOException {
        final List<String> keys = handed.keys();
        final List<String> before = pinned(project);
        final List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(signers)) {
            for (final String line : Files.readAllLines(signers)) {
                final AllowedSigner parsed = AllowedSigner.parse(line);
                if (parsed == null || !parsed.principal().equals(project) || parsed.retired()) {
                    lines.add(line);
                }
            }
        }
        for (final Retired retired : handed.retirements()) {
            final String line = retirementLine(project, retired);
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        for (final String key : keys) {
            lines.add(activeLine(project, key));
        }
        Files.createDirectories(signers.toAbsolutePath().getParent());
        final Path staged =
                Files.createTempFile(signers.toAbsolutePath().getParent(), ".project-signers-", "");
        try {
            Files.write(staged, lines);
            AllowedSigner.read(runner, staged);
        } catch (final IOException | RuntimeException ex) {
            Files.deleteIfExists(staged);
            throw ex;
        }
        final List<String> said = new ArrayList<>();
        keys.stream()
                .filter(key -> !before.contains(key))
                .forEach(key -> said.add("pinned " + fingerprint(key)));
        before.stream()
                .filter(key -> !keys.contains(key))
                .forEach(key -> said.add("unpinned " + fingerprint(key)));
        return new Staged(
                staged,
                signers,
                said.isEmpty()
                        ? ""
                        : String.join(", ", said)
                                + " for "
                                + project
                                + ", as project.signers says");
    }

    private String retirementLine(final String project, final Retired retired) throws IOException {
        final AllowedSigner active =
                java.util.Objects.requireNonNull(
                        AllowedSigner.parse(activeLine(project, retired.key())),
                        "active lines carry a public key");
        final List<String> options = new ArrayList<>(active.options());
        options.add("valid-before=\"" + retired.before() + "\"");
        return project + " " + String.join(",", options) + " " + retired.key();
    }

    private String activeLine(final String project, final String key) throws IOException {
        if (Files.isRegularFile(signers)) {
            for (final String line : Files.readAllLines(signers)) {
                final AllowedSigner parsed = AllowedSigner.parse(line);
                if (parsed != null
                        && parsed.principal().equals(project)
                        && parsed.key().equals(key)
                        && !parsed.retired()) {
                    return line;
                }
            }
        }
        return project + " " + key;
    }

    private String timeOf(final Path clone, final String commit) {
        final CommandResult time =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "show",
                                "-s",
                                "--format=%ct",
                                commit));
        if (!time.successful()) {
            throw new ProjectException("cannot read the handover time at " + commit);
        }
        final long seconds = Long.parseLong(time.standardOutput().strip());
        if (seconds <= 1
                || seconds > java.time.Instant.parse("9999-12-31T23:59:59Z").getEpochSecond()) {
            throw new ProjectException(
                    "handover time cannot be represented as a valid-before after 1970-01-01T00:00:01Z and through 9999-12-31");
        }
        return java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochSecond(seconds));
    }

    private static String fingerprint(final String key) {
        final String found = SignedBy.fingerprintOf(key);
        return found == null ? key : found;
    }

    /**
     * The keys pinned for a project, as ssh-keygen prints them.
     *
     * @param project The project's name.
     * @return Its active keys.
     */
    List<String> pinned(final String project) {
        final List<String> keys = new ArrayList<>();
        try {
            if (!Files.notExists(signers, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                for (final String line : AllowedSigner.read(runner, signers)) {
                    final AllowedSigner parsed = AllowedSigner.parse(line);
                    if (parsed == null && !line.isBlank() && !line.strip().startsWith("#")) {
                        throw new ProjectException(
                                "the pinned keys contain an invalid allowed-signers line");
                    }
                    if (parsed != null && parsed.principal().equals(project) && !parsed.retired()) {
                        keys.add(parsed.key());
                    }
                }
            }
        } catch (final IOException ex) {
            throw new ProjectException("the pinned keys could not be read: " + ex.getMessage(), ex);
        }
        return keys;
    }

    private boolean hasProject(final String project) {
        try {
            if (Files.notExists(signers, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            for (final String line : Files.readAllLines(signers)) {
                final AllowedSigner parsed = AllowedSigner.parse(line);
                if (parsed != null && parsed.principal().equals(project)) {
                    return true;
                }
            }
            return false;
        } catch (final IOException ex) {
            throw new ProjectException("the pinned keys could not be read: " + ex.getMessage(), ex);
        }
    }

    private List<String> between(final Path clone, final String older, final String newer) {
        // First parent: the line of commits the project's main moved along, each one as it was
        // taken - a merge counts as the commit that brought the change.
        final CommandResult listed =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "rev-list",
                                "--reverse",
                                "--first-parent",
                                older + ".." + newer));
        if (!listed.successful()) {
            throw new ProjectException("cannot list commits from " + older + " to " + newer);
        }
        final List<String> commits =
                listed.standardOutput()
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .toList();
        if (!older.equals(newer)) {
            String previous = older;
            for (final String commit : commits) {
                final List<String> parents = parents(clone, commit);
                if (parents.isEmpty() || !parents.getFirst().equals(previous)) {
                    throw new ProjectException(
                            "the commit in force is not on the first-parent path");
                }
                previous = commit;
            }
            if (!previous.equals(newer)) {
                throw new ProjectException("the commit in force is not on the first-parent path");
            }
        }
        return commits;
    }

    private @Nullable List<String> at(final Path clone, final String commit) {
        requireCommit(clone, commit);
        final CommandResult listed =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "ls-tree",
                                "-z",
                                commit,
                                "--",
                                FILE));
        if (!listed.successful()) {
            throw new ProjectException("cannot read the tree at " + commit + " in " + clone);
        }
        if (listed.standardOutput().isEmpty()) {
            return null;
        }
        final CommandResult shown =
                runner.run(Command.of("git", "-C", clone.toString(), "show", commit + ":" + FILE));
        if (!shown.successful()) {
            throw new ProjectException("cannot read " + FILE + " at " + commit + " in " + clone);
        }
        return ProjectReader.signers(shown.standardOutput(), FILE + " at " + commit);
    }
}
