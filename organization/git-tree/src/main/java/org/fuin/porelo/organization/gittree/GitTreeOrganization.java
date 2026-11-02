package org.fuin.porelo.organization.gittree;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;

/**
 * What one walk accepted, answering every question from that one state.
 *
 * @param revision The commit the walk reached.
 * @param owners The logins holding an owner's place with a key of their own.
 * @param members Each project's members.
 * @param refusals What the walk refused, the latest last.
 */
record GitTreeOrganization(
        @Nullable String revision,
        Set<String> owners,
        Map<String, List<TreeFiles.Member>> members,
        List<Organization.Refusal> refusals)
        implements OrganizationQuestions {

    GitTreeOrganization {
        owners = Set.copyOf(owners);
        members = Map.copyOf(members);
        refusals = List.copyOf(refusals);
    }

    @Override
    public boolean isOrganizationOwner(final String login) {
        return owners.contains(login);
    }

    @Override
    public Optional<String> isProjectMember(final String project, final String login) {
        return members.getOrDefault(project, List.of()).stream()
                .filter(member -> member.login().equals(login) && !member.locked())
                .map(TreeFiles.Member::role)
                .findFirst();
    }

    @Override
    public List<Organization.Refusal> getRefusals() {
        return refusals;
    }
}
