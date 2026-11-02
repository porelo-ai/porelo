package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Opens a project: its repository on the forge, the first signed commit, its address filed. A run
 * stopped anywhere is answered again under the same {@code --id}, each step finding its own work.
 */
@Command(
        name = "open",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Opens a project: makes its repository, signs it, and files its address.")
public class ProjectOpenCommand extends OrgWriting {

    /** Makes the repository; replaced by a test, which has no forge. */
    @FunctionalInterface
    interface Maker {
        void make(PoreloContext machine, RegistryOrigin origin, String name);
    }

    @Parameters(paramLabel = "PROJECT", description = "The project.")
    private String project = "";

    Maker maker = ProjectOpenCommand::made;

    private @Nullable String address;

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
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
        final RegistryOrigin origin = RegistryOrigin.of(organization);
        final String at =
                state.getProjectRepository(project).orElseGet(() -> beside(organization, project));
        address = at;
        final String first = firstFile(project);
        try {
            opened.files().found(change, at, first);
        } catch (Refused refused) {
            if (!Refused.NO_REPOSITORY.equals(refused.reason())) {
                throw refused;
            }
            maker.make(machine, origin, project);
            opened.files().found(change, at, first);
        }
        return opened.writes().changeProjectRepository(change, project, at);
    }

    @Override
    void more(final Map<String, @Nullable Object> answer) {
        answer.put("address", address);
    }

    /** A project opens with nothing but what its file must say; its admin fills it from there. */
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

    private static void made(
            final PoreloContext machine, final RegistryOrigin origin, final String name) {
        final String token = OrgForgeToken.of(machine, origin);
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
        new ForgeRepositoryMaker(OrgAccess.http(machine))
                .make(forge, token, origin.organisation(), name);
    }
}
