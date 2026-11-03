package org.fuin.porelo.app;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Signs a project's own file anew, for an admin who has read it. Without {@code --yes} it shows the
 * file and why it stands refused, and changes nothing: whoever signs takes on the whole of it, so
 * {@code --yes} needs the revision they read.
 */
@Command(
        name = "sign",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Shows a project's own file, and with --yes signs it anew.")
public class ProjectSignCommand extends ProjectRepositoryCommand.Changing {

    /** Reads the file; replaced by a test, which has no forge to read it from. */
    @FunctionalInterface
    interface Looks {
        ProjectFile.Look of(
                PoreloContext machine, String address, ProjectRepositoryCommand.Place place)
                throws IOException;
    }

    @Parameters(paramLabel = "PROJECT", description = "The project.")
    private String project = "";

    @Option(names = "--yes", description = "Signs it, as it stands at --base.")
    private boolean yes;

    Looks looks =
            (machine, address, place) ->
                    new ProjectFile(
                                    machine.runner(),
                                    OrganizationAccess.lent(machine),
                                    CommitSigning.agent(""),
                                    OrgAccess.projectFiles(machine))
                            .look(address, place.keys(), project);

    @Override
    String project() {
        return project;
    }

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        if (yes && base() == null) {
            throw new Refused(Refused.USAGE, "--yes needs --base, the revision you read", null);
        }
        if (!yes && (base() != null || id() != null)) {
            throw new Refused(Refused.USAGE, "--base and --id go with --yes", null);
        }
        return yes ? super.answer(machine) : look(machine);
    }

    private Map<String, @Nullable Object> look(final PoreloContext machine) throws IOException {
        final String login =
                OrgAccess.ownLogin(machine)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NOT_ALLOWED,
                                                "a project admin may change this",
                                                null));
        final ProjectRepositoryCommand.Place place = places.of(machine, project, login);
        final ProjectFile.Look look = looks.of(machine, place.address(), place);
        final ProjectFile.Read read = look.read();
        if (ProjectFile.CANNOT_SAY.equals(read.state())) {
            throw new Refused(Refused.CANNOT_SAY, "the project repository cannot be reached", null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("project", project);
        answer.put("revision", look.revision());
        answer.put("of", of());
        answer.put("state", read.state());
        answer.put("why", read.why());
        answer.put("signer", read.signer());
        answer.put("file", look.text());
        answer.put("blocked", look.blocked());
        return answer;
    }

    @Override
    OrganizationWrites.Written make(
            final ProjectFile file,
            final OrganizationWrites.Change change,
            final ProjectRepositoryCommand.Place place)
            throws IOException {
        return file.sign(change, place.address(), place.keys(), project);
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        if (!answer.containsKey("state")) {
            super.text(answer);
            return;
        }
        line("Project: " + answer.get("project"));
        line("Revision: " + answer.get("revision"));
        final Object why = answer.get("why");
        line("State: " + answer.get("state") + (why == null ? "" : ": " + why));
        line("Signed by: " + answer.get("signer"));
        if (answer.get("file") instanceof String file) {
            line("The file, as signing would take it on:");
            for (final String each : List.of(file.split("\n", -1))) {
                // Anyone with write access wrote it; a line says nothing that moves the terminal.
                line(each);
            }
        }
        final String signing =
                "porelo project sign "
                        + answer.get("project")
                        + " --yes --base "
                        + answer.get("revision");
        if (answer.get("blocked") != null) {
            line("A signature would not do: " + answer.get("blocked"));
        } else if (ProjectFile.REFUSED.equals(answer.get("state"))) {
            line("Having read it: " + signing);
        } else {
            line("If another machine refuses it as older, having read it: " + signing);
        }
    }
}
