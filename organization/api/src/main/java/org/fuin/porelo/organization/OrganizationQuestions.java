package org.fuin.porelo.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The questions of the new structure, each answered at one revision, so that several answers rest
 * on one state. Each adapter answers each question itself; the questions are the contract.
 */
public interface OrganizationQuestions {

    /**
     * Returns the revision these answers rest on.
     *
     * @return It, or {@code null} when nothing could be accepted yet.
     */
    @Nullable String revision();

    /**
     * Where a host is reached.
     *
     * @param address A host name or an IP address.
     * @param sshPort The port its ssh listens on.
     * @param hostKey The key its ssh answers with, as {@code known_hosts} holds it.
     */
    record Reach(String address, int sshPort, String hostKey) {}

    /**
     * The organization, in words to show.
     *
     * @param name Its name.
     * @param keptIn Where it is kept.
     * @param vouchedBy Who says whose keys are whose.
     */
    record Description(String name, String keptIn, String vouchedBy) {}

    /**
     * Whether a key is trusted for a login.
     *
     * @param answer {@link #YES}, {@link #NO} or {@link #CANNOT_SAY}.
     * @param why Why not, or "".
     */
    record Trust(String answer, String why) {

        /** The key is the login's. */
        public static final String YES = "yes";

        /** The key is not the login's. */
        public static final String NO = "no";

        /** Nobody can say yet, which is never read as no or as yes. */
        public static final String CANNOT_SAY = "cannot-say";
    }

    /**
     * One key a project's member signs with, or signed with.
     *
     * @param login The member.
     * @param role Their role.
     * @param key The key, {@code ssh-ed25519 AAAA...}.
     * @param validFrom From when, as the card says.
     * @param validUntil Until when, as the card says.
     * @param revokedSince When it was replaced or revoked, or {@code null} while it is in force.
     *     The signer of the change writes this time, so it only serves to read old changes: a new
     *     one is judged against the keys in force when it arrives.
     */
    record SigningKey(
            String login,
            String role,
            String key,
            LocalDate validFrom,
            LocalDate validUntil,
            @Nullable String revokedSince) {}

    /**
     * A person, as their card last accepted says.
     *
     * @param login The login.
     * @param name What they are called.
     * @param email How to reach them, or "".
     * @param key Their key.
     * @param fingerprint The key's fingerprint.
     * @param state {@link Organization#TRUSTED}, {@link Organization#FIRST_SEEN} or {@link
     *     Organization#REFUSED}, which is answered but is no card.
     * @param expires Until when the card says it holds.
     * @param revoked Whether its key, or one it came from, is revoked.
     * @param why Why it is refused, or "".
     */
    record Person(
            String login,
            String name,
            String email,
            String key,
            String fingerprint,
            String state,
            LocalDate expires,
            boolean revoked,
            String why) {}

    /**
     * A project.
     *
     * @param project Its name.
     * @param admins The logins listed with role admin, locked ones among them.
     * @param members The other logins listed.
     * @param firstAdmin The first admin an owner named.
     * @param filed Whether its repository's address is filed.
     * @param host The host carrying it, or {@code null}.
     */
    record Project(
            String project,
            List<String> admins,
            List<String> members,
            String firstAdmin,
            boolean filed,
            @Nullable String host) {}

    /**
     * A member of a project, as listed.
     *
     * @param login The login.
     * @param role The role.
     * @param locked Whether they are listed but may nothing.
     */
    record Member(String login, String role, boolean locked) {}

    /**
     * A host.
     *
     * @param host Its name.
     * @param card What its card says, or {@code null} until its admins say.
     * @param admins One row per key, backup keys among them: an admin with no accepted card has
     *     none.
     * @param projects The projects it carries.
     */
    record Host(
            String host,
            Organization.@Nullable Card card,
            List<Organization.Owner> admins,
            List<String> projects) {}

    /**
     * Returns whether a login holds the organization's owner place with a key of its own: a backup
     * key alone holds none.
     *
     * @param login The login.
     * @return Whether it is an owner.
     */
    boolean isOrganizationOwner(String login);

