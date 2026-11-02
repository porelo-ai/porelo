package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.CardException;

/** An organization of the new structure, read from the repository its settings name. */
public final class GitTreeStore {

    private final CommandRunner runner;

    private final String repository;

    private final String organisation;

    /**
     * Opens the store. Nothing is read yet.
     *
     * @param runner How git runs.
     * @param repository The repository's address, the adapter's {@code repository} setting.
     * @param organisation The organisation the identity source knows it by.
     */
    public GitTreeStore(
            final CommandRunner runner, final String repository, final String organisation) {
        this.runner = runner;
        this.repository = repository;
        this.organisation = organisation;
    }

    /**
     * Fetches the repository and walks its history from the first commit.
     *
     * @param identities Who says whose keys are whose.
     * @return The answers, at the commit the walk reached.
     * @throws IOException When it cannot be fetched or read.
     * @throws OrganizationException When its history proves nothing.
     */
    public OrganizationQuestions read(final IdentitySource identities) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(repository, runner)) {
            return new TreeWalk(clone, identities, organisation).read();
        } catch (CardException | CommandException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }
}
