package org.fuin.porelo.app;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Changes who is an organization admin: an owner's. */
@Command(
        name = "admin",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes who is an organization admin: an owner's.",
        subcommands = {OrgAdminCommand.Add.class, OrgAdminCommand.Remove.class})
public class OrgAdminCommand {

    /** Lists a person as an organization admin. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Lists a person as an organization admin.")
    public static class Add extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addOrganizationAdmin(change, login);
        }
    }

    /** Takes a person off the organization admins. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Takes a person off the organization admins.")
    public static class Remove extends OrgWriting {

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().removeOrganizationAdmin(change, login);
        }
    }
}
