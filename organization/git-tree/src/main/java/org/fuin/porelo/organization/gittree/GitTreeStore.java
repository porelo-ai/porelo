package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.vault.CardException;
import org.jspecify.annotations.Nullable;

/** An organization of the new structure, read from the repository its settings name. */
public final class GitTreeStore {

    private final CommandRunner runner;

    private final String repository;

    private final String organisation;

    private final FetchAccess access;

    GitTreeStore(
            final CommandRunner runner,
            final String repository,
            final String organisation,
            final FetchAccess access) {
        this.runner = runner;
        this.repository = repository;
        this.organisation = organisation;
        this.access = access;
    }

    /**
     * Opens the store. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param access What a fetch may use.
     * @return The store.
     * @throws IllegalArgumentException When the address names no forge and organisation.
     */
    public static GitTreeStore open(
            final CommandRunner runner, final String repository, final FetchAccess access) {
        return new GitTreeStore(
                runner, repository, RegistryOrigin.of(repository).organisation(), access);
    }

    /**
     * Fetches the repository and walks its history to the branch's tip.
     *
     * @param identities Who says whose keys are whose.
     * @return The answers, at the commit the walk reached.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing.
     */
    public OrganizationQuestions read(final IdentitySource identities) throws IOException {
        return walk(identities, null);
    }

    /**
     * Fetches the repository and walks its history to a revision, so that several answers rest on
     * one state.
     *
     * @param identities Who says whose keys are whose.
     * @param revision The revision, one an earlier answer named.
     * @return The answers, at the commit the walk reached.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing, or the revision is not on its
     *     line.
     */
    public OrganizationQuestions read(final IdentitySource identities, final String revision)
            throws IOException {
        return walk(identities, revision);
    }

    private OrganizationQuestions walk(
            final IdentitySource identities, final @Nullable String revision) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(repository, runner, access)) {
            return new TreeWalk(clone, identities, organisation).read(revision);
        } catch (CardException | CommandException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }
}
