package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.TreeServices;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Answers this machine's organization, as it stands. */
@Command(
        name = "org",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Answers the organization: its people, projects and hosts.",
        subcommands = {
            OrgJoinCommand.class,
            OrgCardCommand.class,
            OrgHostCardCommand.class,
            OrgFetchCommand.class,
            OrgRefreshCommand.class,
            OrgSignersCommand.class,
            OrgAddReadKeyCommand.class,
            OrgRemoveReadKeyCommand.class,
            OrgCreateCommand.class,
            OrgOwnerCommand.class,
            OrgAdminCommand.class,
            OrgPersonCommand.class,
            OrgProjectCommand.class,
            OrgHostCommand.class
        })
public class OrgCommand extends OrgReading {

    @Option(
            names = "--refresh",
            description = "Waits for a fresh organization check and answers its replacement value.")
    private boolean refresh;

    private static final String REVOKED = "revoked";

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final OrgAccess.Reading reading = display(machine, refresh);
        final Map<String, @Nullable Object> answer =
                answer(reading, OrgAccess.ownLogin(machine).orElse(null));
        final String address = OrgAccess.repository(machine).orElseThrow();
        answer.put("address", ForgeAddress.withoutUserinfo(address));
        answer.put("forge", ForgeAddress.of(address).forge().getHost());
        answer.put("settings", OrgAccess.settings(machine));
        return answer;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line("Organization: " + answer.get("name"));
        line("Kept in: " + answer.get("kept_in"));
        line(
                "Revision: "
                        + answer.get("revision")
                        + (Boolean.TRUE.equals(answer.get("stale")) ? " (stale)" : ""));
        staleness(answer);
    }

    /** The answer, in the order the contract names its fields. */
    private static Map<String, @Nullable Object> answer(
            final OrgAccess.Reading reading, final @Nullable String login) {
        final OrganizationQuestions state = reading.answers();
        final OrganizationQuestions.Description description = state.describe();
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("name", description.name());
        answer.put("kept_in", description.keptIn());
        answer.put("vouched_by", description.vouchedBy());
        answer.put("revision", state.revision());
        answer.put("stale", reading.stale());
        reading.age(answer);
        answer.put("me", me(state, login));
        answer.put(
                "owners", state.getOrganizationOwners().stream().map(OrgCommand::owner).toList());
        answer.put("organization_admins", state.getOrganizationAdmins().orElse(null));
        answer.put(
                "people",
                state.getPersons().stream()
                        .map(person -> person(person, reading.identities()))
                        .toList());
        answer.put("projects", state.getProjects().stream().map(OrgCommand::project).toList());
        answer.put("hosts", state.getHosts().stream().map(OrgCommand::host).toList());
        answer.put("warnings", state.getWarnings());
        answer.put(
                "refusals",
                state.getRefusals().stream()
                        .map(
                                refusal ->
                                        Map.of(
                                                "revision",
                                                refusal.revision(),
                                                "path",
                                                refusal.path(),
                                                "why",
                                                refusal.why()))
                        .toList());
        answer.put(
                "sole_without_backup",
                state.getSoleWithoutBackup().stream().map(OrgCommand::sole).toList());
        return answer;
    }

    private static @Nullable Map<String, @Nullable Object> me(
            final OrganizationQuestions state, final @Nullable String login) {
        final Optional<OrganizationQuestions.Person> held =
                state.getPersons().stream()
                        .filter(person -> person.login().equals(login))
                        .findFirst();
        if (login == null || held.isEmpty()) {
            return null;
        }
        final OrganizationQuestions.Person person = held.orElseThrow();
        final Map<String, @Nullable Object> me = new LinkedHashMap<>();
        me.put("login", login);
        me.put("card", person.revoked() ? REVOKED : person.state());
        me.put("owner", state.isOrganizationOwner(login));
        me.put("organization_admin", state.isOrganizationAdmin(login));
        me.put(
                "admin_of_hosts",
                state.getHosts().stream()
                        .map(OrganizationQuestions.Host::host)
                        .filter(host -> state.isHostAdmin(host, login))
                        .toList());
        final List<Map<String, String>> projects = new ArrayList<>();
        for (final OrganizationQuestions.Project project : state.getProjects()) {
            state.isProjectMember(project.project(), login)
                    .ifPresent(
                            role ->
                                    projects.add(
                                            Map.of("project", project.project(), "role", role)));
        }
        me.put("projects", projects);
        return me;
    }

    private static Map<String, @Nullable Object> owner(final Organization.Owner owner) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("login", owner.login());
        row.put("fingerprint", owner.fingerprint());
        row.put("backup", owner.backup());
        return row;
    }

    private static Map<String, @Nullable Object> person(
            final OrganizationQuestions.Person person, final @Nullable IdentitySource identities) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("login", person.login());
        row.put("name", person.name());
        row.put("email", person.email());
        row.put("fingerprint", person.fingerprint());
        row.put("state", person.state());
        row.put("vouched", vouched(person, identities));
        row.put("expires", person.expires().toString());
        row.put("revoked", person.revoked());
        row.put("why", person.why());
        return row;
    }

    /**
     * What the source says now; a stale read asked nobody, and a list in part proves no key
     * missing.
     */
    private static String vouched(
            final OrganizationQuestions.Person person, final @Nullable IdentitySource identities) {
        if (identities == null) {
            return OrganizationQuestions.Trust.CANNOT_SAY;
        }
        final Optional<IdentitySource.Keys> keys =
                identities.keys(person.login(), person.fingerprint());
        if (keys.isEmpty()) {
            return OrganizationQuestions.Trust.CANNOT_SAY;
        }
        if (PersonCard.lists(keys.orElseThrow().keys(), person.fingerprint())) {
            return OrganizationQuestions.Trust.YES;
        }
        return keys.orElseThrow().complete()
                ? OrganizationQuestions.Trust.NO
                : OrganizationQuestions.Trust.CANNOT_SAY;
    }

    private static Map<String, @Nullable Object> project(
            final OrganizationQuestions.Project project) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("project", project.project());
        row.put("admins", project.admins());
        row.put("members", project.members());
        row.put("first_admin", project.firstAdmin());
        row.put("opened", project.filed());
        row.put("host", project.host());
        return row;
    }

    private static Map<String, @Nullable Object> host(final OrganizationQuestions.Host host) {
        final Organization.Card card = host.card();
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("host", host.host());
        row.put("address", card == null ? null : card.address());
        row.put("ssh_port", card == null ? null : card.sshPort());
        row.put("ssh_host_key", card == null ? null : card.sshHostKey());
        row.put("services", card == null ? null : TreeServices.of(card));
        row.put(
                "admins",
                host.admins().stream()
                        .map(
                                admin ->
                                        Map.of(
                                                "login",
                                                admin.login(),
                                                "key",
                                                admin.key(),
                                                "fingerprint",
                                                admin.fingerprint(),
                                                "backup",
                                                admin.backup()))
                        .toList());
        row.put("projects", host.projects());
        row.put("repositories", new java.util.TreeMap<>(host.repositories()));
        return row;
    }

    private static Map<String, @Nullable Object> sole(final Organization.Sole sole) {
        final Map<String, @Nullable Object> row = new LinkedHashMap<>();
        row.put("role", sole.role());
        row.put("host", sole.host());
        row.put("login", sole.login());
        return row;
    }
}
