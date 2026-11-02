package org.fuin.porelo.app;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Changes the organization's projects. */
@Command(
        name = "project",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes the organization's projects.",
        subcommands = {
            OrgProjectCommand.Add.class,
            OrgProjectCommand.Remove.class,
            OrgProjectCommand.Admin.class
        })
public class OrgProjectCommand {

    /** Adds a project, with its first admin. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Adds a project, with its first admin.")
    public static class Add extends OrgWriting {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Option(names = "--admin", required = true, description = "Its first admin.")
        private String admin = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addProject(change, project, admin);
        }
    }

    /** Removes a project. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Removes a project.")
    public static class Remove extends OrgWriting {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().removeProject(change, project);
        }
    }

    /** Changes a project's first admin. */
    @Command(
            name = "admin",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Changes a project's first admin.")
    public static class Admin extends OrgWriting {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().changeProjectAdmin(change, project, login);
        }
    }
}
