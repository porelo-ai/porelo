package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Answers one project, for its members, or one registered on this machine alone. */
@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Answers a project of the organization, for its members, or of this machine.")
public class ProjectShowCommand extends OrgReading {

    private static final String REGISTRATION_TOKEN = "matrix-registration-token";

    /** Reads a project's own file; replaced by a test, which has no forge to read it from. */
    @FunctionalInterface
    interface Files {
        ProjectFile.Read read(
                PoreloContext machine, String address, List<OrganizationQuestions.SigningKey> keys)
                throws IOException;
    }

    Files files =
            (machine, address, keys) ->
                    new ProjectFile(
                                    machine.runner(),
                                    OrgAccess.lent(machine),
                                    CommitSigning.agent(""))
                            .read(address, keys);

    @Parameters(index = "0", paramLabel = "PROJECT", description = "The project.")
    private String project = "";

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final String localFile = OrgAccess.localProjects(machine).get(project);
        if (OrgAccess.repository(machine).isEmpty() && localFile != null) {
            return local(localFile);
        }
        final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
        final OrganizationQuestions state = reading.answers();
        final Optional<OrganizationQuestions.Project> held =
                state.getProjects().stream()
                        .filter(each -> each.project().equals(project))
                        .findFirst();
        if (held.isPresent()) {
            return filed(
                    machine, reading, held.orElseThrow(), OrgAccess.ownLogin(machine).orElse(null));
        }
        if (localFile != null) {
            return local(localFile);
        }
        throw new Refused(Refused.NOT_FOUND, "no such project", state.revision());
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line("Project: " + answer.get("project"));
        line("Revision: " + answer.get("revision"));
        line("Host: " + answer.get("host"));
    }

    private Map<String, @Nullable Object> filed(
            final PoreloContext machine,
            final OrgAccess.Reading reading,
            final OrganizationQuestions.Project held,
            final @Nullable String login)
            throws IOException {
        final OrganizationQuestions state = reading.answers();
        final List<OrganizationQuestions.Member> members =
                state.getProjectMembers(project).orElse(List.of());
        final OrganizationQuestions.Member me =
                members.stream()
                        .filter(member -> member.login().equals(login))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NOT_ALLOWED,
                                                "a project member sees this; porelo org shows the"
                                                        + " organization's other people the rest",
                                                state.revision()));
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("project", project);
        answer.put("revision", state.revision());
        answer.put("stale", reading.stale());
        answer.put("first_admin", held.firstAdmin());
        answer.put("host", held.host());
        answer.put("opened", held.filed());
        answer.put("address", state.getProjectRepository(project).orElse(null));
        answer.put("me", Map.of("role", me.role(), "locked", me.locked()));
        answer.put(
                "members",
                members.stream()
                        .map(
                                member ->
                                        Map.of(
                                                "login",
                                                member.login(),
                                                "role",
                                                member.role(),
                                                "locked",
                                                member.locked()))
                        .toList());
        if (!held.filed()) {
            answer.put("repositories", repositories("not-opened", List.of()));
            answer.put("matrix_registration_entry", null);
            return answer;
        }
        final ProjectFile.Read read =
                files.read(
                        machine,
                        state.getProjectRepository(project).orElseThrow(),
                        state.signingKeys(project));
        final Project file = read.project();
        final Map<String, @Nullable Object> repositories =
                repositories(read.state(), file == null ? List.of() : names(file));
        repositories.put("revision", read.revision());
        if (read.why() != null) {
            repositories.put("why", read.why());
        }
        answer.put("repositories", repositories);
        answer.put("matrix_registration_entry", file == null ? null : registrationEntry(file));
        return answer;
    }

    private Map<String, @Nullable Object> local(final String file) {
        final Project read;
        try {
            read = ProjectReader.read(Path.of(file));
        } catch (RuntimeException ex) {
            // What the reader says quotes the file, and the file is this person's own.
            throw new Refused(
                    Refused.CANNOT_SAY, "this machine's project file does not read", null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("project", project);
        answer.put("revision", null);
        answer.put("stale", false);
        answer.put("first_admin", null);
        answer.put("host", null);
        answer.put("opened", null);
        answer.put("address", null);
        answer.put("me", null);
        answer.put("members", List.of());
        answer.put("repositories", repositories("read", names(read)));
        answer.put("matrix_registration_entry", registrationEntry(read));
        return answer;
    }

    private static @Nullable String registrationEntry(final Project read) {
        return read.mail().transports().get("matrix") instanceof Map<?, ?> matrix
                ? LentEntry.name(REGISTRATION_TOKEN, matrix.get("homeserver"))
                : null;
    }

    /** A project of one repository names it as its own upstream, with no list of them. */
    private List<Map<String, @Nullable Object>> names(final Project read) {
        if (read.repositories().isEmpty() && read.upstream() != null) {
            return List.of(name(project, read.upstream()));
        }
        return read.repositories().stream()
                .map(repository -> name(repository.name(), repository.upstream()))
                .toList();
    }

    private static Map<String, @Nullable Object> name(
            final String name, final @Nullable String upstream) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("upstream", upstream);
        return row;
    }

    private static Map<String, @Nullable Object> repositories(
            final String state, final List<Map<String, @Nullable Object>> names) {
        final Map<String, @Nullable Object> repositories = new LinkedHashMap<>();
        repositories.put("state", state);
        repositories.put("revision", null);
        repositories.put("names", names);
        return repositories;
    }
}
