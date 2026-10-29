package org.fuin.porelo.core.project;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A project's access file, {@code access.yml}: what the public {@code project.yml} must not show.
 *
 * <p>Public keys and addresses only, never a secret: the file is signed like the project file and
 * may lie beside it, as visible as the project's repository.
 *
 * @param host The prepared host, or {@code null} when the file names none.
 * @param developers Each developer by account, in the order written; empty when none is named.
 * @param transports Each transport's settings, kept as written, by scheme; empty when none.
 */
public record AccessFile(
        @Nullable Host host, Map<String, Developer> developers, Map<String, Object> transports) {

    /**
     * The prepared host the project's machines and developers reach.
     *
     * @param address Its public name or address.
     * @param endpoint Its WireGuard endpoint, {@code host:port}.
     * @param wireguardPublicKey Its WireGuard public key.
     * @param network The VPN's network, in CIDR notation.
     * @param sshHostKey Its ssh host key, as ssh-keygen prints a public key.
     * @param caSha256 The SHA-256 of its CA certificate, hex.
     */
    public record Host(
            String address,
            String endpoint,
            String wireguardPublicKey,
            String network,
            String sshHostKey,
            String caSha256) {}

    /**
     * One developer of the project.
     *
     * @param account Their account on the host.
     * @param laptops Each laptop they reach the host from.
     * @param forgeLogin Their login at the forge, or {@code null}.
     */
    public record Developer(String account, List<Laptop> laptops, @Nullable String forgeLogin) {}

    /**
     * A laptop a developer reaches the host from.
     *
     * @param sshPublicKey Its ssh public key.
     * @param wireguardPublicKey Its WireGuard public key.
     * @param vpnAddress Its address inside the host's {@link Host#network()}.
     */
    public record Laptop(String sshPublicKey, String wireguardPublicKey, String vpnAddress) {}

    /** Canonical constructor; the maps are copied, keeping their order. */
    public AccessFile {
        developers =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(developers));
        transports =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(transports));
    }

    /**
     * Reads an access file.
     *
     * @param reader Its content.
     * @param origin Name used in error messages.
     * @return What it says.
     * @throws ProjectException If it does not read as an access file.
     */
    public static AccessFile read(final Reader reader, final String origin) {
        return AccessFileReader.read(reader, origin);
    }
}
