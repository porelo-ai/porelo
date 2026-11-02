package org.fuin.porelo.organization.gittree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.HostCard;
import org.jspecify.annotations.Nullable;

/**
 * What a walk has accepted so far; copied before each commit, which is judged against it.
 *
 * @param owners The entries of {@code owners.yml}.
 * @param people Each login's card.
 * @param projects Each project.
 * @param revoked The fingerprints of the keys revoked.
 * @param cards The blob last accepted at each card's path.
 * @param hosts Each host.
 */
record TreeModel(
        List<TreeFiles.Owner> owners,
        Map<String, Person> people,
        Map<String, ProjectState> projects,
        Set<String> revoked,
        Map<String, String> cards,
        Map<String, HostState> hosts) {

    /**
     * A person's card as it stands.
     *
     * @param fingerprint The fingerprint of its key.
     * @param state {@link Organization#TRUSTED}, {@link Organization#FIRST_SEEN} or {@link
     *     Organization#REFUSED}.
     * @param handedOn The keys it was handed on from, newest first, never its own.
     * @param predecessor The fingerprint of the key the card names as its predecessor, or "".
     */
    record Person(String fingerprint, String state, List<String> handedOn, String predecessor) {

        Person with(final String newState) {
            return new Person(fingerprint, newState, handedOn, predecessor);
        }
    }

    /**
     * A project as it stands.
     *
     * @param admin The first admin an owner named.
     * @param members Its members, or {@code null} before the first {@code members.yml}.
     * @param address The address of its repository, or {@code null} until one is filed.
     */
    record ProjectState(
            String admin, @Nullable List<TreeFiles.Member> members, @Nullable String address) {}

    /**
     * A host as it stands.
     *
     * @param file Its admins and what it carries.
     * @param card Its card, or {@code null} until an admin files one.
     */
    record HostState(TreeFiles.HostFile file, @Nullable HostCard card) {}

    static TreeModel empty() {
        return new TreeModel(
                new ArrayList<>(),
                new TreeMap<>(),
                new TreeMap<>(),
                new TreeSet<>(),
                new TreeMap<>(),
                new TreeMap<>());
    }

    TreeModel copy() {
        return new TreeModel(
                new ArrayList<>(owners),
                new TreeMap<>(people),
                new TreeMap<>(projects),
                new TreeSet<>(revoked),
                new TreeMap<>(cards),
                new TreeMap<>(hosts));
    }

    boolean owner(final String fingerprint) {
        return owners.stream()
                .anyMatch(owner -> owner.fingerprint().equals(fingerprint) && !owner.backup());
    }

    /**
     * Answers every question from this state.
     *
     * @param reached The commit it stands at.
     * @param refusals What was refused.
     * @param warnings What the read has to say of itself.
     * @return The answers.
     */
    GitTreeOrganization answers(
            final @Nullable String reached,
            final List<Organization.Refusal> refusals,
            final List<String> warnings) {
        final Set<String> ownerLogins = new LinkedHashSet<>();
        owners.stream()
                .filter(owner -> !owner.backup())
                .forEach(owner -> ownerLogins.add(owner.login()));
        final Map<String, List<TreeFiles.Member>> members = new TreeMap<>();
        final Map<String, String> addresses = new TreeMap<>();
        projects.forEach(
                (name, project) -> {
                    final List<TreeFiles.Member> listed = project.members();
                    members.put(name, listed == null ? List.of() : listed);
                    if (project.address() != null) {
                        addresses.put(name, project.address());
                    }
                });
        final Map<String, GitTreeOrganization.Host> answered = new TreeMap<>();
        hosts.forEach(
                (name, host) ->
                        answered.put(
                                name,
                                new GitTreeOrganization.Host(
                                        host.file().admins().stream()
                                                .filter(admin -> !admin.backup())
                                                .map(TreeFiles.HostAdmin::login)
                                                .collect(Collectors.toSet()),
                                        host.file().projects(),
                                        reach(host.card()))));
        final Set<String> holders = new TreeSet<>();
        people.forEach(
                (login, person) -> {
                    if (!person.state().equals(Organization.REFUSED)) {
                        holders.add(login);
                    }
                });
        return new GitTreeOrganization(
                reached, ownerLogins, members, addresses, answered, holders, refusals, warnings);
    }

    private static OrganizationQuestions.@Nullable Reach reach(final @Nullable HostCard card) {
        return card == null
                ? null
                : new OrganizationQuestions.Reach(
                        card.address(), card.sshPort(), card.sshHostKey());
    }
}
