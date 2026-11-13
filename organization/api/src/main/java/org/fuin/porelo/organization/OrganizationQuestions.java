package org.fuin.porelo.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The questions of the new structure, each answered at one revision, so that several answers rest
 * on one state. Each adapter answers each question itself; the questions are the contract.
 *
 * <p>A change nobody allowed is no reason to refuse the read: the answers leave it out and name it
 * in {@link #getRefusals()}, and every later change is judged without it. The read itself is
 * refused with {@link Refused} only when the state as a whole cannot be taken: an older state
 * served again, a fork, or a founder's key the identity source no longer lists, each {@link
 * Refused#REFUSED_STATE}.
 */
public interface OrganizationQuestions {

    /**
     * Returns the revision these answers rest on.
     *
     * @return It, or {@code null} when nothing could be accepted yet.
     */
    @Nullable String revision();

    /** The adapter's plain no, for a program by its reason and for a person by its words. */
    final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** This machine has no organization. */
        public static final String NO_ORGANIZATION = "no-organization";

        /** Options or arguments are wrong. */
        public static final String USAGE = "usage";

        /**
         * Porelo changes no project file that exists: the words name the file and hold the entry a
         * person writes into it.
         */
        public static final String BY_HAND = "by-hand";

        /** The acting person does not hold the role. */
        public static final String NOT_ALLOWED = "not-allowed";

        /** The base is not the revision the store is at. */
        public static final String MOVED_ON = "moved-on";

        /** The acting member is locked. */
        public static final String LOCKED = "locked";

        /** What is added is already there, under another id. */
        public static final String EXISTS = "exists";

        /** It would remove the last owner, the last host admin or a project's last admin. */
        public static final String LAST_OF_ITS_KIND = "last-of-its-kind";

        /** A project may not carry this name. */
        public static final String RESERVED_NAME = "reserved-name";

        /** The project is already on another host. */
        public static final String ON_ANOTHER_HOST = "on-another-host";

        /** The person named has no accepted card. */
        public static final String NO_CARD = "no-card";

        /** What was read is refused: a change nobody allowed, an older state served again. */
        public static final String REFUSED_STATE = "refused-state";

        /** No such person, project or host. */
        public static final String NOT_FOUND = "not-found";

        /** The project has no address yet. */
        public static final String NOT_OPENED = "not-opened";

        /** The organization's repository does not exist. */
        public static final String NO_REPOSITORY = "no-repository";

        /** The store or the identity source could not be reached or could not say: never a no. */
        public static final String CANNOT_SAY = "cannot-say";

        /** The forge's ssh host key is not one this machine trusts: the person's question. */
        public static final String UNKNOWN_HOST_KEY = "unknown-host-key";

        /** The agent does not serve the key that must sign. */
        public static final String NO_KEY = "no-key";

        /** The vault is shut, and a token it holds is needed. */
        public static final String VAULT_SHUT = "vault-shut";

        private final String reason;

        private final @Nullable String revision;

        private final @Nullable String entry;

        /**
         * Makes the refusal.
         *
         * @param reason Why, one of this class's reasons.
         * @param words What to say to a person, which quotes nothing a file held.
         * @param revision Where the store is, when that is what matters, or {@code null}.
         */
        public Refused(final String reason, final String words, final @Nullable String revision) {
            this(reason, words, revision, null);
        }

        /**
         * Makes a refusal that holds the entry a person writes instead.
         *
         * @param reason Why, one of this class's reasons.
         * @param words What to say to a person, which quotes nothing a file held.
         * @param revision Where the store is, when that is what matters, or {@code null}.
         * @param entry The YAML a person writes into the file, or {@code null} when there is none.
         */
        public Refused(
                final String reason,
                final String words,
                final @Nullable String revision,
                final @Nullable String entry) {
            super(words);
            this.reason = reason;
            this.revision = revision;
            this.entry = entry;
        }

        /**
         * Returns why, for a program.
         *
         * @return The reason.
         */
        public String reason() {
            return reason;
        }

        /**
         * Returns where the store is, when that is what matters.
         *
         * @return The revision, or {@code null}.
         */
        public @Nullable String revision() {
            return revision;
        }

        /**
         * Returns the YAML a person writes into the file instead, for a program.
         *
         * @return The entry, or {@code null}.
         */
        public @Nullable String entry() {
            return entry;
        }
    }

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
     * @param projects The projects it carries, wholly or by some of their repositories.
     * @param repositories For each project it carries by some of its repositories, their names; a
     *     project not here it carries whole.
     */
    record Host(
            String host,
            Organization.@Nullable Card card,
            List<Organization.Owner> admins,
            List<String> projects,
            java.util.Map<String, List<String>> repositories) {}

    /**
     * Returns whether a login holds the organization's owner place with a key of its own: a backup
     * key alone holds none.
     *
     * @param login The login.
     * @return Whether it is an owner.
     */
    boolean isOrganizationOwner(String login);

    /**
     * Returns whether a login holds the organization admin's place. Before anybody is listed, the
     * organization runs by the rules from before that role, under which an owner does this work.
     *
     * @param login The login.
     * @return Whether it does this work now.
     */
    boolean isOrganizationAdmin(String login);

    /**
     * Returns who is listed as an organization admin.
     *
     * @return The logins, or empty when the organization has no such list yet.
     */
    Optional<List<String>> getOrganizationAdmins();

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
     * Returns a host carrying a project, whole or by some of its repositories; with several, one of
     * them.
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
     * Tells whether this reaches the newest state: the identity source could not say enough for a
     * read to go past a commit, so what came after it, a revocation too, is not in this answer.
     *
     * @return Whether nothing was left unread.
     */
    default boolean whole() {
        return true;
    }

    /**
     * Where a read that did not reach the newest state waits, and what for. Nothing in it is the
     * forge's: the words and the kind are Porelo's own and the revision is a hash.
     *
     * @param revision The revision the read could not decide.
     * @param words What it waits for.
     * @param kind Why the identity source could not say, or {@code null} when it was not asked.
     */
    record Waiting(String revision, String words, @Nullable String kind) {

        /**
         * Returns the words to show: {@code waits at <short revision>: <words> (<kind>)}.
         *
         * @return The words.
         */
        public String said() {
            return "waits at "
                    + revision.substring(0, Math.min(12, revision.length()))
                    + ": "
                    + words
                    + (kind == null ? "" : " (" + kind + ")");
        }
    }

    /**
     * Returns where a read that is not {@link #whole()} waits.
     *
     * @return The step it waits at, or empty when it reached the newest state or cannot say.
     */
    default Optional<Waiting> waiting() {
        return Optional.empty();
    }

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
