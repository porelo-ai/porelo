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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * What a read found.
     *
     * @param state {@link #READ}, {@link #CANNOT_SAY} or {@link #REFUSED}.
     * @param revision The project repository's tip, or {@code null} unless read.
     * @param project The file, or {@code null} unless read.
     * @param why Why it is refused, or {@code null}.
     */
    public record Read(
            String state,
            @Nullable String revision,
            @Nullable Project project,
            @Nullable String why) {}

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

    private Read judged(
            final TreeRepository clone, final List<SigningKey> keys, final String address)
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
                                + ", which is no key of an admin of this project now: an admin signs"
                                + " it anew");
            }
            final Optional<String> blob = clone.object(tip, PATH);
            if (blob.isEmpty()) {
                return refused("the project repository holds no project.yml");
            }
            final Instant dated = OffsetDateTime.parse(clone.dated(commit)).toInstant();
            final Instant accepted = accepted(address);
            if (accepted != null && dated.isBefore(accepted)) {
                return refused(
                        "the project.yml of "
                                + commit
                                + " is older than the one this machine accepted, from "
                                + accepted
                                + ": an admin signs it anew");
            }
            final Set<String> named = new LinkedHashSet<>();
            final Project project =
                    parsed(clone.text(blob.orElseThrow()), inClone(clone, tip, named));
            for (final String each : named) {
                final Optional<String> changed = newestChange(clone, each);
                if (changed.isPresent()
                        && !anAdminsKey(
                                clone.signer(changed.orElseThrow()),
                                keys,
                                clone.dated(changed.orElseThrow()))) {
                    return refused(
                            each
                                    + ", which project.yml names, was changed by "
                                    + changed.orElseThrow()
                                    + ", not signed by the key of an admin of this project now: an"
                                    + " admin signs it anew");
                }
            }
            final Read read = new Read(READ, tip, project, null);
            remember(address, dated);
            return read;
        } catch (CardException ex) {
            return refused(String.valueOf(ex.getMessage()));
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
        return new Read(REFUSED, null, null, why);
    }

    private static Project parsed(final String text, final SnippetFiles snippets) {
        return ProjectReader.read(new StringReader(text), PATH, snippets);
    }

    /** Reads what the file names from the commit judged, noting it: it is judged as the file is. */
    private static SnippetFiles inClone(
            final TreeRepository clone, final String tip, final Set<String> named) {
        return (path, origin) -> {
            final Path normal = Path.of(path).normalize();
            if (normal.isAbsolute() || normal.startsWith("..")) {
                throw new ProjectException(origin + ": a snippet file leaves the repository");
            }
            final Optional<String> object = clone.object(tip, normal.toString());
            if (object.isEmpty()) {
                throw new ProjectException(origin + ": no such snippet file");
            }
            named.add(normal.toString());
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
        return change(
                change,
                address,
                keys,
                TreeRepository.asked("Name a repository", name, upstream),
                repositories -> {
                    if (repositories.containsKey(name)) {
                        throw new Refused(Refused.EXISTS, "that repository is named", null);
                    }
                    repositories.put(name, new LinkedHashMap<>(Map.of("upstream", upstream)));
                    return repositories;
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

    private OrganizationWrites.Written change(
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
        final Object held = file.get(REPOSITORIES);
        final Map<String, Object> repositories =
                held == null ? new LinkedHashMap<>() : (Map<String, Object>) held;
        file.put(REPOSITORIES, edit.apply(repositories));
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
