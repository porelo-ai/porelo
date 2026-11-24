package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkNumbers;
import org.jspecify.annotations.Nullable;

/**
 * A host's identity as the person's vault keeps it: the organization's read key, each repository's
 * read key and the host's SSH host keys, one JSON object in one entry per host, so a rental's
 * preparation writes and reads the whole set at once.
 *
 * <p>The value is checked whole when it is kept, and its fields again on every read, which derives
 * no key. A refusal names the field alone, never a key's text or a name the caller chose: either
 * would reach a log or a screen. Each private key is one unencrypted OpenSSH key whose public half,
 * derived from it when it is kept, is the {@code public} given.
 */
public final class HostIdentity {

    /** The kind its vault entry carries. */
    public static final String KIND = "host-identity";

    /** The most a value may take, written as JSON. */
    public static final int MOST_BYTES = 256 * 1024;

    /** The most repositories one host carries. */
    public static final int MOST_REPOSITORIES = 256;

    /** The most SSH host keys: one of each type. */
    public static final int MOST_HOST_KEYS = 3;

    /** A host's name, as its card names it. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /** A project's name, as the host's records name it. */
    private static final Pattern PROJECT = Pattern.compile("[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?");

    /** A repository's name, as the host's records name it. */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final List<String> TOP =
            List.of("host", "organization", "repositories", "ssh_host_keys");

    private static final List<String> KEY = List.of("public", "private");

    private static final List<String> ROW =
            List.of("project", "repository", "address", "key_id", "title", "public", "private");

    private static final List<String> HOST_KEY = List.of("type", "public", "private");

    /** How long {@code ssh-keygen} may take to derive one public half. */
    private static final Duration DERIVING = Duration.ofSeconds(30);

    private HostIdentity() {}

