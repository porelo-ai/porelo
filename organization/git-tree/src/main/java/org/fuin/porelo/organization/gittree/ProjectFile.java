package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
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

    /**
     * Constructor.
     *
     * @param runner How git runs.
     * @param access What a fetch and a push may use.
     * @param signing Whose agent signs a change; a read signs nothing.
     */
    public ProjectFile(
            final CommandRunner runner, final FetchAccess access, final CommitSigning signing) {
        this.runner = runner;
        this.access = access;
        this.signing = signing;
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
            return judged(clone, keys);
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            return new Read(CANNOT_SAY, null, null, null);
        }
    }

    private static Read judged(final TreeRepository clone, final List<SigningKey> keys)
            throws IOException {
        try {
            final String tip = clone.tip();
            final Optional<String> newest = newestChange(clone);
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
            return new Read(READ, tip, parsed(clone.text(blob.orElseThrow())), null);
        } catch (CardException ex) {
            return refused(String.valueOf(ex.getMessage()));
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, which anyone with write access wrote.
            return refused("the project repository's project.yml does not read");
        }
    }

    private static Read refused(final String why) {
        return new Read(REFUSED, null, null, why);
    }

    private static Project parsed(final String text) {
        return ProjectReader.read(new StringReader(text), PATH);
    }

    private static Optional<String> newestChange(final TreeRepository clone) {
        final List<Map.Entry<String, String>> line = clone.line(null);
        for (int each = line.size() - 1; each >= 0; each--) {
            final Map.Entry<String, String> step = line.get(each);
            if (clone.changed(step.getKey(), step.getValue()).contains(PATH)) {
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

    private OrganizationWrites.Written change(
            final OrganizationWrites.Change change,
            final String address,
            final List<SigningKey> keys,
            final String subject,
            final UnaryOperator<Map<String, Object>> edit)
            throws IOException {
        final String acting = change.acting().key();
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final Optional<String> made =
                    clone.changeNamed(change.id(), PersonCard.fingerprint(acting));
            if (made.isPresent()) {
                if (!clone.subject(made.orElseThrow()).equals(subject)) {
                    throw new Refused(
                            Refused.USAGE, "this --id named another change", made.orElseThrow());
                }
                return new OrganizationWrites.Written(made.orElseThrow(), change.id(), true);
            }
            final Read read = judged(clone, keys);
            final String tip = clone.tip();
            if (!READ.equals(read.state())) {
                throw new Refused(Refused.REFUSED_STATE, String.valueOf(read.why()), tip);
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
            parsed(written);
        } catch (RuntimeException ex) {
            throw new Refused(Refused.USAGE, "that would make no project file", null);
        }
        return written;
    }
}
