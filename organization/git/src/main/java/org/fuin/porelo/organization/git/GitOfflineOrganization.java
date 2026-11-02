package org.fuin.porelo.organization.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.vault.CardException;

/** An organization kept in Git, as a copy on this machine: a bundle file a host checks offline. */
public final class GitOfflineOrganization implements OfflineOrganization {

    private final CommandRunner runner;

    private final Path bundle;

    /**
     * Makes the copy's reader.
     *
     * @param runner How git runs.
     * @param bundle The bundle file.
     */
    public GitOfflineOrganization(final CommandRunner runner, final Path bundle) {
        this.runner = runner;
        this.bundle = bundle;
    }

    @Override
    public Map<String, Object> check(final String host, final String pinned, final String since)
            throws IOException {
        try {
            return new RegistryVerifier(runner).verify(bundle, host, pinned, since);
        } catch (CardException | CommandException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }
}
