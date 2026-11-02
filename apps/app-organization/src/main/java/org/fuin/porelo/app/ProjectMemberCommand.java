package org.fuin.porelo.app;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Changes a project's members. */
@Command(
        name = "member",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes a project's members.",
        subcommands = {
            ProjectMemberCommand.Add.class,
            ProjectMemberCommand.Remove.class,
            ProjectMemberCommand.Role.class,
            ProjectMemberCommand.Lock.class,
            ProjectMemberCommand.Unlock.class
        })
public class ProjectMemberCommand {

    /** Adds a member. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Adds a member.")
    public static class Add extends OrgWriting {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Option(names = "--role", required = true, description = "admin or developer.")
        private String role = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addProjectMember(change, project, login, role);
        }
    }

    /** Removes a member. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Removes a member.")
    public static class Remove extends OrgWriting {

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
            return opened.writes().removeProjectMember(change, project, login);
        }
    }

    /** Changes a member's role. */
    @Command(
            name = "role",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Changes a member's role.")
    public static class Role extends OrgWriting {

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Parameters(paramLabel = "LOGIN", description = "The login.")
        private String login = "";

        @Parameters(paramLabel = "ROLE", description = "admin or developer.")
        private String role = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().changeProjectMemberRole(change, project, login, role);
        }
    }

    /** Locks a member, who stays listed and may nothing. */
    @Command(
            name = "lock",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Locks a member, who stays listed and may nothing.")
    public static class Lock extends OrgWriting {

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
            return opened.writes().lockProjectMember(change, project, login);
        }
    }

    /** Unlocks a member. */
    @Command(
            name = "unlock",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Unlocks a member.")
    public static class Unlock extends OrgWriting {

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
            return opened.writes().unlockProjectMember(change, project, login);
        }
    }
}
