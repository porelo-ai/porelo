package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Shows a repository's file before a project admin or developer signs the revision they read. */
@Command(
        name = "sign-repository-file",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Shows a repository's porelo.yml, and with --yes signs it anew.")
public class ProjectSignRepositoryFileCommand extends OrgWriting {

    @Parameters(
            index = "0",
            paramLabel = "PROJECT",
            description = "The organization's project taken here.")
    private String project = "";

    @Parameters(
            index = "1",
            paramLabel = "REPOSITORY",
            description = "The repository in its project file.")
    private String repository = "";

    @Option(names = "--yes", description = "Signs it, as it stands at --base.")
    private boolean yes;

    private record Target(String address, List<SigningKey> keys) {}

    @Override
    String of() {
        return "repository";
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

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
            throws IOException {
        final Target target = target(machine);
        return opened.files().signRepositoryFile(change, target.address(), target.keys());
    }

    private Target target(final PoreloContext machine) throws IOException {
        final OrganizationProjects.Placed placed = OrganizationProjects.of(machine).find(project);
        if (placed == null) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine has not taken the organization's project",
                    null);
        }
        final Path file = ProjectSource.resolve(machine, project).file();
        if (file == null) {
            throw new Refused(
                    Refused.NOT_OPENED,
                    "this machine holds no judged project file: start the project first",
                    null);
        }
        final Project read = ProjectReader.read(file);
        final Repository named = read.repository(repository);
        if (named == null || named.upstream() == null) {
            throw new Refused(
                    Refused.NOT_FOUND,
                    "the project file names no upstream for that repository",
                    null);
        }
        final List<SigningKey> keys =
                placed.keys().stream()
                        .map(
                                key ->
                                        new SigningKey(
                                                key.login(),
                                                key.role(),
                                                key.key(),
                                                key.validFrom(),
                                                key.validUntil(),
                                                key.revokedSince()))
                        .toList();
        return new Target(named.upstream(), keys);
    }

    private Map<String, @Nullable Object> look(final PoreloContext machine) throws IOException {
        final Target target = target(machine);
        final ProjectFile.RepositoryLook shown =
                new ProjectFile(
                                machine.runner(),
                                OrganizationAccess.lent(machine),
                                CommitSigning.agent(""),
                                OrgAccess.projectFiles(machine))
                        .lookRepositoryFile(target.address(), target.keys());
        final ProjectFile.Look look = shown.look();
        final ProjectFile.Read read = look.read();
        if (ProjectFile.CANNOT_SAY.equals(read.state())) {
            throw new Refused(Refused.CANNOT_SAY, "the repository cannot be reached", null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        more(answer);
        answer.put("revision", look.revision());
        answer.put("of", of());
        answer.put("change", shown.change());
        answer.put("state", read.state());
        answer.put("why", read.why());
        answer.put("signer", read.signer());
        answer.put("file", look.text());
        answer.put("blocked", look.blocked());
        return answer;
    }

    @Override
    void more(final Map<String, @Nullable Object> answer) {
        answer.put("project", project);
        answer.put("repository", repository);
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        if (!answer.containsKey("state")) {
            super.text(answer);
            return;
        }
        line("Project: " + answer.get("project"));
        line("Repository: " + answer.get("repository"));
        line("Revision: " + answer.get("revision"));
        line("Newest change of porelo.yml: " + answer.get("change"));
        final Object why = answer.get("why");
        line("State: " + answer.get("state") + (why == null ? "" : ": " + why));
        line("Signed by: " + answer.get("signer"));
        if (answer.get("file") instanceof String file) {
            line("The file, as signing would take it on:");
            for (final String each : List.of(file.split("\n", -1))) {
                // Anybody with write access may have put terminal controls in the file.
                line(each);
            }
        }
        if (answer.get("blocked") != null) {
            line("A signature would not do: " + answer.get("blocked"));
        } else {
            line(
                    "Having read it: porelo project sign-repository-file "
                            + project
                            + " "
                            + repository
                            + " --yes --base "
                            + answer.get("revision"));
        }
    }
}