    /** A field of a value that does not hold. Its message is the field's path and nothing else. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String field;

        Refused(final String field) {
            super(field, null, false, false);
            this.field = field;
        }

        /**
         * Returns the field that does not hold.
         *
         * @return Its path, such as {@code identity.repositories[0].key_id}.
         */
        public String field() {
            return field;
        }
    }

    /** Derives a private key's public half. */
    @FunctionalInterface
    public interface PublicHalf {

        /**
         * Derives the public half of one unencrypted OpenSSH private key.
         *
         * @param privateKey The private key file's text.
         * @param algorithm Its algorithm, such as {@code ssh-ed25519}.
         * @return {@code <algorithm> <base64>}, or {@code null} when it cannot be derived.
         */
        @Nullable String of(String privateKey, String algorithm);
    }

    /**
     * Tells whether a name is a host's, as its card names it.
     *
     * @param host The name.
     * @return true for one lower-case DNS label.
     */
    public static boolean validHost(final String host) {
        return HOST.matcher(host).matches();
    }

    /**
     * Returns the vault entry a host's identity is kept under.
     *
     * @param host The host, as {@link #validHost} takes it.
     * @return Its name in the vault's reserved name space.
     */
    public static String entryName(final String host) {
        return VaultNames.HOST_IDENTITY_PREFIX + host;
    }

    /**
     * Checks a value whole and returns it as it is kept.
     *
     * @param value The value, as a varlink call or the vault's JSON carries it.
     * @param host The host it is for.
     * @param origin The organization's central forge, {@code https://<host>[:<port>]} in lower
     *     case, or {@code null} when there is no organization to check an address against.
     * @param derive Derives a private key's public half.
     * @return The value, every field in its place.
     * @throws Refused Naming the first field that does not hold.
     */
    public static Map<String, Object> checked(
            final @Nullable Object value,
            final String host,
            final @Nullable String origin,
            final PublicHalf derive) {
        return checking(value, host, origin, derive);
    }

    /**
     * Checks a value the vault keeps, which was checked whole when it was kept: its shape and
     * fields again, and no public half derived, so a read writes no key anywhere.
     *
     * @param value The value, as the vault's JSON carries it.
     * @param host The host it is for.
     * @param origin As {@link #checked} takes it.
     * @return The value, every field in its place.
     * @throws Refused Naming the first field that does not hold.
     */
    public static Map<String, Object> checkedAsKept(
            final @Nullable Object value, final String host, final @Nullable String origin) {
        return checking(value, host, origin, null);
    }

    private static Map<String, Object> checking(
            final @Nullable Object value,
            final String host,
            final @Nullable String origin,
            final @Nullable PublicHalf derive) {
        if (!(value instanceof Map<?, ?>)
                || Json.write(value).getBytes(StandardCharsets.UTF_8).length > MOST_BYTES) {
            throw new Refused("identity");
        }
        final Map<String, Object> identity = object(value, TOP, "identity");
        if (!host.equals(text(identity, "host", "identity", false))) {
            throw new Refused("identity.host");
        }
        final Map<String, Object> organization =
                object(identity.get("organization"), KEY, "identity.organization");
        key(organization, "identity.organization", null, derive);
        identity.put("organization", organization);

        final List<Object> rows = list(identity.get("repositories"), "identity.repositories");
        if (rows.size() > MOST_REPOSITORIES) {
            throw new Refused("identity.repositories");
        }
        final Set<String> named = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            final String field = "identity.repositories[" + index + "]";
            final Map<String, Object> row = object(rows.get(index), ROW, field);
            final String project = text(row, "project", field, false);
            if (!PROJECT.matcher(project).matches()) {
                throw new Refused(field + ".project");
            }
            final String repository = text(row, "repository", field, false);
            if (!REPOSITORY.matcher(repository).matches()
                    || !named.add(project + "/" + repository.toLowerCase(Locale.ROOT))) {
                throw new Refused(field + ".repository");
            }
            if (origin == null || !origin.equals(origin(text(row, "address", field, false)))) {
                throw new Refused(field + ".address");
            }
            if (VarlinkNumbers.whole(row.get("key_id")).orElse(0L) <= 0) {
                throw new Refused(field + ".key_id");
            }
            if (!("porelo host " + host + " project " + project)
                    .equals(text(row, "title", field, false))) {
                throw new Refused(field + ".title");
            }
            row.put("key_id", VarlinkNumbers.whole(row.get("key_id")).orElseThrow());
            key(row, field, null, derive);
            rows.set(index, row);
        }
        identity.put("repositories", rows);

        final List<Object> hostKeys = list(identity.get("ssh_host_keys"), "identity.ssh_host_keys");
        if (hostKeys.isEmpty() || hostKeys.size() > MOST_HOST_KEYS) {
            throw new Refused("identity.ssh_host_keys");
        }
        final Set<String> types = new HashSet<>();
        for (int index = 0; index < hostKeys.size(); index++) {
            final String field = "identity.ssh_host_keys[" + index + "]";
            final Map<String, Object> row = object(hostKeys.get(index), HOST_KEY, field);
            final String type = text(row, "type", field, false);
            if (!List.of("ed25519", "ecdsa", "rsa").contains(type) || !types.add(type)) {
                throw new Refused(field + ".type");
            }
            key(row, field, type, derive);
            hostKeys.set(index, row);
        }
        identity.put("ssh_host_keys", hostKeys);
        return identity;
    }

    /**
     * Returns a checked value with every private half left out.
     *
     * @param identity A value {@link #checked} returned.
     * @return The same shape without any {@code private} field.
     */
    public static Map<String, Object> publicOnly(final Map<String, Object> identity) {
        final Map<String, Object> shown = new LinkedHashMap<>();
        identity.forEach(
                (field, value) -> {
                    if (value instanceof Map<?, ?> key) {
                        shown.put(field, withoutPrivate(key));
                    } else if (value instanceof List<?> rows) {
                        shown.put(
                                field,
                                rows.stream()
                                        .map(
                                                row ->
                                                        row instanceof Map<?, ?> each
                                                                ? withoutPrivate(each)
                                                                : row)
                                        .toList());
                    } else {
                        shown.put(field, value);
                    }
                });
        return shown;
    }

    private static Map<String, Object> withoutPrivate(final Map<?, ?> key) {
        final Map<String, Object> shown = new LinkedHashMap<>();
        key.forEach(
                (field, value) -> {
                    if (!"private".equals(field) && value != null) {
                        shown.put(String.valueOf(field), value);
                    }
                });
        return shown;
    }

    /**
     * Tells whether an offered value is a kept one with repositories added and nothing else: the
     * same organization key, the same host keys, and every kept repository with the same address,
     * key id, title and public key. Keys are compared as {@code <algorithm> <base64>}, without
     * their comments.
     *
     * @param kept The value the vault holds.
     * @param offered The value offered in its place.
     * @return true when the offered one only adds.
     */
    public static boolean onlyAdds(
            final Map<String, Object> kept, final Map<String, Object> offered) {
        if (!Objects.equals(organizationKey(kept), organizationKey(offered))
                || organizationKey(kept) == null
                || !hostKeys(kept).equals(hostKeys(offered))) {
            return false;
        }
        final Map<String, List<Object>> now = repositories(offered);
        for (final Map.Entry<String, List<Object>> was : repositories(kept).entrySet()) {
            if (!was.getValue().equals(now.get(was.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String organizationKey(final Map<String, Object> identity) {
        return identity.get("organization") instanceof Map<?, ?> organization
                        && organization.get("public") instanceof String key
                ? canonical(key)
                : null;
    }

    private static Set<String> hostKeys(final Map<String, Object> identity) {
        final Set<String> keys = new TreeSet<>();
        if (identity.get("ssh_host_keys") instanceof List<?> rows) {
            for (final Object row : rows) {
                if (row instanceof Map<?, ?> key && key.get("public") instanceof String line) {
                    keys.add(key.get("type") + " " + canonical(line));
                }
            }
        }
        return keys;
    }

    private static Map<String, List<Object>> repositories(final Map<String, Object> identity) {
        final Map<String, List<Object>> found = new LinkedHashMap<>();
        if (identity.get("repositories") instanceof List<?> rows) {
            for (final Object row : rows) {
                if (row instanceof Map<?, ?> each) {
                    final List<Object> held = new ArrayList<>();
                    held.add(String.valueOf(each.get("address")));
                    held.add(VarlinkNumbers.whole(each.get("key_id")).orElse(0L));
                    held.add(String.valueOf(each.get("title")));
                    held.add(
                            each.get("public") instanceof String line
                                    ? canonical(line)
                                    : String.valueOf(each.get("public")));
                    found.put(each.get("project") + "/" + each.get("repository"), held);
                }
            }
        }
        return found;
    }

    /**
     * The keys a kept identity holds at the central forge, for a person to remove there when the
     * vault goes: the organization's read key and each repository's. Read as it is kept, without
     * checking it again, so a value the checks would now refuse still names its keys.
     *
     * @param entry The vault entry's name.
     * @param value Its value.
     * @param organization The organization's address, or "" when none is known.
     * @return The keys, the organization's first; none when the value does not read.
     */
    public static List<LeftDeployKeys.Key> leftKeys(
            final String entry, final String value, final String organization) {
        final Object parsed;
        try {
            parsed = Json.parseExact(value);
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> identity)) {
            return List.of();
        }
        final List<LeftDeployKeys.Key> keys = new ArrayList<>();
        if (identity.get("organization") instanceof Map<?, ?> key
                && key.get("public") instanceof String line) {
            final String fingerprint = fingerprint(line);
            keys.add(
                    new LeftDeployKeys.Key(
                            entry,
                            "",
                            organization,
                            canonical(line),
                            fingerprint,
                            "porelo host read key " + fingerprint,
                            false,
                            false));
        }
        if (identity.get("repositories") instanceof List<?> rows) {
            for (final Object row : rows) {
                if (row instanceof Map<?, ?> each
                        && each.get("public") instanceof String line
                        && each.get("repository") instanceof String repository
                        && each.get("address") instanceof String address
                        && each.get("title") instanceof String title) {
                    keys.add(
                            new LeftDeployKeys.Key(
                                    entry,
                                    repository,
                                    address,
                                    canonical(line),
                                    fingerprint(line),
                                    title,
                                    false,
                                    false));
                }
            }
        }
        return keys;
    }

    private static String fingerprint(final String line) {
        final String fingerprint = SignedBy.fingerprintOf(line);
        return fingerprint == null ? "" : fingerprint;
    }

    /**
     * Returns the organization's central forge, as {@link #checked} compares an address with it.
     *
     * @param context The machine.
     * @return {@code https://<host>[:<port>]} in lower case, or {@code null} when there is no
     *     organization or its settings do not read.
     */
    public static @Nullable String centralOrigin(final PoreloContext context) {
        try {
            final String repository = IdentityOrganization.repository(context).orElse(null);
            return repository == null ? null : origin(repository);
        } catch (IdentityChange.Refused | IOException | RuntimeException ex) {
            return null;
        }
    }

    private static @Nullable String origin(final String address) {
        try {
            return ForgeAddress.of(address).forge().toString().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Returns where the daemon derives a key's public half: a directory of its own in its runtime
     * directory, which only the account reads and which does not outlive a reboot.
     *
     * @param paths The machine's paths.
     * @return The directory.
     */
    public static Path derivations(final PoreloPaths paths) {
        return paths.xdg().runtime().resolve("host-identity-keys");
    }

    /**
     * Removes whatever an earlier run left where keys are derived, as a daemon that ended in the
     * middle of a derivation leaves a key's copy behind. Run when the daemon starts.
     *
     * @param paths The machine's paths.
     */
    public static void clearDerivations(final PoreloPaths paths) {
        final Path directory = derivations(paths);
        if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var left = Files.list(directory)) {
            for (final Path each : left.toList()) {
                if (Files.isDirectory(each, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    VaultPutCommand.shred(each);
                } else {
                    Files.deleteIfExists(each);
                }
            }
        } catch (IOException ex) {
            // Nothing to report to: the next derivation makes a directory of its own anyway.
        }
    }

    /**
     * Returns how this machine derives a private key's public half: in Java for Ed25519, holding
     * every section of the file to its seed, and by {@code ssh-keygen -y} for the others, from a
     * copy in a directory only the account can read, under {@code directory}, removed at once.
     *
     * @param runner Where {@code ssh-keygen} runs.
     * @param directory Where the copies are made, as {@link #derivations} names it.
     * @return The derivation.
     */
    public static PublicHalf derivation(final CommandRunner runner, final Path directory) {
        return (privateKey, algorithm) -> {
            if ("ssh-ed25519".equals(algorithm)) {
                return OpenSshPrivateKey.consistentEd25519PublicKey(privateKey);
            }
            Path copies = null;
            try {
                Files.createDirectories(directory);
                Files.setPosixFilePermissions(
                        directory, PosixFilePermissions.fromString("rwx------"));
                copies =
                        Files.createTempDirectory(
                                directory,
                                "key-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
                final Path copy =
                        Files.createFile(
                                copies.resolve("key"),
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")));
                Files.writeString(copy, privateKey, StandardCharsets.US_ASCII);
                final CommandResult result =
                        runner.run(
                                Command.of("ssh-keygen", "-y", "-P", "", "-f", copy.toString())
                                        .withTimeout(DERIVING));
                return result.successful() ? canonical(result.standardOutput().strip()) : null;
            } catch (IOException | CommandException ex) {
                return null;
            } finally {
                if (copies != null) {
                    VaultPutCommand.shred(copies);
                }
            }
        };
    }

    /** A key as {@code <algorithm> <base64>}, its comment left out. */
    private static String canonical(final String line) {
        final String[] fields = line.strip().split(" ", 3);
        return fields.length < 2 ? line : fields[0] + " " + fields[1];
    }

    /** The host key type an algorithm is of, or "" for none a host key has. */
    private static String typeOf(final String algorithm) {
        if ("ssh-ed25519".equals(algorithm)) {
            return "ed25519";
        }
        if ("ssh-rsa".equals(algorithm)) {
            return "rsa";
        }
        return algorithm.matches("ecdsa-sha2-nistp(256|384|521)") ? "ecdsa" : "";
    }

    /** Checks one key: its public line, the type it is said to be, its private half. */
    private static void key(
            final Map<String, Object> key,
            final String field,
            final @Nullable String type,
            final @Nullable PublicHalf derive) {
        final String line = text(key, "public", field, false);
        final String canonical = canonical(line);
        try {
            HostCard.hostKey(canonical, field + ".public");
        } catch (CardException ex) {
            throw new Refused(field + ".public");
        }
        final String algorithm = canonical.substring(0, canonical.indexOf(' '));
        if (type != null && !type.equals(typeOf(algorithm))) {
            throw new Refused(field + ".type");
        }
        final String privateKey = text(key, "private", field, true);
        if (!algorithm.equals(OpenSshPrivateKey.singleUnencryptedType(privateKey))) {
            throw new Refused(field + ".private");
        }
        if (derive != null && !canonical.equals(derive.of(privateKey, algorithm))) {
            throw new Refused(field + ".public");
        }
    }

    /** An object with exactly these fields, in this order. */
    private static Map<String, Object> object(
            final @Nullable Object value, final List<String> fields, final String field) {
        if (!(value instanceof Map<?, ?> given)) {
            throw new Refused(field);
        }
        for (final Object name : given.keySet()) {
            if (!(name instanceof String known) || !fields.contains(known)) {
                // Named by where it is: its name is the caller's text, never answered back.
                throw new Refused(field + ": an unknown field");
            }
        }
        final Map<String, Object> ordered = new LinkedHashMap<>();
        for (final String name : fields) {
            final Object each = given.get(name);
            if (each == null) {
                throw new Refused(field + "." + name);
            }
            ordered.put(name, each);
        }
        return ordered;
    }

    private static List<Object> list(final @Nullable Object value, final String field) {
        if (!(value instanceof List<?> given)) {
            throw new Refused(field);
        }
        return new ArrayList<>(given);
    }

    /**
     * A text field free of control characters; a private key keeps its line breaks, which are its
     * only ones.
     */
    private static String text(
            final Map<String, Object> object,
            final String name,
            final String field,
            final boolean lines) {
        if (!(object.get(name) instanceof String text) || text.isEmpty()) {
            throw new Refused(field + "." + name);
        }
        for (int index = 0; index < text.length(); index++) {
            final char each = text.charAt(index);
            if ((Character.isISOControl(each) && !(lines && each == '\n'))
                    || Character.getType(each) == Character.FORMAT
                    || Character.isSurrogate(each)) {
                throw new Refused(field + "." + name);
            }
        }
        return text;
    }
}
