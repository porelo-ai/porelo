package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.jspecify.annotations.Nullable;
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

        @Option(
                names = "--create-repository",
                description =
                        "Creates the project's Git repository in the same step; you must be its"
                                + " admin.")
        private boolean createRepository;

        @Option(
                names = "--review",
                description =
                        "With --create-repository, whether a merge needs another person: required"
                                + " or optional.")
        private @Nullable String review;

        ProjectCreateRepositoryCommand.Maker maker = ProjectCreateRepositoryCommand::made;

        private @Nullable String address;

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            if (!createRepository) {
                if (review != null) {
                    throw new Refused(
                            Refused.USAGE, "--review goes with --create-repository", null);
                }
                return opened.writes().addProject(change, project, admin);
            }
            // Before anything is written: only the admin may create the repository.
            if (!admin.equals(change.acting().login())) {
                throw new Refused(
                        Refused.USAGE,
                        "--create-repository is for the project's admin: name yourself, or leave"
                                + " it to "
                                + admin
                                + " to run porelo project create-repository",
                        null);
            }
            opened.writes().addProject(change, project, admin);
            try {
                final ProjectCreateRepositoryCommand.Created created =
                        ProjectCreateRepositoryCommand.create(
                                machine,
                                opener,
                                opened,
                                // Its own id, from this one: asked again, each part finds its work.
                                new OrganizationWrites.Change(
                                        change.acting(), null, change.id() + "-repository"),
                                project,
                                review,
                                maker);
                address = created.address();
                return created.written();
            } catch (Refused refused) {
                throw new Refused(
                        refused.reason(),
                        "project "
                                + project
                                + " is added, and its repository is not: "
                                + refused.getMessage()
                                + ": ask again with --id "
                                + change.id(),
                        refused.revision());
            }
        }

        @Override
        void more(final Map<String, @Nullable Object> answer) {
            if (createRepository) {
                answer.put("address", address);
            }
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
