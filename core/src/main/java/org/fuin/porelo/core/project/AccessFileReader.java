package org.fuin.porelo.core.project;

import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads {@code access.yml} into an {@link AccessFile}. */
final class AccessFileReader {

    private AccessFileReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    static AccessFile read(final Reader reader, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            // YAML exceptions include source excerpts, which may contain a private key.
            throw refusal(
                    origin,
                    "$",
                    "cannot parse YAML; use one mapping without duplicate keys or custom tags");
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw refusal(origin, "$", "is empty or is not a YAML mapping");
        }
        publicOnly(root, "$", origin, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (root.containsKey("signers")) {
            throw refusal(origin, "signers", "belongs to the project file alone");
        }
        known(root, Set.of("host", "developers", "transports"), "", origin);
        final AccessFile.Host host =
                root.containsKey("host")
                        ? host(mapping(root.get("host"), "host", origin), origin)
                        : null;
        final Network network = host == null ? null : network(host.network(), origin);
        return new AccessFile(host, developers(root, network, origin), transports(root, origin));
    }

    private static AccessFile.Host host(final Map<?, ?> host, final String origin) {
        known(
                host,
                Set.of(
                        "address",
                        "endpoint",
                        "wireguard_public_key",
                        "network",
                        "ssh_host_key",
                        "ca_sha256"),
                "host",
                origin);
        final String address = required(host, "address", "host", origin);
        if (!hostAddress(address)) {
            throw refusal(origin, "host.address", "is not a DNS name or IP address");
        }
        final String endpoint = required(host, "endpoint", "host", origin);
        if (!endpoint(endpoint)) {
            throw refusal(origin, "host.endpoint", "must be host:port with a port in 1..65535");
        }
        final String wireguard =
                wireguard(
                        required(host, "wireguard_public_key", "host", origin),
                        "host.wireguard_public_key",
                        origin);
        final String cidr = required(host, "network", "host", origin);
        final String ssh =
                ssh(required(host, "ssh_host_key", "host", origin), "host.ssh_host_key", origin);
        final String ca = required(host, "ca_sha256", "host", origin);
        if (!ca.matches("[0-9a-f]{64}")) {
            throw refusal(origin, "host.ca_sha256", "must be 64 lower-case hex characters");
        }
        return new AccessFile.Host(address, endpoint, wireguard, cidr, ssh, ca);
    }

