package org.fuin.porelo.organization.gittree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * What one walk accepted, answering every question from that one state.
 *
 * @param revision The commit the walk reached.
 * @param model What it accepted, which nothing here changes.
 * @param refusals What the walk refused, the latest last.
 * @param warnings What the read has to say of itself.
 * @param description The words for the organization.
 */
record GitTreeOrganization(
        @Nullable String revision,
        TreeModel model,
        List<Organization.Refusal> refusals,
        List<String> warnings,
        Description description)
        implements OrganizationQuestions {

    GitTreeOrganization {
        model = model.copy();
        refusals = List.copyOf(refusals);
        warnings = List.copyOf(warnings);
    }

    @Override
    public Description describe() {
        return description;
    }

    @Override
    public Trust trusts(final String login, final String key) {
        final TreeModel.Person person = model.people().get(login);
        final String fingerprint = fingerprintOf(key);
        if (person == null || fingerprint == null || !fingerprint.equals(person.fingerprint())) {
            return new Trust(Trust.NO, "it is not the key on the login's card");
        }
        return switch (person.state()) {
            case Organization.TRUSTED -> new Trust(Trust.YES, "");
            case Organization.FIRST_SEEN ->
                    new Trust(Trust.CANNOT_SAY, "the identity source has not said yet");
            default -> new Trust(Trust.NO, why(login));
        };
    }

    @Override
    public Optional<String> keyHolder(final String key) {
        final String fingerprint = fingerprintOf(key);
        return model.people().entrySet().stream()
                .filter(
                        each ->
                                each.getValue().fingerprint().equals(fingerprint)
                                        && accepted(each.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Override
    public boolean isOrganizationOwner(final String login) {
        return model.owners().stream()
                .anyMatch(owner -> owner.login().equals(login) && !owner.backup());
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
        final TreeModel.HostState held = model.hosts().get(host);
        return held != null
                && holdsCard(login)
                && held.file().admins().stream()
                        .anyMatch(admin -> !admin.backup() && admin.login().equals(login));
    }

    @Override
    public Optional<List<String>> projectAdmins(final String project) {
        if (!model.projects().containsKey(project)) {
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
        return Optional.ofNullable(model.hosts().get(host))
                .map(TreeModel.HostState::card)
                .map(card -> new Reach(card.address(), card.sshPort(), card.sshHostKey()));
    }

    @Override
    public List<String> hostsFor(final String login) {
        return model.hosts().entrySet().stream()
                .filter(
                        host ->
                                host.getValue().file().projects().stream()
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
        return model.hosts().entrySet().stream()
                .filter(host -> host.getValue().file().projects().contains(project))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    @Override
    public List<SigningKey> signingKeys(final String project) {
        final List<SigningKey> rows = new ArrayList<>();
        acting(project).forEach(member -> addKeys(member, rows));
        return List.copyOf(rows);
    }

    private void addKeys(final TreeFiles.Member member, final List<SigningKey> rows) {
        final String login = member.login();
        final TreeModel.Person person = model.people().get(login);
        if (person == null) {
            return;
        }
        for (final TreeModel.EarlierKey earlier : person.earlier()) {
            rows.add(
                    new SigningKey(
                            login,
                            member.role(),
                            earlier.key(),
                            earlier.validFrom(),
                            earlier.validUntil(),
                            earlier.revokedSince()));
        }
        final PersonCard card = cardOf(login, person);
        rows.add(
                new SigningKey(
                        login, member.role(), card.key(), card.created(), card.expires(), null));
    }

    private static PersonCard cardOf(final String login, final TreeModel.Person person) {
        return PersonCard.read(person.card(), "people/" + login + ".card");
    }

    @Override
    public List<Person> getPersons() {
        return model.people().entrySet().stream()
                .map(each -> person(each.getKey(), each.getValue()))
                .toList();
    }

    private Person person(final String login, final TreeModel.Person person) {
        final PersonCard card = cardOf(login, person);
        final boolean refused = person.state().equals(Organization.REFUSED);
        return new Person(
                login,
                card.name(),
                card.email(),
                card.key(),
                person.fingerprint(),
                person.state(),
                card.expires(),
                model.revoked().contains(person.fingerprint()),
                refused ? why(login) : "");
    }

    @Override
    public List<Project> getProjects() {
        return model.projects().entrySet().stream()
                .map(
                        each -> {
                            final List<TreeFiles.Member> listed = listed(each.getValue());
                            return new Project(
                                    each.getKey(),
                                    logins(listed, true),
                                    logins(listed, false),
                                    each.getValue().admin(),
                                    each.getValue().address() != null,
                                    hostOf(each.getKey()).orElse(null));
                        })
                .toList();
    }

    private static List<String> logins(final List<TreeFiles.Member> listed, final boolean admins) {
        return listed.stream()
                .filter(member -> member.role().equals(TreeFiles.ADMIN) == admins)
                .map(TreeFiles.Member::login)
                .toList();
    }

    private static List<TreeFiles.Member> listed(final TreeModel.ProjectState project) {
        final List<TreeFiles.Member> members = project.members();
        return members == null ? List.of() : members;
    }

    @Override
    public Optional<String> getProjectRepository(final String project) {
        return Optional.ofNullable(model.projects().get(project))
                .map(TreeModel.ProjectState::address);
    }

    @Override
    public Optional<List<Member>> getProjectMembers(final String project) {
        return Optional.ofNullable(model.projects().get(project))
                .map(
                        held ->
                                listed(held).stream()
                                        .map(
                                                member ->
                                                        new Member(
                                                                member.login(),
                                                                member.role(),
                                                                member.locked()))
                                        .toList());
    }

    @Override
    public List<Host> getHosts() {
        return model.hosts().entrySet().stream()
                .map(
                        each ->
                                new Host(
                                        each.getKey(),
                                        card(each.getValue().card()),
                                        admins(each.getValue()),
                                        each.getValue().file().projects()))
                .toList();
    }

    private static Organization.@Nullable Card card(final @Nullable HostCard card) {
        return card == null
                ? null
                : new Organization.Card(
                        card.address(),
                        card.sshPort(),
                        card.sshHostKey(),
                        card.forgeWebPort(),
                        card.forgeGitPort(),
                        card.matrixPort(),
                        card.signer());
    }

    private List<Organization.Owner> admins(final TreeModel.HostState host) {
        final List<Organization.Owner> rows = new ArrayList<>();
        for (final TreeFiles.HostAdmin admin : host.file().admins()) {
            final String backup = admin.backupKey();
            if (backup != null) {
                rows.add(
                        new Organization.Owner(
                                admin.login(), backup, PersonCard.fingerprint(backup), true));
                continue;
            }
            final TreeModel.Person person = model.people().get(admin.login());
            if (person != null && accepted(person)) {
                final String key = cardOf(admin.login(), person).key();
                rows.add(
                        new Organization.Owner(
                                admin.login(), key, PersonCard.fingerprint(key), false));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public List<Organization.Owner> getOrganizationOwners() {
        return model.owners().stream()
                .map(
                        owner ->
                                new Organization.Owner(
                                        owner.login(),
                                        owner.key(),
                                        owner.fingerprint(),
                                        owner.backup()))
                .toList();
    }

    @Override
    public Optional<String> getCard(final String login) {
        return Optional.ofNullable(model.people().get(login)).map(TreeModel.Person::card);
    }

    @Override
    public Optional<String> getHostCard(final String host) {
        return Optional.ofNullable(model.hosts().get(host))
                .map(TreeModel.HostState::card)
                .map(card -> card.body() + card.signature());
    }

    @Override
    public List<Organization.Sole> getSoleWithoutBackup() {
        final List<Organization.Sole> sole = new ArrayList<>();
        final List<String> owners =
                model.owners().stream()
                        .filter(owner -> !owner.backup())
                        .map(TreeFiles.Owner::login)
                        .toList();
        if (owners.size() == 1
                && model.owners().stream()
                        .noneMatch(
                                owner ->
                                        owner.backup()
                                                && owner.login().equals(owners.getFirst()))) {
            sole.add(new Organization.Sole("owner", null, owners.getFirst()));
        }
        model.hosts()
                .forEach(
                        (host, state) -> {
                            final List<TreeFiles.HostAdmin> admins = state.file().admins();
                            final List<String> own =
                                    admins.stream()
                                            .filter(admin -> !admin.backup())
                                            .map(TreeFiles.HostAdmin::login)
                                            .toList();
                            if (own.size() == 1
                                    && admins.stream()
                                            .noneMatch(
                                                    admin ->
                                                            admin.backup()
                                                                    && admin.login()
                                                                            .equals(
                                                                                    own
                                                                                            .getFirst()))) {
                                sole.add(new Organization.Sole("host-admin", host, own.getFirst()));
                            }
                        });
        return List.copyOf(sole);
    }

    @Override
    public List<Organization.Refusal> getRefusals() {
        return refusals;
    }

    @Override
    public List<String> getWarnings() {
        return warnings;
    }

    /** The members who may act: not locked, and with an accepted card. */
    private Stream<TreeFiles.Member> acting(final String project) {
        final TreeModel.ProjectState held = model.projects().get(project);
        return (held == null ? List.<TreeFiles.Member>of() : listed(held))
                .stream().filter(member -> !member.locked() && holdsCard(member.login()));
    }

    private boolean holdsCard(final String login) {
        final TreeModel.Person person = model.people().get(login);
        return person != null && accepted(person);
    }

    private static boolean accepted(final TreeModel.Person person) {
        return !person.state().equals(Organization.REFUSED);
    }

    private String why(final String login) {
        final String path = "people/" + login + ".card";
        return refusals.reversed().stream()
                .filter(refusal -> refusal.path().equals(path))
                .map(Organization.Refusal::why)
                .findFirst()
                .orElse("the card's key is refused");
    }

    private static @Nullable String fingerprintOf(final String key) {
        try {
            return PersonCard.fingerprint(key);
        } catch (CardException | IllegalArgumentException ex) {
            return null;
        }
    }
}
