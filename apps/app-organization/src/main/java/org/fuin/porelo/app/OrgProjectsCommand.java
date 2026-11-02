package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/**
 * Lists the organization's projects this person is in, then those registered on this machine alone,
 * and {@code default}: what {@code project list} answers once nobody follows by hand.
 */
@Command(
        name = "projects",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Lists the projects of the organization this person is in, then this machine's.")
public class OrgProjectsCommand extends OrgReading {

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        final List<String> named = new ArrayList<>();
        @Nullable String revision = null;
        boolean stale = false;
        if (OrgAccess.repository(machine).isPresent()) {
            final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
            final OrganizationQuestions state = reading.answers();
            revision = state.revision();
            stale = reading.stale();
            final Optional<String> login = OrgAccess.ownLogin(machine);
            for (final OrganizationQuestions.Project project : state.getProjects()) {
                named.add(project.project());
                login.flatMap(own -> listed(state, project, own)).ifPresent(rows::add);
            }
        }
        for (final String local : OrgAccess.localProjects(machine).keySet()) {
            if (!named.contains(local)) {
                rows.add(other(local, "local"));
            }
        }
        rows.add(other(DefaultProject.NAME, "built-in"));
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", revision);
        answer.put("stale", stale);
        answer.put("projects", rows);
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        staleness(answer);
        if (answer.get("projects") instanceof List<?> rows) {
            for (final Object row : rows) {
                if (row instanceof Map<?, ?> project) {
                    line(String.valueOf(project.get("project")));
                }
            }
        }
    }

    /** A locked member stays listed here, saying so, and may do nothing there. */
    private static Optional<Map<String, @Nullable Object>> listed(
            final OrganizationQuestions state,
            final OrganizationQuestions.Project project,
            final String login) {
        return state.getProjectMembers(project.project()).orElse(List.of()).stream()
                .filter(member -> member.login().equals(login))
                .findFirst()
                .map(
                        member -> {
                            final Map<String, @Nullable Object> row = new LinkedHashMap<>();
                            row.put("project", project.project());
                            row.put("source", "organization");
                            row.put("role", member.role());
                            row.put("locked", member.locked());
                            row.put("host", project.host());
                            row.put("opened", project.filed());
                            return row;
                        });
    }

    private static Map<String, @Nullable Object> other(final String name, final String source) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("project", name);
        row.put("source", source);
        row.put("role", null);
        row.put("locked", false);
        row.put("host", null);
        row.put("opened", null);
        return row;
    }
}