    private static Map<String, AccessFile.Developer> developers(
            final Map<?, ?> root, final @Nullable Network network, final String origin) {
        if (!root.containsKey("developers")) {
            return Map.of();
        }
        final Map<?, ?> named = mapping(root.get("developers"), "developers", origin);
        final Map<String, AccessFile.Developer> developers = new LinkedHashMap<>();
        final Set<Long> addresses = new HashSet<>();
        for (final Map.Entry<?, ?> entry : named.entrySet()) {
            final String account = key(entry.getKey());
            final String path = "developers." + account;
            if (!(entry.getKey() instanceof String) || !account.matches("[a-z_][a-z0-9_-]{0,31}")) {
                throw refusal(
                        origin, path, "is not an account name; expected [a-z_][a-z0-9_-]{0,31}");
            }
            final Map<?, ?> developer = mapping(entry.getValue(), path, origin);
            known(developer, Set.of("laptops", "forge_login"), path, origin);
            final Object value = developer.get("laptops");
            if (!(value instanceof List<?> entries) || entries.isEmpty()) {
                throw refusal(origin, path + ".laptops", "must be a list of at least one laptop");
            }
            final List<AccessFile.Laptop> laptops = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                final String laptopPath = path + ".laptops[" + i + "]";
                final Map<?, ?> laptop = mapping(entries.get(i), laptopPath, origin);
                known(
                        laptop,
                        Set.of("ssh_public_key", "wireguard_public_key", "vpn_address"),
                        laptopPath,
                        origin);
                final String ssh =
                        ssh(
                                required(laptop, "ssh_public_key", laptopPath, origin),
                                laptopPath + ".ssh_public_key",
                                origin);
                final String wireguard =
                        wireguard(
                                required(laptop, "wireguard_public_key", laptopPath, origin),
                                laptopPath + ".wireguard_public_key",
                                origin);
                final String vpn = required(laptop, "vpn_address", laptopPath, origin);
                vpnAddress(vpn, network, addresses, laptopPath + ".vpn_address", origin);
                laptops.add(new AccessFile.Laptop(ssh, wireguard, vpn));
            }
            final String login =
                    developer.containsKey("forge_login")
                            ? required(developer, "forge_login", path, origin)
                            : null;
            developers.put(account, new AccessFile.Developer(account, List.copyOf(laptops), login));
        }
        return developers;
    }

    private static Map<String, Object> transports(final Map<?, ?> root, final String origin) {
        if (!root.containsKey("transports")) {
            return Map.of();
        }
        final Map<?, ?> named = mapping(root.get("transports"), "transports", origin);
        final Map<String, Object> transports = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : named.entrySet()) {
            final String scheme = key(entry.getKey());
            final String path = "transports." + scheme;
            if (!(entry.getKey() instanceof String) || !scheme.matches("[a-z0-9][a-z0-9-]*")) {
                throw refusal(
                        origin,
                        path,
                        "is not a transport's name; expected lower-case letters, digits and hyphens");
            }
            transports.put(scheme, mapping(entry.getValue(), path, origin));
        }
        return transports;
    }

    private static void vpnAddress(
            final String value,
            final @Nullable Network network,
            final Set<Long> used,
            final String path,
            final String origin) {
        if (network == null) {
            throw refusal(origin, path, "requires 'host.network'; there is no 'host' section");
        }
        final long address = ipv4(value);
        if (address < 0) {
            throw refusal(origin, path, "must be an IPv4 address");
        }
        if (address < network.first() || address > network.last()) {
            throw refusal(origin, path, "is outside 'host.network'");
        }
        if (address == network.first()) {
            throw refusal(origin, path, "is the network address");
        }
        if (address == network.last()) {
            throw refusal(origin, path, "is the broadcast address");
        }
        if (address == network.first() + 1) {
            throw refusal(origin, path, "is the host's own address");
        }
        if (!used.add(address)) {
            throw refusal(origin, path, "is already used by another laptop");
        }
    }

    private record Network(long first, long last) {}

    private static Network network(final String value, final String origin) {
        final String[] parts = value.split("/", -1);
        if (parts.length == 2 && parts[1].matches("[0-9]{1,2}")) {
            final long address = ipv4(parts[0]);
            final int prefix = Integer.parseInt(parts[1]);
            if (address >= 0 && prefix >= 8 && prefix <= 30) {
                final long size = 1L << (32 - prefix);
                if (address % size == 0) {
                    return new Network(address, address + size - 1);
                }
            }
        }
        throw refusal(
                origin,
                "host.network",
                "must be an IPv4 network in CIDR notation with prefix 8..30");
    }

    private static long ipv4(final String value) {
        final String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return -1;
        }
        long address = 0;
        for (final String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) {
                return -1;
            }
            final int octet = Integer.parseInt(part);
            if (octet > 255) {
                return -1;
            }
            address = (address << 8) | octet;
        }
        return address;
    }

    private static boolean hostAddress(final String value) {
        if (value.contains(":")) {
            try {
                if (value.contains("%")) {
                    return false;
                }
                InetAddress.ofLiteral(value);
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        if (value.matches("[0-9.]+")) {
            return ipv4(value) >= 0;
        }
        final String name = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        if (name.isEmpty() || name.length() > 253) {
            return false;
        }
        for (final String label : name.split("\\.", -1)) {
            if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    private static boolean endpoint(final String value) {
        final int colon = value.lastIndexOf(':');
        if (colon < 1) {
            return false;
        }
        final String host = value.substring(0, colon);
        final String port = value.substring(colon + 1);
        final boolean validHost =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1).contains(":")
                                && hostAddress(host.substring(1, host.length() - 1))
                        : !host.contains(":") && hostAddress(host);
        return validHost
                && port.matches("[0-9]{1,5}")
                && Integer.parseInt(port) >= 1
                && Integer.parseInt(port) <= 65535;
    }

    private static String wireguard(final String value, final String path, final String origin) {
        if (value.matches("[A-Za-z0-9+/]{43}=")
                && Base64.getEncoder()
                        .encodeToString(Base64.getDecoder().decode(value))
                        .equals(value)) {
            return value;
        }
        throw refusal(origin, path, "must be a WireGuard public key (base64 of 32 bytes)");
    }

    private static String ssh(final String value, final String path, final String origin) {
        final String[] fields = value.strip().split("\\s+");
        if (fields.length >= 2
                && fields[0].matches(
                        "ssh-(ed25519|rsa)|ecdsa-sha2-nistp(256|384|521)|sk-[a-z0-9@.-]+")
                && fields[1].matches("[A-Za-z0-9+/]+={0,2}")) {
            try {
                Base64.getDecoder().decode(fields[1]);
                return fields[0] + " " + fields[1];
            } catch (IllegalArgumentException ex) {
                // A blob with base64 characters can still have invalid padding or length.
            }
        }
        throw refusal(
                origin,
                path,
                "is not a public key; write it as ssh-keygen prints it, such as 'ssh-ed25519 AAAA...'");
    }

    private static Map<?, ?> mapping(
            final @Nullable Object value, final String path, final String origin) {
        if (!(value instanceof Map<?, ?> map)) {
            throw refusal(origin, path, "must be a mapping");
        }
        return map;
    }

    private static String required(
            final Map<?, ?> map, final String name, final String path, final String origin) {
        if (!(map.get(name) instanceof String value) || value.isBlank()) {
            throw refusal(origin, path + "." + name, "is required and must be nonempty text");
        }
        return value;
    }

    private static void known(
            final Map<?, ?> map, final Set<String> keys, final String path, final String origin) {
        for (final Object key : map.keySet()) {
            if (!(key instanceof String name) || !keys.contains(name)) {
                throw refusal(
                        origin, path.isEmpty() ? key(key) : path + "." + key(key), "is not known");
            }
        }
    }

    private static String key(final @Nullable Object key) {
        return key instanceof String || key instanceof Number || key instanceof Boolean
                ? String.valueOf(key)
                : "<non-text key>";
    }

    private static void publicOnly(
            final @Nullable Object value,
            final String path,
            final String origin,
            final Set<Object> seen) {
        if (value == null || !seen.add(value)) {
            return;
        }
        if (value instanceof String text && privateKey(text)
                || value instanceof byte[] bytes
                        && privateKey(new String(bytes, StandardCharsets.UTF_8))) {
            throw refusal(
                    origin, path, "is refused; the file holds public keys and addresses only");
        }
        if (value instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                publicOnly(entry.getKey(), path, origin, seen);
                publicOnly(
                        entry.getValue(),
                        path.equals("$") ? key(entry.getKey()) : path + "." + key(entry.getKey()),
                        origin,
                        seen);
            }
        } else if (value instanceof Iterable<?> entries) {
            int index = 0;
            for (final Object entry : entries) {
                publicOnly(entry, path + "[" + index++ + "]", origin, seen);
            }
        }
    }

    private static boolean privateKey(final String value) {
        final String upper = value.toUpperCase(Locale.ROOT);
        return upper.contains("PRIVATE KEY") || upper.contains("AGE-SECRET-KEY-");
    }

    private static ProjectException refusal(
            final String origin, final String path, final String reason) {
        return new ProjectException(
                origin
                        + ": '"
                        + path.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?")
                        + "' "
                        + reason.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?"));
    }
}
