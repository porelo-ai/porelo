package org.fuin.porelo.app;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Changes the address of a project that is opened. */
@Command(
        name = "address",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes the address of a project that is opened.")
public class ProjectAddressCommand extends OrgWriting {

    @Parameters(paramLabel = "PROJECT", description = "The project.")
    private String project = "";

    @Parameters(paramLabel = "ADDRESS", description = "Its repository's address.")
    private String address = "";

    /** Replaced by a test, which has no forge to read the address now filed from. */
    Filed filed = ProjectAddressCommand::filedNow;

    /** Whether a project's address is filed. */
    @FunctionalInterface
    interface Filed {
        boolean of(PoreloContext machine, String project) throws IOException;
    }

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
            throws IOException {
        // Only create-repository gives a project its address, so that a first signed commit is
        // there.
        if (!filed.of(machine, project)) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "the project has no address yet: create its repository first",
                    null);
        }
        return opened.writes().changeProjectRepository(change, project, address);
    }

    private static boolean filedNow(final PoreloContext machine, final String project)
            throws IOException {
        return mayBeFiled(OrgAccess.read(machine, OrgAccess::real).answers(), project);
    }

    /**
     * Whether a project has an address to change: one filed, or one a lost key filed, which stands
     * refused for a new reader and comes back by filing it again.
     *
     * @param state What the organization says.
     * @param project The project.
     * @return Whether an address may be filed.
     */
    static boolean mayBeFiled(final OrganizationQuestions state, final String project) {
        for (final OrganizationQuestions.Project each : state.getProjects()) {
            if (each.project().equals(project)) {
                final String file = "projects/" + project + "/repository.yml";
                return each.filed()
                        || state.getRefusals().stream()
                                .anyMatch(refusal -> refusal.path().equals(file));
            }
        }
        throw new Refused(Refused.NOT_FOUND, "no such project", state.revision());
    }
}
