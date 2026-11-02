package org.fuin.porelo.app;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Changes who owns the organization. */
@Command(
        name = "owner",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes who owns the organization.",
        subcommands = {OrgOwnerCommand.Add.class, OrgOwnerCommand.Remove.class})
public class OrgOwnerCommand {

    /** Makes a person an owner. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Makes a person an owner.")
    public static class Add extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addOrganizationOwner(change, login);
        }
    }

    /** Takes a person off the owners. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Takes a person off the owners.")
    public static class Remove extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().removeOrganizationOwner(change, login);
        }
    }
}
