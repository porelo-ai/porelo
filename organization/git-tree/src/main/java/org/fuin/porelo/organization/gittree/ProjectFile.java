package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A project's {@code project.yml}, kept in its own repository: fetched, checked against the keys of
 * its admins, and changed by a signed commit. A project's own repository may hold other files too,
 * by other signers, so only the newest commit that changed this file is judged.
 */
public final class ProjectFile {

    /** It was read. */
    public static final String READ = "read";

    /** The repository could not be reached or could not say. */
    public static final String CANNOT_SAY = "cannot-say";

    /** It is there and is not to be believed. */
    public static final String REFUSED = "refused";

    private static final String PATH = "project.yml";

    private static final String ADMIN = "admin";

    private static final String REPOSITORIES = "repositories";

    /** What a signing adds, as only a commit that changes the file is judged. */
    private static final String MARK = "# signed anew by ";

    /**
     * What a read found.
     *
     * @param state {@link #READ}, {@link #CANNOT_SAY} or {@link #REFUSED}.
     * @param revision The project repository's tip, or {@code null} unless read.
     * @param project The file, or {@code null} unless read.
     * @param why Why it is refused, or {@code null}.
     * @param signer The key the newest change is signed by, or {@code null} when it has none that
     *     verifies.
     */
    public record Read(
            String state,
            @Nullable String revision,
            @Nullable Project project,
            @Nullable String why,
            @Nullable String signer) {

        /** What a read found, with no signer to name. */
        public Read(
                final String state,
                final @Nullable String revision,
                final @Nullable Project project,
                final @Nullable String why) {
            this(state, revision, project, why, null);
        }
    }

    /**
     * What an admin reads before signing the file anew.
     *
     * @param read What a read of it found.
     * @param revision The project repository's tip, which a signing names, or {@code null} when it
     *     could not be reached.
     * @param text The file as it stands, or {@code null} when there is none that can be shown.
     * @param blocked What a signature would not change, or {@code null} when one would do.
     */
    public record Look(
            Read read,
            @Nullable String revision,
            @Nullable String text,
            @Nullable String blocked) {}

    private final CommandRunner runner;

    private final FetchAccess access;

    private final CommitSigning signing;

    private final Path memory;

    /**
     * Constructor.
     *
     * @param runner How git runs.
     * @param access What a fetch and a push may use.
     * @param signing Whose agent signs a change; a read signs nothing.
     * @param memory The directory of this machine's state that keeps, per repository, the date of
     *     the newest change it accepted.
     */
    public ProjectFile(
            final CommandRunner runner,
            final FetchAccess access,
            final CommitSigning signing,
            final Path memory) {
        this.runner = runner;
        this.access = access;
        this.signing = signing;
        this.memory = memory;
    }

