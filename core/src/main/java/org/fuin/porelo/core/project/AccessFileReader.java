package org.fuin.porelo.core.project;

import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
        known(root, Set.of("host", "persons", "revoked", "transports"), "", origin);
        final AccessFile.Host host =
                root.containsKey("host")
                        ? host(mapping(root.get("host"), "host", origin), origin)
                        : null;
        return new AccessFile(
                host, persons(root, origin), revoked(root, origin), transports(root, origin));
    }

    private static AccessFile.Host host(final Map<?, ?> host, final String origin) {
        known(host, Set.of("address", "ssh_host_key"), "host", origin);
        final String address = required(host, "address", "host", origin);
        if (!hostAddress(address)) {
            throw refusal(origin, "host.address", "is not a DNS name or IP address");
        }
        return new AccessFile.Host(
                address,
                ssh(required(host, "ssh_host_key", "host", origin), "host.ssh_host_key", origin));
    }

    private static Map<String, AccessFile.Person> persons(
            final Map<?, ?> root, final String origin) {
        if (!root.containsKey("persons")) {
            return Map.of();
        }
        final Map<?, ?> named = mapping(root.get("persons"), "persons", origin);
        final Map<String, AccessFile.Person> persons = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : named.entrySet()) {
            final String login = key(entry.getKey());
            final String path = "persons." + login;
            if (!(entry.getKey() instanceof String)
                    || !login.matches("[a-z][a-z0-9-]{0,31}")
                    || login.equals("porelo")
                    || login.startsWith("porelo-")) {
                throw refusal(
                        origin,
                        path,
                        "is not a card login; expected [a-z][a-z0-9-]{0,31}, excluding porelo and porelo-*");
            }
            final Map<?, ?> person = mapping(entry.getValue(), path, origin);
            known(person, Set.of("fingerprint", "role", "hosts", "locked"), path, origin);
            final String fingerprint =
                    fingerprint(
                            required(person, "fingerprint", path, origin),
                            path + ".fingerprint",
                            origin);
            final String role = required(person, "role", path, origin);
            if (!Set.of("admin", "developer").contains(role)) {
                throw refusal(
                        origin,
                        path + ".role",
                        "'" + role + "' is not a role; expected admin or developer");
            }
            final Object value = person.get("hosts");
            if (!(value instanceof List<?> entries) || entries.isEmpty()) {
                throw refusal(origin, path + ".hosts", "must be a list of at least one host name");
            }
            final List<String> hosts = new ArrayList<>();
            for (final Object entryHost : entries) {
                if (!(entryHost instanceof String name)
                        || !name.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
                    throw refusal(
                            origin,
                            path + ".hosts",
                            "must contain host names matching [a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
                }
                hosts.add(name);
            }
            if (person.containsKey("locked") && !(person.get("locked") instanceof Boolean)) {
                throw refusal(origin, path + ".locked", "must be a boolean");
            }
            persons.put(
                    login,
                    new AccessFile.Person(
                            login,
                            fingerprint,
                            role,
                            hosts,
                            Boolean.TRUE.equals(person.get("locked"))));
        }
        return persons;
    }

    private static List<String> revoked(final Map<?, ?> root, final String origin) {
        if (!root.containsKey("revoked")) {
            return List.of();
        }
        if (!(root.get("revoked") instanceof List<?> entries)) {
            throw refusal(origin, "revoked", "must be a list of key fingerprints");
        }
        final List<String> revoked = new ArrayList<>();
        for (final Object entry : entries) {
            if (!(entry instanceof String value)) {
                throw refusal(origin, "revoked", "must be a list of key fingerprints");
            }
            revoked.add(fingerprint(value, "revoked", origin));
        }
        return revoked;
    }

    private static String fingerprint(final String value, final String path, final String origin) {
        if (!value.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw refusal(origin, path, "must contain SHA256 key fingerprints");
        }
        return value;
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
                if (Set.of(
                                "developers",
                                "laptops",
                                "wireguard_public_key",
                                "endpoint",
                                "network",
                                "ca_sha256",
                                "vpn_address")
                        .contains(key(entry.getKey()))) {
                    throw refusal(
                            origin,
                            path.equals("$")
                                    ? key(entry.getKey())
                                    : path + "." + key(entry.getKey()),
                            "is retired; name persons by their cards");
                }
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
