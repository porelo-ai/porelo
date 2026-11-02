package org.fuin.porelo.organization;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What an organization says at one revision: its owners, the admins of its hosts, its persons, its
 * hosts with their projects, and what a read of it refused or warns of. Plain data: it says nothing
 * of how the organization is kept.
 *
 * @param readTo The revision the read reached, or {@code null} when it reached none.
 * @param revision The revision it accepted, the same as {@code readTo} once anything was accepted;
 *     {@code null} when nothing was.
 * @param owners The organization's owners, backup keys among them.
 * @param hostAdmins Each host's admins, by the host's name, backup keys among them.
 * @param people The persons, each as their card last accepted says.
 * @param hosts The hosts.
 * @param revoked The fingerprints of the keys it revokes.
 * @param warnings What the read warns of.
 * @param refusals What the read refused, the latest last.
 * @param soleWithoutBackup Each place held by one key with no backup key behind it.
 */
public record Organization(
        @Nullable String readTo,
        @Nullable String revision,
        List<Owner> owners,
        Map<String, List<Owner>> hostAdmins,
        List<Person> people,
        List<Host> hosts,
        List<String> revoked,
        List<String> warnings,
        List<Refusal> refusals,
        List<Sole> soleWithoutBackup) {

    /** A person's standing: the identity source lists their key. */
    public static final String TRUSTED = "trusted";

    /** A person's standing: accepted before the identity source could say. */
    public static final String FIRST_SEEN = "first-seen";

    /** A person's standing: answered, but no card; the next one for the login counts as first. */
    public static final String REFUSED = "refused";

    /** Compact constructor: every collection is copied and cannot be changed afterwards. */
    public Organization {
        owners = List.copyOf(owners);
        final Map<String, List<Owner>> admins = new TreeMap<>();
        hostAdmins.forEach((host, list) -> admins.put(host, List.copyOf(list)));
        hostAdmins = Collections.unmodifiableMap(admins);
        people = List.copyOf(people);
        hosts = List.copyOf(hosts);
        revoked = List.copyOf(revoked);
        warnings = List.copyOf(warnings);
        refusals = List.copyOf(refusals);
        soleWithoutBackup = List.copyOf(soleWithoutBackup);
    }

    /**
     * Returns what the read refused last, which stays in force until something replaces it.
     *
     * @return The refusal, or {@code null} when none.
     */
    public @Nullable Refusal lastRefusal() {
        return refusals.isEmpty() ? null : refusals.getLast();
    }

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