    /**
     * Reads the file.
     *
     * @param address The project repository.
     * @param keys The project's signing keys, as its organization answers them.
     * @return What was found; a failure to reach the repository is never read as an empty project.
     * @throws IOException When something unexpected happened.
     */
    public Read read(final String address, final List<SigningKey> keys) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            return judged(clone, keys, address);
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    /**
     * Reads the file in a repository already on this machine, with no network and no memory: the
     * caller holds the commit it took last and the keys in force.
     *
     * @param runner How git runs.
     * @param repository The git directory, a bare copy another account may own.
     * @param keys The project's signing keys.
     * @return What was found.
     * @throws IOException When something unexpected happened.
     */
    public static Read standing(
            final CommandRunner runner, final Path repository, final List<SigningKey> keys)
            throws IOException {
        try (TreeRepository clone = TreeRepository.standing(repository, runner)) {
            return judged(clone, keys, null, "");
        } catch (CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    private Read judged(
            final TreeRepository clone, final List<SigningKey> keys, final String address)
            throws IOException {
        return judged(clone, keys, this, address);
    }

    private static Read judged(
            final TreeRepository clone,
            final List<SigningKey> keys,
            final @Nullable ProjectFile guard,
            final String address)
            throws IOException {
        try {
            final String tip = clone.tip();
            final Optional<String> newest = newestChange(clone, PATH);
            if (newest.isEmpty()) {
                return refused("the project repository holds no project.yml");
            }
            final String commit = newest.orElseThrow();
            final String signer = clone.signer(commit);
            if (!anAdminsKey(signer, keys, clone.dated(commit))) {
                return refused(
                        "the project.yml of "
                                + commit
                                + " is signed by "
                                + PersonCard.fingerprint(signer)
                                + ", which is no key of an admin of this project now"
                                + wayOut(clone, tip),
                        signer);
            }
            final Optional<String> blob = clone.object(tip, PATH);
            if (blob.isEmpty()) {
                return refused("the project repository holds no project.yml");
            }
            final Instant dated = OffsetDateTime.parse(clone.dated(commit)).toInstant();
            final Instant accepted = guard == null ? null : guard.accepted(address);
            if (accepted != null && dated.isBefore(accepted)) {
                return refused(
                        "the project.yml of "
                                + commit
                                + " is older than the one this machine accepted, from "
                                + accepted
                                + wayOut(clone, tip),
                        signer);
            }
            final Project project = parsed(clone.text(blob.orElseThrow()), inClone(clone, tip));
            final Read read = new Read(READ, tip, project, null, signer);
            if (guard != null) {
                guard.remember(address, dated);
            }
            return read;
        } catch (CardException ex) {
            return refused(String.valueOf(ex.getMessage()));
        } catch (ProjectException ex) {
            if (guard != null) {
                return refused("the project repository's project.yml does not read");
            }
            // Past the signature only: an admin of the project wrote what this quotes.
            return refused(RegistryText.printable(String.valueOf(ex.getMessage())));
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, which anyone with write access wrote.
            return refused("the project repository's project.yml does not read");
        }
    }

    /**
     * Returns the date last accepted. A record that does not read is no record: the guard only ever
     * refuses, so losing it costs what a new machine has too.
     */
    private @Nullable Instant accepted(final String address) throws IOException {
        final Path file = memory.resolve(TreeRepository.digest(address));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Instant.parse(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private void remember(final String address, final Instant dated) throws IOException {
        Files.createDirectories(memory);
        final Path temporary = Files.createTempFile(memory, "accepted", ".tmp");
        try {
            Files.writeString(temporary, dated + "\n", StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    memory.resolve(TreeRepository.digest(address)),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Read refused(final String why) {
        return refused(why, null);
    }

    private static Read refused(final String why, final @Nullable String signer) {
        return new Read(REFUSED, null, null, why, signer);
    }

    private static Project parsed(final String text, final SnippetFiles snippets) {
        return ProjectReader.read(new StringReader(text), PATH, snippets);
    }

    /** Reads what the file names from that commit: the caller's directory has no part in it. */
    private static SnippetFiles inClone(final TreeRepository clone, final String tip) {
        return (path, origin) -> {
            final Path normal = Path.of(path).normalize();
            if (normal.isAbsolute() || normal.startsWith("..")) {
                throw new ProjectException(origin + ": a snippet file leaves the repository");
            }
            final Optional<String> object = clone.object(tip, normal.toString());
            if (object.isEmpty()) {
                throw new ProjectException(origin + ": no such snippet file");
            }
            return clone.text(object.orElseThrow());
        };
    }

    private static Optional<String> newestChange(final TreeRepository clone, final String path) {
        final List<Map.Entry<String, String>> line = clone.line(null);
        for (int each = line.size() - 1; each >= 0; each--) {
            final Map.Entry<String, String> step = line.get(each);
            if (clone.changed(step.getKey(), step.getValue()).contains(path)) {
                return Optional.of(step.getKey());
            }
        }
        return Optional.empty();
    }

    /** A key whose card was withdrawn signs nothing that is still believed, whatever it claims. */
    private static boolean anAdminsKey(
            final String key, final List<SigningKey> keys, final String dated) {
        final LocalDate day = OffsetDateTime.parse(dated).toLocalDate();
        return keys.stream()
                .anyMatch(
                        row ->
                                ADMIN.equals(row.role())
                                        && row.key().equals(key)
                                        && row.revokedSince() == null
                                        && !day.isBefore(row.validFrom())
                                        && day.isBefore(row.validUntil()));
    }

    /**
     * Reads the file for an admin who may sign it anew. It changes nothing in the repository, and
     * on this machine what every read does: a file that reads is remembered as accepted.
     *
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param project The project the file must name.
     * @return What was found, with the file's text.
     * @throws IOException When something unexpected happened.
     */
    public Look look(final String address, final List<SigningKey> keys, final String project)
            throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final String tip = clone.tip();
            final Read read = judged(clone, keys, address);
            return new Look(read, tip, shown(clone, tip), blocked(clone, tip, project));
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new Look(new Read(CANNOT_SAY, null, null, null), null, null, null);
        }
    }

    /**
     * Signs the file anew as it stands, so that it is read again: whoever does it takes on the
     * whole of it, which is why the person reads it first and names what they read.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param project The project the file must name.
     * @return What it made, or made before under this id.
     * @throws Refused {@code not-allowed} when the key that acts is no admin's, {@code moved-on}
     *     when the repository is not what was read, {@code refused-state} when a signature would
     *     not make the file read.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written sign(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String project)
            throws IOException {
        final String acting = change.acting().key();
        final String subject = TreeRepository.asked("Sign the file anew", project);
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final Optional<String> made = own(clone, change);
            if (made.isPresent()) {
                if (!clone.subject(made.orElseThrow()).equals(subject)) {
                    throw new Refused(
                            Refused.USAGE, "this --id named another change", made.orElseThrow());
                }
                return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
            }
            final String tip = clone.tip();
            if (!anAdminsKey(acting, keys, OffsetDateTime.now().toString())) {
                throw new Refused(
                        Refused.NOT_ALLOWED,
                        "the key that acts is no key of this project's admin",
                        tip);
            }
            if (change.base() != null && !change.base().equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the project repository moved on", tip);
            }
            // Reading here says nothing of a machine that accepted a newer one, so it signs too.
            final String text = signable(clone, tip, project);
            final String commit =
                    clone.commit(
                            tip,
                            Map.of(PATH, signed(text, change.id())),
                            subject,
                            change.id(),
                            signing,
                            acting,
                            change.acting().login());
            if (!clone.push(address, access)) {
                throw new GitTreeStore.Unreachable(
                        "the project repository did not take the change");
            }
            remember(address, OffsetDateTime.parse(clone.dated(commit)).toInstant());
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /** The file's text, or {@code null} when there is none that can be shown. */
    private static @Nullable String shown(final TreeRepository clone, final String tip) {
        try {
            return clone.object(tip, PATH).map(clone::text).orElse(null);
        } catch (CardException ex) {
            return null;
        }
    }

    /** What a signature would not change, or {@code null} when one would do. */
    private static @Nullable String blocked(
            final TreeRepository clone, final String tip, final String project) {
        try {
            signable(clone, tip, project);
            return null;
        } catch (Refused refused) {
            return refused.getMessage();
        }
    }

    /** What a refusal offers, which is the verb only where a signature would make the file read. */
    private static String wayOut(final TreeRepository clone, final String tip) {
        try {
            parsed(clone.text(clone.object(tip, PATH).orElseThrow()), inClone(clone, tip));
            return ": porelo project sign shows an admin whether signing it anew will do";
        } catch (RuntimeException ex) {
            return ", and it does not read either: an admin mends it and signs that commit";
        }
    }

    private static String signed(final String text, final String id) {
        return text + (text.endsWith("\n") ? "" : "\n") + MARK + id + "\n";
    }

    /** The file as it stands, if a signature would make it read. */
    private static String signable(
            final TreeRepository clone, final String tip, final String project) {
        final Optional<String> text;
        final @Nullable String named;
        try {
            text = clone.object(tip, PATH).map(clone::text);
            named = text.isEmpty() ? null : parsed(text.orElseThrow(), inClone(clone, tip)).name();
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, which anyone with write access wrote.
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml does not read, and a signature would not"
                            + " change that",
                    tip);
        }
        if (text.isEmpty()) {
            throw new Refused(
                    Refused.REFUSED_STATE, "the project repository holds no project.yml", tip);
        }
        if (!project.equals(named)) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml names another project",
                    tip);
        }
        // What is pushed must read: the longest line an id can make has to fit, and to parse.
        final String longest = signed(text.orElseThrow(), "x".repeat(64));
        if (longest.getBytes(StandardCharsets.UTF_8).length > TreeRepository.LIMIT) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "a signature's line would take the project repository's project.yml over the"
                            + " limit of what is read",
                    tip);
        }
        try {
            parsed(longest, inClone(clone, tip));
        } catch (RuntimeException ex) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the project repository's project.yml would not read with a signature's line",
                    tip);
        }
        return text.orElseThrow();
    }

    /**
     * Names a repository in the file.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param name The repository's name.
     * @param upstream Its address.
     * @return What it made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written add(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String name,
            final String upstream)
            throws IOException {
        return add(change, address, keys, name, upstream, null, null);
    }

    /**
     * Names a repository, with where a host takes it from and whether a merge needs a review.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param name The repository's name.
     * @param upstream Its address.
     * @param central Where a host takes it from, or {@code null} for nowhere.
     * @param review {@code required} or {@code optional}, or {@code null} to say nothing.
     * @return What it made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written add(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String name,
            final String upstream,
            final @Nullable String central,
            final @Nullable String review)
            throws IOException {
        final Map<String, Object> row = new LinkedHashMap<>(Map.of("upstream", upstream));
        if (central != null) {
            row.put("central", central);
        }
        if (review != null) {
            row.put("review", review);
        }
        final List<String> asked = new ArrayList<>(List.of(name, upstream));
        row.entrySet().stream()
                .filter(each -> !"upstream".equals(each.getKey()))
                .forEach(each -> asked.add(String.valueOf(each.getValue())));
        return change(
                change,
                address,
                keys,
                TreeRepository.asked("Name a repository", asked.toArray(String[]::new)),
                repositories -> {
                    if (repositories.containsKey(name)) {
                        throw new Refused(Refused.EXISTS, "that repository is named", null);
                    }
                    repositories.put(name, row);
                    return repositories;
                });
    }

    /**
     * Files a detected repository row as one signed change.
     *
     * @param change Who acts and the revision they approved.
     * @param address The project's own repository.
     * @param keys The project's signing keys.
     * @param name The repository's name.
     * @param row Its upstream, egress and image, with optional central and review.
     * @return The signed change.
     * @throws IOException When the change cannot be completed.
     */
    public OrganizationWrites.Written propose(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String name,
            final Map<String, Object> row)
            throws IOException {
        final Map<String, Object> accepted = new LinkedHashMap<>();
        for (final String key : List.of("upstream", "central", "review", "egress", "image")) {
            if (row.containsKey(key)) accepted.put(key, row.get(key));
        }
        return change(
                change,
                address,
                keys,
                TreeRepository.asked(
                        "Propose a repository", name, new Yaml().dump(ordered(accepted))),
                repositories -> {
                    if (repositories.containsKey(name))
                        throw new Refused(Refused.EXISTS, "that repository is named", null);
                    repositories.put(name, accepted);
                    return repositories;
                });
    }

    /** Map iteration order must not make a retry under the same id another change. */
    private static Object ordered(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> sorted = new java.util.TreeMap<>();
            for (final var entry : map.entrySet())
                sorted.put(
                        String.valueOf(entry.getKey()),
                        ordered(
                                java.util.Objects.requireNonNull(
                                        entry.getValue(), "a proposal row holds no null values")));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream()
                    .map(
                            each ->
                                    ordered(
                                            java.util.Objects.requireNonNull(
                                                    each, "a proposal list holds no null values")))
                    .toList();
        return value;
    }

    /**
     * Says whether a merge needs a review, in the project's own repository or in one it names.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param name The repository's name, or {@code null} for the project's own.
     * @param review {@code required} or {@code optional}.
     * @return What it made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    @SuppressWarnings("unchecked")
    public OrganizationWrites.Written review(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final @Nullable String name,
            final String review)
            throws IOException {
        return changeOf(
                change,
                address,
                keys,
                TreeRepository.asked(
                        "Say whether a merge needs a review", name == null ? "" : name, review),
                file -> {
                    if (name == null) {
                        ((Map<String, Object>)
                                        java.util.Objects.requireNonNull(file.get("project")))
                                .put("review", review);
                        return file;
                    }
                    final Map<String, Object> repositories =
                            (Map<String, Object>) file.get(REPOSITORIES);
                    if (repositories == null || !repositories.containsKey(name)) {
                        throw new Refused(Refused.NOT_FOUND, "no such repository", null);
                    }
                    final Object held = repositories.get(name);
                    final Map<String, Object> row =
                            held == null ? new LinkedHashMap<>() : (Map<String, Object>) held;
                    row.put("review", review);
                    repositories.put(name, row);
                    return file;
                });
    }

    /**
     * Takes a repository out of the file.
     *
     * @param change Who acts, against the project repository's revision, under which id.
     * @param address The project repository.
     * @param keys The project's signing keys.
     * @param name The repository's name.
     * @return What it made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written remove(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String name)
            throws IOException {
        return change(
                change,
                address,
                keys,
                TreeRepository.asked("Take a repository out", name),
                repositories -> {
                    if (repositories.remove(name) == null) {
                        throw new Refused(Refused.NOT_FOUND, "no such repository", null);
                    }
                    return repositories;
                });
    }

    /**
     * Fills an empty project repository with its first signed commit.
     *
     * @param change Who acts, under which id.
     * @param address The project repository.
     * @param text The file's first text.
     * @return What it made, or made before under this id.
     * @throws Refused {@code no-repository} when it is not there, {@code exists} when it holds
     *     anything else.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    public OrganizationWrites.Written found(
            final OrganizationWrites.Change change, final String address, final String text)
            throws IOException {
        final String acting = change.acting().key();
        final Optional<TreeRepository> empty = TreeRepository.founding(address, runner, access);
        if (empty.isEmpty()) {
            try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
                final Optional<String> made = own(clone, change);
                if (made.isPresent()) {
                    return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
                }
                throw new Refused(
                        Refused.EXISTS, "the project's repository holds something", clone.tip());
            } catch (GitTreeStore.Empty ex) {
                throw new Refused(Refused.EXISTS, "the project's repository holds something", null);
            } catch (CardException ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        }
        try (TreeRepository clone = empty.orElseThrow()) {
            final String commit =
                    clone.commit(
                            "",
                            Map.of(PATH, text),
                            "Open the project",
                            change.id(),
                            signing,
                            acting,
                            change.acting().login());
            if (!clone.push(address, access)) {
                throw new GitTreeStore.Unreachable(
                        "the project repository did not take the first commit");
            }
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private OrganizationWrites.Written change(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String subject,
            final UnaryOperator<Map<String, Object>> edit)
            throws IOException {
        return changeOf(
                change,
                address,
                keys,
                subject,
                file -> {
                    final Object held = file.get(REPOSITORIES);
                    file.put(
                            REPOSITORIES,
                            edit.apply(
                                    held == null
                                            ? new LinkedHashMap<>()
                                            : (Map<String, Object>) held));
                    return file;
                });
    }

    private OrganizationWrites.Written changeOf(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String subject,
            final UnaryOperator<Map<String, Object>> edit)
            throws IOException {
        final String acting = change.acting().key();
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final Optional<String> made = own(clone, change);
            if (made.isPresent()) {
                if (!clone.subject(made.orElseThrow()).equals(subject)) {
                    throw new Refused(
                            Refused.USAGE, "this --id named another change", made.orElseThrow());
                }
                return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
            }
            final Read read = judged(clone, keys, address);
            final String tip = clone.tip();
            if (!READ.equals(read.state())) {
                throw new Refused(Refused.REFUSED_STATE, String.valueOf(read.why()), tip);
            }
            // Signed by a key that is no admin's, the file would be refused by its next reader.
            if (!anAdminsKey(acting, keys, OffsetDateTime.now().toString())) {
                throw new Refused(
                        Refused.NOT_ALLOWED,
                        "the key that acts is no key of this project's admin",
                        tip);
            }
            if (change.base() != null && !change.base().equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "the project repository moved on", tip);
            }
            final String text = edited(clone.text(clone.object(tip, PATH).orElseThrow()), edit);
            final String commit =
                    clone.commit(
                            tip,
                            Map.of(PATH, text),
                            subject,
                            change.id(),
                            signing,
                            acting,
                            change.acting().login());
            if (!clone.push(address, access)) {
                throw new GitTreeStore.Unreachable(
                        "the project repository did not take the change");
            }
            return new OrganizationWrites.Written(commit, change.id(), false);
        } catch (CardException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * A change here is one commit, signed by the key that acts. Anyone with write access can append
     * a commit carrying the same id, which must not be answered as that person's.
     */
    private static Optional<String> own(
            final TreeRepository clone, final OrganizationWrites.Change change) throws IOException {
        final String fingerprint = PersonCard.fingerprint(change.acting().key());
        final Optional<String> made = clone.changeNamed(change.id(), fingerprint);
        return made.isPresent() && clone.signedBy(made.orElseThrow(), fingerprint)
                ? made
                : Optional.empty();
    }

    /** The file is written anew from what it says, so a comment in it does not survive. */
    @SuppressWarnings("unchecked")
    private static String edited(final String text, final UnaryOperator<Map<String, Object>> edit) {
        final Map<String, Object> file =
                new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        edit.apply(file);
        final DumperOptions block = new DumperOptions();
        block.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        final String written = new Yaml(block).dump(file);
        try {
            // Only the file's shape: what it names is judged when it is read.
            parsed(written, (path, origin) -> "");
        } catch (RuntimeException ex) {
            throw new Refused(Refused.USAGE, "that would make no project file", null);
        }
        return written;
    }
}