    /**
     * Returns the role a login may act in within a project. A locked member is listed, but may
     * nothing, so it holds none here.
     *
     * @param project The project's name.
     * @param login The login.
     * @return The role, {@code admin} or {@code developer}; empty for anyone else, and for a
     *     project that does not exist.
     */
    Optional<String> isProjectMember(String project, String login);

    /**
     * Returns whether a login is an admin of a host with a key of its own: a backup key alone holds
     * no such place.
     *
     * @param host The host's name.
     * @param login The login.
     * @return Whether it is one.
     */
    boolean isHostAdmin(String host, String login);

    /**
     * Returns who may act as a project's admin: its members with that role, none of them locked.
     *
     * @param project The project's name.
     * @return The logins, or empty when there is no such project.
     */
    Optional<List<String>> projectAdmins(String project);

    /**
     * Returns where a host is reached.
     *
     * @param host The host's name.
     * @return Where, or empty when its admins have not said yet, or there is no such host.
     */
    Optional<Reach> reach(String host);

    /**
     * Returns the hosts carrying a project this login may act in.
     *
     * @param login The login.
     * @return The hosts' names, sorted.
     */
    List<String> hostsFor(String login);

    /**
     * Returns the one host carrying a project.
     *
     * @param project The project's name.
     * @return The host's name, or empty when none carries it.
     */
    Optional<String> hostOf(String project);

    /**
     * Returns the address of a project's repository. It is a pointer, which nothing here opens.
     *
     * @param project The project's name.
     * @return The address, or empty until one is filed.
     */
    Optional<String> getProjectRepository(String project);

    /**
     * Returns what the read refused, the latest last.
     *
     * @return The refusals.
     */
    List<Organization.Refusal> getRefusals();

    /**
     * Returns what the read has to say of itself, in words to show.
     *
     * @return The warnings.
     */
    List<String> getWarnings();

    /**
     * Describes the organization.
     *
     * @return The words to show.
     */
    Description describe();

    /**
     * Returns whether a key is trusted for a login, as this state has it.
     *
     * @param login The login.
     * @param key The key, {@code ssh-ed25519 AAAA...}.
     * @return The answer.
     */
    Trust trusts(String login, String key);

    /**
     * Returns who holds a key on an accepted card.
     *
     * @param key The key, {@code ssh-ed25519 AAAA...}.
     * @return The login, or empty for nobody.
     */
    Optional<String> keyHolder(String key);

    /**
     * Returns the keys a project's members sign with, and their earlier ones, each saying since
     * when it no longer holds. A backup key, a locked member and a person with no accepted card get
     * no row.
     *
     * @param project The project's name.
     * @return The rows; none for a project that does not exist.
     */
    List<SigningKey> signingKeys(String project);

    /**
     * Returns everyone with a card, a refused one among them.
     *
     * @return The persons, by login.
     */
    List<Person> getPersons();

    /**
     * Returns the projects.
     *
     * @return The projects, by name.
     */
    List<Project> getProjects();

    /**
     * Returns a project's members as listed, locked ones among them.
     *
     * @param project The project's name.
     * @return The members, or empty when there is no such project.
     */
    Optional<List<Member>> getProjectMembers(String project);

    /**
     * Returns the hosts.
     *
     * @return The hosts, by name.
     */
    List<Host> getHosts();

    /**
     * Returns the organization's owners, one row per key, backup keys among them.
     *
     * @return The owners.
     */
    List<Organization.Owner> getOrganizationOwners();

    /**
     * Returns a person's card as text.
     *
     * @param login The login.
     * @return The card, or empty when none is held.
     */
    Optional<String> getCard(String login);

    /**
     * Returns a host's card as text.
     *
     * @param host The host's name.
     * @return The card, or empty when none is held.
     */
    Optional<String> getHostCard(String host);

    /**
     * Returns each place held by one key with no backup key: losing it leaves nobody who may change
     * that place.
     *
     * @return The places, the organization's first, then the hosts' by name.
     */
    List<Organization.Sole> getSoleWithoutBackup();
}
