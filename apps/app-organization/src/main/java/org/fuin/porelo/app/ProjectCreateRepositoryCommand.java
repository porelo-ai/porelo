package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Creates a project's Git repository: on the forge, with its first signed commit and its address
 * filed. A run stopped anywhere is answered again under the same {@code --id}, each step finding
 * its own work.
 */
@Command(
        name = "create-repository",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Creates the project's Git repository, signs its first project.yml, and files its"
                        + " address.")
public class ProjectCreateRepositoryCommand extends OrgWriting {

    /** Makes the repository; replaced by a test, which has no forge. */
    @FunctionalInterface
    interface Maker {
        void make(PoreloContext machine, ForgeAddress origin, String name);
    }

    @Parameters(paramLabel = "PROJECT", description = "The project.")
    private String project = "";

    Maker maker = ProjectCreateRepositoryCommand::made;

    private @Nullable String address;

    @Option(
            names = "--review",
            description = "Whether a merge needs another person: required or optional.")
    private @Nullable String review;

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
            throws IOException {
        final Created created = create(machine, opener, opened, change, project, review, maker);
        address = created.address();
        return created.written();
    }

    /**
     * What creating a repository made.
     *
     * @param written The filing of its address.
     * @param address Its address.
     */
    record Created(OrganizationWrites.Written written, String address) {}

    /**
     * Creates a project's repository, reading the organization anew: who may, and where it stands.
     *
     * @param machine The machine.
     * @param opener Opens the organization.
     * @param opened Who acts.
     * @param change Under which id; without a base, against what is read here.
     * @param project The project.
     * @param review What its first file says of a review, or {@code null}.
     * @param maker Makes the repository on the forge.
     * @return What it made.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    static Created create(
            final PoreloContext machine,
            final OrgAccess.Opener opener,
            final Session opened,
            final OrganizationWrites.Change change,
            final String project,
            final @Nullable String review,
            final Maker maker)
            throws IOException {
        final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
        if (reading.stale()) {
            throw new Refused(
                    Refused.CANNOT_SAY, "the organization cannot be reached to say who may", null);
        }
        final OrganizationQuestions state = reading.answers();
        final String login = change.acting().login();
        ProjectRepositoryCommand.admin(state, project, login);
        // Asked again, the address is filed and the id answers; otherwise nothing is made on a
        // state the person did not see, which a new id could then only call taken.
        if (change.base() != null
                && !change.base().equals(state.revision())
                && state.getProjectRepository(project).isEmpty()) {
            throw new Refused(Refused.MOVED_ON, "the organization has moved on", state.revision());
        }
        final String organization = OrgAccess.repository(machine).orElseThrow();
        final ForgeAddress origin = ForgeAddress.of(organization);
        final String at =
                state.getProjectRepository(project).orElseGet(() -> beside(organization, project));
        final String first =
                review == null
                        ? firstFile(project)
                        : firstFile(project)
                                .replace(
                                        "  description: \"\"\n",
                                        "  description: \"\"\n  review: \""
                                                + ProjectRepositoryCommand.review(review)
                                                + "\"\n");
        try {
            opened.files().found(change, at, first);
        } catch (Refused refused) {
            if (!Refused.NO_REPOSITORY.equals(refused.reason())) {
                throw refused;
            }
            maker.make(machine, origin, project);
            opened.files().found(change, at, first);
        }
        final OrganizationWrites.Change filing =
                change.base() == null
                        ? new OrganizationWrites.Change(
                                change.acting(), state.revision(), change.id())
                        : change;
        return new Created(opened.writes().changeProjectRepository(filing, project, at), at);
    }

    @Override
    void more(final Map<String, @Nullable Object> answer) {
        answer.put("address", address);
    }

    /** A project starts with nothing but what its file must say; its admin fills it from there. */
    static String firstFile(final String project) {
        return "project:\n  name: \""
                + project
                + "\"\n  description: \"\"\n  security_class: \"guarded\"\nimage:\n"
                + "  base_image: \"ubuntu:24.04\"\n";
    }

    /** A project's repository stands beside the organization's, reached the same way. */
    static String beside(final String organization, final String project) {
        final String trimmed = organization.replaceAll("/+$", "");
        final String ending = trimmed.endsWith(".git") ? ".git" : "";
        return trimmed.substring(0, trimmed.lastIndexOf('/') + 1) + project + ending;
    }

    static void made(final PoreloContext machine, final ForgeAddress origin, final String name) {
        final String token = OrganizationAccess.forgeToken(machine, origin);
        if (token == null) {
            if (machine.readableCredentials().isEmpty()) {
                throw new Refused(
                        Refused.VAULT_SHUT,
                        "the vault is shut, and the token to make the repository may be in it",
                        null);
            }
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "no token for " + origin.forge() + " to make the repository with",
                    null);
        }
        final URI forge = origin.forge();
        new ForgeRepositoryMaker(OrganizationAccess.http(machine))
                .make(forge, token, origin.organization(), name);
    }
}
