package org.fuin.porelo.core.project;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A project's public access settings, authenticated by its project signers.
 *
 * @param host The prepared host, or {@code null} when none is named.
 * @param persons Persons by card login, in file order; their cards are checked separately.
 * @param revoked Fingerprints no card or chain may carry.
 * @param transports Each transport's settings, kept as written, by scheme.
 */
public record AccessFile(
        @Nullable Host host,
        Map<String, Person> persons,
        List<String> revoked,
        Map<String, Object> transports) {

    /**
     * The prepared host.
     *
     * @param address Its public name or address.
     * @param sshHostKey Its SSH host key, as ssh-keygen prints a public key.
     */
    public record Host(String address, String sshHostKey) {}

    /**
     * A person's project access, pinned to the fingerprint their card must lead back to.
     *
     * @param login Their card's login.
     * @param fingerprint Their pinned key fingerprint.
     * @param role Their project role, {@code admin} or {@code developer}.
     * @param hosts The host names they work on.
     * @param locked Whether this project has locked them out.
     */
    public record Person(
            String login, String fingerprint, String role, List<String> hosts, boolean locked) {
        /** Canonical constructor; host grants cannot be changed afterwards. */
        public Person {
            hosts = List.copyOf(hosts);
        }
    }

    /** Canonical constructor; collections are copied, keeping file order. */
    public AccessFile {
        persons = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(persons));
        revoked = List.copyOf(revoked);
        transports =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(transports));
    }

    /**
     * Reads access settings; cards are verified from the same repository commit by the caller.
     *
     * @param reader The file's content.
     * @param origin Name used in error messages.
     * @return The settings.
     * @throws ProjectException If it does not read as an access file.
     */
    public static AccessFile read(final Reader reader, final String origin) {
        return AccessFileReader.read(reader, origin);
    }
}
