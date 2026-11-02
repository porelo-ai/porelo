package org.fuin.porelo.organization.gittree;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;

/**
 * What one walk accepted, answering every question from that one state.
 *
 * @param revision The commit the walk reached.
 * @param owners The logins holding an owner's place with a key of their own.
 * @param members Each project's members.
 * @param addresses The address of each project's repository that has one.
 * @param hosts Each host.
 * @param holders The logins with an accepted card, the only ones a member's or a host admin's place
 *     counts for.
 * @param refusals What the walk refused, the latest last.
 */
record GitTreeOrganization(
        @Nullable String revision,
        Set<String> owners,
        Map<String, List<TreeFiles.Member>> members,
        Map<String, String> addresses,
        Map<String, Host> hosts,
        Set<String> holders,
        List<Organization.Refusal> refusals)
        implements OrganizationQuestions {

    /**
     * A host as answered.
     *
     * @param admins The logins holding its admin's place with a key of their own.
     * @param projects The projects it carries.
     * @param reach Where it is reached, or {@code null} until its admins say.
     */
    record Host(Set<String> admins, List<String> projects, @Nullable Reach reach) {}

    GitTreeOrganization {
        owners = Set.copyOf(owners);
        members = Map.copyOf(members);
        addresses = Map.copyOf(addresses);
        hosts = Map.copyOf(hosts);
        holders = Set.copyOf(holders);
        refusals = List.copyOf(refusals);
    }

    @Override
    public boolean isOrganizationOwner(final String login) {
        return owners.contains(login);
    }

    @Override
    public Optional<String> isProjectMember(final String project, final String login) {
        return acting(project)
                .filter(member -> member.login().equals(login))
                .map(TreeFiles.Member::role)
                .findFirst();
    }

    @Override
    public boolean isHostAdmin(final String host, final String login) {
        final Host held = hosts.get(host);
        return held != null && held.admins().contains(login) && holders.contains(login);
    }

    @Override
    public Optional<List<String>> projectAdmins(final String project) {
        if (!members.containsKey(project)) {
            return Optional.empty();
        }
        return Optional.of(
                acting(project)
                        .filter(member -> member.role().equals(TreeFiles.ADMIN))
                        .map(TreeFiles.Member::login)
                        .toList());
    }

    @Override
    public Optional<Reach> reach(final String host) {
        return Optional.ofNullable(hosts.get(host)).map(Host::reach);
    }

    @Override
    public List<String> hostsFor(final String login) {
        return hosts.entrySet().stream()
                .filter(
                        host ->
                                host.getValue().projects().stream()
                                        .anyMatch(
                                                project ->
                                                        isProjectMember(project, login)
                                                                .isPresent()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @Override
    public Optional<String> hostOf(final String project) {
        return hosts.entrySet().stream()
                .filter(host -> host.getValue().projects().contains(project))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Override
    public Optional<String> getProjectRepository(final String project) {
        return Optional.ofNullable(addresses.get(project));
    }

    @Override
    public List<Organization.Refusal> getRefusals() {
        return refusals;
    }

    /** The members who may act: not locked, and with an accepted card. */
    private Stream<TreeFiles.Member> acting(final String project) {
        return members.getOrDefault(project, List.of()).stream()
                .filter(member -> !member.locked() && holders.contains(member.login()));
    }
}
