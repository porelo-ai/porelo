package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.vault.CardException;
import org.jspecify.annotations.Nullable;

/**
 * An organization of the new structure, read from the repository its settings name. With a state
 * directory it keeps a trust record, so that a later read goes on from what an earlier one
 * accepted, and refuses a tip that does not continue it: an older state served again, or a fork.
 */
public final class GitTreeStore {

    private static final String RECORD = "trust-record.json";

    private static final Set<PosixFilePermission> NOT_OWNERS =
            EnumSet.complementOf(
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));

    private final CommandRunner runner;

    private final String repository;

    private final String organisation;

    private final FetchAccess access;

    private final @Nullable Path state;

    private final OrganizationQuestions.Description description;

    GitTreeStore(
            final CommandRunner runner,
            final String repository,
            final String organisation,
            final String forge,
            final FetchAccess access,
            final @Nullable Path state) {
        this.runner = runner;
        this.repository = repository;
        this.organisation = organisation;
        this.access = access;
        this.state = state;
        final String vouchedBy = "github.com".equals(forge) ? "GitHub" : forge;
        this.description =
                new OrganizationQuestions.Description(
                        organisation,
                        "the " + vouchedBy + " organization " + organisation,
                        vouchedBy);
    }

    private static GitTreeStore opened(
            final CommandRunner runner,
            final String repository,
            final FetchAccess access,
            final @Nullable Path state) {
        final RegistryOrigin origin = RegistryOrigin.of(repository);
        return new GitTreeStore(
                runner, repository, origin.organisation(), origin.forge().getHost(), access, state);
    }

    /**
     * Opens a store that keeps nothing. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param access What a fetch may use.
     * @return The store.
     * @throws IllegalArgumentException When the address names no forge and organisation.
     */
    public static GitTreeStore open(
            final CommandRunner runner, final String repository, final FetchAccess access) {
        return opened(runner, repository, access, null);
    }

    /**
     * Opens a store that keeps its trust record. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param access What a fetch may use.
     * @param state The directory the record is kept in, made owner-only when first written.
     * @return The store.
     * @throws IllegalArgumentException When the address names no forge and organisation.
     */
    public static GitTreeStore open(
            final CommandRunner runner,
            final String repository,
            final FetchAccess access,
            final Path state) {
        return opened(runner, repository, access, state);
    }

    /**
     * Fetches the repository and walks its history to the branch's tip.
     *
     * @param identities Who says whose keys are whose.
     * @return The answers, at the commit the walk reached.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing, or does not continue the
     *     record.
     */
    public OrganizationQuestions read(final IdentitySource identities) throws IOException {
        return walk(identities, null);
    }

    /**
     * Fetches the repository and walks its history to a revision, so that several answers rest on
     * one state. It keeps nothing.
     *
     * @param identities Who says whose keys are whose.
     * @param revision The revision, one an earlier answer named.
     * @return The answers, at that revision.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing, does not continue the record,
     *     or the revision is not on its line or could not be reached.
     */
    public OrganizationQuestions read(final IdentitySource identities, final String revision)
            throws IOException {
        return walk(identities, revision);
    }

    /**
     * Answers the last state kept, fetching nothing, for when the repository cannot be reached.
     * With no history at hand it cannot check the record against it, so it rests on the owner-only
     * file alone: <strong>no security check may rest on this answer.</strong>
     *
     * @return The answers, or empty when no usable record is kept.
     * @throws IOException When the record cannot be read.
     */
    public Optional<OrganizationQuestions> last() throws IOException {
        final List<String> warnings = new ArrayList<>();
        final TreeRecord kept = kept(warnings);
        return kept == null ? Optional.empty() : Optional.of(answers(kept, warnings));
    }

    private OrganizationQuestions walk(
            final IdentitySource identities, final @Nullable String revision) throws IOException {
        final List<String> warnings = new ArrayList<>();
        final TreeRecord kept = kept(warnings);
        try (TreeRepository clone = TreeRepository.fetch(repository, runner, access)) {
            final List<String> line = clone.line(null).stream().map(Map.Entry::getKey).toList();
            final String commit = kept == null ? null : kept.commit();
            if (commit != null && !line.contains(commit)) {
                throw new OrganizationQuestions.Refused(
                        OrganizationQuestions.Refused.REFUSED_STATE,
                        "the organization's tip does not continue the commit last trusted: an older"
                                + " state served again, or a fork, at "
                                + commit,
                        commit);
            }
            final TreeWalk walk = new TreeWalk(clone, identities, organisation);
            walk.readChains();
            final TreeRecord start = start(walk, kept, line, revision, warnings);
            final TreeRecord walked = walk.read(revision, start);
            if (revision == null && keeps(line, commit, walked.commit())) {
                keep(walked);
            }
            return answers(walked, warnings);
        } catch (CardException | CommandException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }

    private OrganizationQuestions answers(final TreeRecord walked, final List<String> warnings) {
        return new GitTreeOrganization(
                walked.commit(), walked.model(), walked.refusals(), warnings, description);
    }

    /** The record to go on from: none for a revision before it, or when a card it held changed. */
    private static @Nullable TreeRecord start(
            final TreeWalk walk,
            final @Nullable TreeRecord kept,
            final List<String> line,
            final @Nullable String revision,
            final List<String> warnings) {
        if (kept == null) {
            return null;
        }
        if (revision != null
                && line.indexOf(revision) >= 0
                && line.indexOf(revision) < line.indexOf(kept.commit())) {
            return null;
        }
        if (walk.unsettled(kept)) {
            warnings.add(
                    "the identity source now says otherwise of a card it could not settle;"
                            + " the organization was read afresh from its first commit");
            return null;
        }
        return kept;
    }

    /** A record never goes back: a fresh walk the source stopped early leaves the later one. */
    private boolean keeps(
            final List<String> line, final @Nullable String kept, final @Nullable String reached) {
        return state != null
                && reached != null
                && (kept == null || line.indexOf(reached) >= line.indexOf(kept));
    }

    /**
     * Reads the record kept. One that is no owner-only regular file, or does not read whole, or
     * speaks for another address is discarded, said in a warning: slower, never weaker.
     */
    private @Nullable TreeRecord kept(final List<String> warnings) throws IOException {
        if (state == null) {
            return null;
        }
        final Path file = state.resolve(RECORD);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Collections.disjoint(
                            Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS),
                            NOT_OWNERS)) {
                throw new CardException("the trust record is no owner-only regular file");
            }
            return TreeRecord.read(Files.readString(file, StandardCharsets.UTF_8), repository);
        } catch (CardException | MalformedInputException ex) {
            warnings.add(
                    "the trust record was discarded and the organization read afresh from its"
                            + " first commit: "
                            + (ex instanceof CardException
                                    ? ex.getMessage()
                                    : "it is not UTF-8 text"));
            return null;
        }
    }

    /** Writes the record owner-only and whole: a reader never finds half of one. */
    private void keep(final TreeRecord record) throws IOException {
        final Path directory = java.util.Objects.requireNonNull(state, "a store that keeps");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path staged =
                Files.createTempFile(
                        directory,
                        ".",
                        ".json.staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staged, record.toJson(repository), StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    directory.resolve(RECORD),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
