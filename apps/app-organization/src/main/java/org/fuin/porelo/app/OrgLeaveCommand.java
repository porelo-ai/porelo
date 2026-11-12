package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;

/** Withdraws the acting person's card and writable roles, then forgets the organization here. */
@Command(
        name = "leave",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Leaves the organization and forgets it on this machine.")
public class OrgLeaveCommand extends OrgWriting {

    private @Nullable OrgDeparture departure;

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        return FileLocks.holding(OrgAccess.settingsLock(machine), () -> leave(machine));
    }

    private Map<String, @Nullable Object> leave(final PoreloContext machine) throws IOException {
        departure = OrgDeparture.read(machine);
        final OrgDeparture pending = departure;
        if (pending != null) {
            pending.check(machine);
            resume(pending.base(), pending.id());
        }
        if (pending == null || !pending.pushed()) {
            super.answer(machine);
        }
        final OrgDeparture done =
                Objects.requireNonNull(departure, "a successful write keeps its departure");
        done.forget(machine);
        return done.answer();
    }

    @Override
    OrganizationWrites.Written write(
            final PoreloContext machine,
            final Session opened,
            final OrganizationWrites.Change change)
            throws IOException {
        OrgDeparture planned = departure;
        final boolean firstTry = planned == null;
        if (planned == null) {
            final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
            if (reading.stale() || !reading.answers().whole()) {
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the organization cannot be checked: ask again when it is reachable",
                        null);
            }
            final OrganizationQuestions state = reading.answers();
            final String login = opened.acting().login();
            final String signer = PersonCard.fingerprint(opened.acting().key());
            // The readers judge an owner by the key that signs, so only an entry of this key is one
            // this person can take out; one of an older key stays listed for an organization admin.
            final boolean owner =
                    state.getOrganizationOwners().stream()
                            .anyMatch(
                                    row ->
                                            !row.backup()
                                                    && row.login().equals(login)
                                                    && row.fingerprint().equals(signer));
            final boolean listedOwner =
                    !owner
                            && state.getOrganizationOwners().stream()
                                    .anyMatch(row -> !row.backup() && row.login().equals(login));
            if (owner && !anotherOwnerCanAct(state, login)) {
                throw lastOwner(state, login);
            }
            final boolean admin = state.getOrganizationAdmins().orElse(List.of()).contains(login);
            final List<String> left = new ArrayList<>();
            if (owner) left.add("owner");
            if (owner && admin) left.add("organization-admin");
            planned =
                    new OrgDeparture(
                            OrgAccess.repository(machine).orElseThrow(),
                            login,
                            PersonCard.fingerprint(opened.acting().key()),
                            Objects.requireNonNull(
                                    change.base(), "leaving stands on an accepted revision"),
                            change.id(),
                            left,
                            stillListed(state, login, owner, listedOwner, admin),
                            false);
            planned.write(machine);
            departure = planned;
        }
        final OrgDeparture leaving = planned;
        final OrganizationWrites.Written written;
        try {
            written =
                    opened.writes()
                            .together(
                                    change,
                                    "Leave the organization",
                                    writes -> {
                                        // The admin removal still needs the owner's signature.
                                        if (leaving.left().contains("organization-admin")) {
                                            writes.removeOrganizationAdmin(change, leaving.login());
                                        }
                                        if (leaving.left().contains("owner")) {
                                            writes.removeOrganizationOwner(change, leaving.login());
                                        }
                                        writes.revokeCard(change, leaving.login());
                                    });
        } catch (Refused refused) {
            // A retry's preflight refusal says nothing about whether its earlier push landed;
            // moved-on
            // is answered only once the store has seen that this change is not on main.
            if (Refused.MOVED_ON.equals(refused.reason())
                    || firstTry && !Refused.CANNOT_SAY.equals(refused.reason())) {
                OrgDeparture.remove(machine);
                departure = null;
            }
            throw refused;
        }
        final OrgDeparture pushed = leaving.afterPush();
        pushed.write(machine);
        departure = pushed;
        return written;
    }

    /**
     * Says whether another owner could still act: an entry, not a backup, of another login whose
     * card is trusted, unrevoked and of the key the entry names.
     */
    private static boolean anotherOwnerCanAct(
            final OrganizationQuestions state, final String login) {
        return state.getOrganizationOwners().stream()
                .filter(row -> !row.backup() && !row.login().equals(login))
                .anyMatch(
                        row ->
                                state.getPersons().stream()
                                        .anyMatch(
                                                person ->
                                                        person.login().equals(row.login())
                                                                && !person.revoked()
                                                                && Organization.TRUSTED.equals(
                                                                        person.state())
                                                                && person.fingerprint()
                                                                        .equals(
                                                                                row
                                                                                        .fingerprint())));
    }

    private static Refused lastOwner(final OrganizationQuestions state, final String login) {
        final List<String> trusted =
                state.getPersons().stream()
                        .filter(
                                person ->
                                        !person.login().equals(login)
                                                && !person.revoked()
                                                && Organization.TRUSTED.equals(person.state()))
                        .map(OrganizationQuestions.Person::login)
                        .sorted()
                        .toList();
        final List<String> admins =
                state.getOrganizationAdmins().orElse(List.of()).stream()
                        .filter(trusted::contains)
                        .sorted()
                        .toList();
        final List<String> candidates = admins.isEmpty() ? trusted : admins;
        return new Refused(
                Refused.LAST_OF_ITS_KIND,
                "you are the last owner: name another owner before leaving"
                        + (candidates.isEmpty()
                                ? "; first have another person file a trusted card"
                                : "; candidates "
                                        + (admins.isEmpty()
                                                ? "holding trusted cards: "
                                                : "among organization admins: ")
                                        + String.join(", ", candidates)),
                state.revision());
    }

    private static List<Map<String, String>> stillListed(
            final OrganizationQuestions state,
            final String login,
            final boolean owner,
            final boolean listedOwner,
            final boolean admin) {
        final List<Map<String, String>> rows = new ArrayList<>();
        if (listedOwner) {
            rows.add(Map.of("file", "owners.yml", "role", "owner"));
        }
        if (admin && !owner) {
            rows.add(Map.of("file", "organization-admins.yml", "role", "organization-admin"));
        }
        for (final OrganizationQuestions.Host host : state.getHosts()) {
            if (host.admins().stream().anyMatch(row -> row.login().equals(login))) {
                rows.add(
                        Map.of("file", "hosts/" + host.host() + "/host.yml", "role", "host-admin"));
            }
        }
        for (final OrganizationQuestions.Project project : state.getProjects()) {
            for (final OrganizationQuestions.Member member :
                    state.getProjectMembers(project.project()).orElse(List.of())) {
                if (member.login().equals(login)) {
                    rows.add(
                            Map.of(
                                    "file",
                                    "projects/" + project.project() + "/members.yml",
                                    "role",
                                    "admin".equals(member.role()) ? "project-admin" : "developer"));
                }
            }
        }
        return rows.stream()
                .sorted(java.util.Comparator.comparing(row -> row.get("file")))
                .toList();
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line(
                "Left "
                        + answer.get("organization")
                        + " as "
                        + answer.get("login")
                        + "; organization forgotten on this machine.");
        final OrgDeparture done =
                Objects.requireNonNull(departure, "a successful leave has its receipt");
        for (final Map<String, String> row : done.stillListed()) {
            line(
                    "Still listed: "
                            + row.get("file")
                            + " ("
                            + row.get("role")
                            + "); ask an organization admin to remove the entry.");
        }
    }
}
