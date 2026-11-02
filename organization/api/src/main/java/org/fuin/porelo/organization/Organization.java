package org.fuin.porelo.organization;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What an organization answers at one revision: every question rests on the same state. Each
 * adapter answers each question itself; the questions are the contract, and how an adapter finds an
 * answer is its own. Plain data: it says nothing of how the organization is kept.
 */
public interface Organization {

    /** A person's standing: the identity source lists their key. */
    public static final String TRUSTED = "trusted";

    /** A person's standing: accepted before the identity source could say. */
    public static final String FIRST_SEEN = "first-seen";

    /** A person's standing: answered, but no card; the next one for the login counts as first. */
    public static final String REFUSED = "refused";

    /**
     * Returns the revision the read reached.
     *
     * @return It, or {@code null} when it reached none.
     */
    @Nullable String readTo();

    /**
     * Returns the revision it accepted, the same as {@link #readTo} once anything was accepted.
     *
     * @return It, or {@code null} when nothing was.
     */
    @Nullable String revision();

    /**
     * Returns the organization's owners, backup keys among them.
     *
     * @return The owners.
     */
    List<Owner> owners();

    /**
     * Returns each host's admins, backup keys among them.
     *
     * @return The admins, by the host's name.
     */
    Map<String, List<Owner>> hostAdmins();

    /**
     * Returns the persons, each as their card last accepted says.
     *
     * @return The persons.
     */
    List<Person> people();

    /**
     * Returns the hosts.
     *
     * @return The hosts.
     */
    List<Host> hosts();

    /**
     * Returns the fingerprints of the keys it revokes.
     *
     * @return The fingerprints.
     */
    List<String> revoked();

    /**
     * Returns what the read warns of.
     *
     * @return The warnings.
     */
    List<String> warnings();

    /**
     * Returns what the read refused, the latest last.
     *
     * @return The refusals.
     */
    List<Refusal> refusals();

    /**
     * Returns what the read refused last, which stays in force until something replaces it.
     *
     * @return The refusal, or {@code null} when none.
     */
    @Nullable Refusal lastRefusal();

    /**
     * Returns each place held by one key with no backup key behind it.
     *
     * @return The places.
     */
    List<Sole> soleWithoutBackup();

    /**
     * Returns who holds a key on an accepted card, another than the login given.
     *
     * @param fingerprint The key's fingerprint.
     * @param login The login that may hold it, or {@code null} when any login is another.
     * @return Their login, or {@code null} when nobody else holds it.
     */
    @Nullable String keyHolder(String fingerprint, @Nullable String login);

    /**
     * A path a revision was refused for.
     *
     * @param revision The revision.
     * @param path The path.
     * @param why Why, printable and quoting nothing the file holds.
     */
    public record Refusal(String revision, String path, String why) {}

    /**
     * An owner or a host admin.
     *
     * @param login Their login.
     * @param key Their key, {@code ssh-ed25519 AAAA...}.
     * @param fingerprint The key's fingerprint.
     * @param backup Whether it is an offline backup key.
     */
    public record Owner(String login, String key, String fingerprint, boolean backup) {}

    /**
     * A place held by one key with no backup key behind it.
     *
     * @param role What the place is.
     * @param host The host, or {@code null} for the organization's own.
     * @param login Who holds it.
     */
    public record Sole(String role, @Nullable String host, String login) {}

    /**
     * A person, as their card last accepted says.
     *
     * @param login The login, the identity.
     * @param name What they are called.
     * @param email How to reach them, or "".
     * @param key Their key, {@code ssh-ed25519 AAAA...}.
     * @param fingerprint The key's fingerprint.
     * @param state {@link #TRUSTED}, {@link #FIRST_SEEN} or {@link #REFUSED}.
     * @param why Why it is refused, or "".
     */
    public record Person(
            String login,
            String name,
            String email,
            String key,
            String fingerprint,
            String state,
            String why) {

        /**
         * Returns whether this is a refused card, which is answered but is no card.
         *
         * @return Whether it was refused.
         */
        public boolean refused() {
            return REFUSED.equals(state);
        }
    }

    /**
     * A host.
     *
     * @param host Its name.
     * @param card Its reach, or {@code null} when no card of it was accepted.
     * @param admins The admins its access names.
     * @param projects The projects it carries.
     */
    public record Host(
            String host, @Nullable Card card, List<Admin> admins, List<Project> projects) {
        /** Compact constructor: the lists are copied and cannot be changed afterwards. */
        public Host {
            admins = List.copyOf(admins);
            projects = List.copyOf(projects);
        }
    }

    /**
     * A host's reach, as its accepted card says.
     *
     * @param address Its address.
     * @param sshPort Its ssh port.
     * @param sshHostKey Its ssh host key.
     * @param forgeWebPort Its forge's web port.
     * @param forgeGitPort Its forge's git port.
     * @param matrixPort Its homeserver's port.
     * @param signedBy The fingerprint of the key that signed the card.
     */
    public record Card(
            String address,
            int sshPort,
            String sshHostKey,
            int forgeWebPort,
            int forgeGitPort,
            int matrixPort,
            String signedBy) {}

    /**
     * An admin a host's access names.
     *
     * @param login Their login.
     * @param fingerprint The fingerprint of the key it pins.
     */
    public record Admin(String login, String fingerprint) {}

    /**
     * A project a host carries.
     *
     * @param name Its name.
     * @param admin Its admin's login.
     * @param accessRepository The address of its access repository.
     */
    public record Project(String name, String admin, String accessRepository) {}
}
