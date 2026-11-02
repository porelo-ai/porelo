package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;

/**
 * An organization as one host takes it: fetched with the host's own key, judged by the rules a
 * person's machine judges with, and held to the host's pin.
 */
public final class GitTreeOffline implements OfflineOrganization {

    private final GitTreeStore store;

    private final IdentitySource identities;

    /**
     * Makes the host's reader.
     *
     * @param store The store, opened with the host's key.
     * @param identities The forge's public key list, asked with no credential.
     */
    public GitTreeOffline(final GitTreeStore store, final IdentitySource identities) {
        this.store = store;
        this.identities = identities;
    }

    @Override
    public Map<String, Object> check(final String host, final String pinned, final String since)
            throws IOException {
        return check(host, Set.of(pinned), since);
    }

    @Override
    public Map<String, Object> check(
            final String host, final Set<String> pinned, final String since) throws IOException {
        final OrganizationQuestions state = store.fetchFor(identities, host, pinned, since);
        final OrganizationQuestions.Host held =
                state.getHosts().stream()
                        .filter(each -> each.host().equals(host))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NOT_FOUND,
                                                "the organization has no such host",
                                                state.revision()));
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("revision", String.valueOf(state.revision()));
        answer.put("host_card", hostCard(held));
        answer.put("admins", held.admins().stream().map(GitTreeOffline::admin).toList());
        answer.put("persons", persons(state, held));
        answer.put("projects", held.projects().stream().map(name -> project(state, name)).toList());
        return answer;
    }

    private static Map<String, @Nullable Object> hostCard(final OrganizationQuestions.Host held) {
        final Organization.Card card = held.card();
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("host", held.host());
        row.put("address", card == null ? null : card.address());
        row.put("ssh_port", card == null ? null : card.sshPort());
        row.put("ssh_host_key", card == null ? null : card.sshHostKey());
        row.put("services", card == null ? null : TreeServices.of(card));
        return row;
    }

    private static Map<String, Object> admin(final Organization.Owner admin) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("login", admin.login());
        row.put("key", admin.key());
        row.put("fingerprint", admin.fingerprint());
        row.put("backup", admin.backup());
        return row;
    }

    /**
     * The persons this host needs, and no other. A refused card is no card, but a revoked one is
     * said, since a host must drop it.
     */
    private static List<Map<String, Object>> persons(
            final OrganizationQuestions state, final OrganizationQuestions.Host held) {
        final Set<String> needed = new TreeSet<>();
        held.admins().forEach(admin -> needed.add(admin.login()));
        for (final String project : held.projects()) {
            state.getProjectMembers(project)
                    .orElse(List.of())
                    .forEach(member -> needed.add(member.login()));
        }
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final OrganizationQuestions.Person person : state.getPersons()) {
            if (needed.contains(person.login())
                    && (person.revoked() || !Organization.REFUSED.equals(person.state()))) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("login", person.login());
                row.put("key", person.key());
                row.put("expires", person.expires().toString());
                row.put("revoked", person.revoked());
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, @Nullable Object> project(
            final OrganizationQuestions state, final String project) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("project", project);
        row.put("address", state.getProjectRepository(project).orElse(null));
        row.put(
                "members",
                state.getProjectMembers(project).orElse(List.of()).stream()
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
        row.put(
                "signing_keys",
                state.signingKeys(project).stream().map(GitTreeOffline::signingKey).toList());
        return row;
    }

    private static Map<String, @Nullable Object> signingKey(
            final OrganizationQuestions.SigningKey key) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("login", key.login());
        row.put("role", key.role());
        row.put("key", key.key());
        row.put("valid_from", key.validFrom().toString());
        row.put("valid_until", key.validUntil().toString());
        row.put("revoked_since", key.revokedSince());
        return row;
    }
}
