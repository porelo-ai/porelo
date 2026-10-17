package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * The host keys this machine trusts, and the ones a host offers.
 *
 * <p><strong>An unknown host key is a question, not a decision.</strong> Accepting on first use is
 * what everybody does and it is what an interception looks like; refusing silently leaves a daemon
 * stuck with no way to say why. So a host nobody has vouched for stops the work, and what stopped
 * it is answered with every key that host offered - type and fingerprint, as this machine saw them
 * - so a person can compare them with what they were told and say which one is real.
 *
 * <p><strong>Neither is accept-new, anywhere.</strong> Not on a fetch somebody started, and not on
 * the timer either: a machine that quietly learns a host at three in the morning has decided the
 * question that this refuses to decide. The first follow is where a person is present, and that is
 * where it is settled. The operator's ruling, 2026-09-19.
 */
public final class HostKeys {

    private HostKeys() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One key a host offers.
     *
     * @param type The algorithm, as ssh names it.
     * @param fingerprint {@code SHA256:...}, the form ssh itself prints and a person is told.
     * @param line The {@code known_hosts} line it would be recorded as. Not shown to anybody; it is
     *     what {@link #trust} writes once somebody has confirmed the fingerprint.
     */
    public record Offered(String type, String fingerprint, String line) {

        /**
         * Returns this as plain values, without the line.
         *
         * @return type and fingerprint.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("fingerprint", fingerprint);
            return map;
        }
    }

    /**
     * A host as ssh reaches it: a name or an address, and the port.
     *
     * <p><strong>The port is part of whom this machine has met.</strong> ssh remembers a host on
     * port 22 by its bare name and on any other port as {@code [host]:port}, so a key confirmed for
     * one says nothing about the other - and asking port 22 about a forge on 7999 asks a different
     * service, or nobody.
     *
     * @param host The name or address, without brackets.
     * @param port The port.
     */
    public record Address(String host, int port) {

        /** The port ssh uses when an address names none. */
        public static final int SSH = 22;

        /**
         * Reads a host as a person types it and every hint prints it: {@code forge.example.org},
         * {@code forge.example.org:7999} or {@code [2001:db8::1]:7999}.
         *
         * <p>A port of 22 written out is the same as none. An address with more than one colon and
         * no brackets is an IPv6 address on port 22, never split at its colons.
         *
         * @param name What was typed.
         * @return The address.
         * @throws IllegalArgumentException For no host, a host that would be read as an option, or
         *     a port that is no port.
         */
        public static Address of(final String name) {
            final String said = name.strip();
            final String host;
            final @Nullable String port;
            if (said.startsWith("[")) {
                final int close = said.indexOf(']');
                if (close < 0 || close < said.length() - 1 && said.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException(
                            "'" + said + "' is not a host - write it as [address]:port");
                }
                host = said.substring(1, close);
                port = close < said.length() - 1 ? said.substring(close + 2) : null;
            } else if (said.indexOf(':') >= 0 && said.indexOf(':') == said.lastIndexOf(':')) {
                host = said.substring(0, said.indexOf(':'));
                port = said.substring(said.indexOf(':') + 1);
            } else {
                host = said;
                port = null;
            }
            // Handed to ssh-keyscan as an argument: a leading dash would be read as an option.
            if (host.isEmpty()
                    || host.startsWith("-")
                    || host.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("'" + said + "' is not a host");
            }
            return new Address(host, port == null ? SSH : portOf(said, port));
        }

        private static int portOf(final String said, final String port) {
            if (!port.matches("[0-9]{1,5}")
                    || Integer.parseInt(port) < 1
                    || Integer.parseInt(port) > 65535) {
                throw new IllegalArgumentException(
                        "'" + said + "' names no port - write it as host:port");
            }
            return Integer.parseInt(port);
        }

        /**
         * Returns the host an address reaches, when git reaches it over ssh.
         *
         * <p>{@code ssh://[user@]host[:port]/path} with its port, and {@code [user@]host:path},
         * which is always port 22.
         *
         * @param upstream The address.
         * @return The host, or {@code null} for an address git does not reach over ssh.
         */
        public static @Nullable Address ofUpstream(final @Nullable String upstream) {
            if (upstream == null) {
                return null;
            }
            final String said = upstream.strip();
            try {
                if (said.startsWith("ssh://")) {
                    final String rest = said.substring("ssh://".length());
                    final String authority =
                            rest.indexOf('/') < 0 ? rest : rest.substring(0, rest.indexOf('/'));
                    return of(authority.substring(authority.lastIndexOf('@') + 1));
                }
                // 'user@host:path' and 'host:path' alike: git reads both as ssh.
                final java.util.regex.Matcher scp =
                        java.util.regex.Pattern.compile("(?:[^/@:]+@)?([^/:@]+):.*").matcher(said);
                return !said.contains("://") && scp.matches()
                        ? new Address(of(scp.group(1)).host(), SSH)
                        : null;
            } catch (final IllegalArgumentException ex) {
                return null;
            }
        }

        /**
         * Returns the host as a person types it: the bare name on port 22, {@code host:port} on
         * another.
         *
         * @return The name {@link #of} reads back.
         */
        public String name() {
            if (port == SSH) {
                return host;
            }
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }

        /**
         * Returns the name ssh looks the host up by in {@code known_hosts}.
         *
         * @return The bare host on port 22, {@code [host]:port} on another.
         */
        public String entry() {
            return port == SSH ? host : "[" + host + "]:" + port;
        }
    }

    /** What git says when it has not met the host. */
    private static final String UNKNOWN = "Host key verification failed";

    /** What ssh says when it has met the host and the key is not the one it remembers. */
    private static final String CHANGED = "REMOTE HOST IDENTIFICATION HAS CHANGED";

    /**
     * Returns the host of an address as {@code trust-host} takes it, so a hint can be typed back as
     * it is printed.
     *
     * @param url The address.
     * @return {@code host:port} off port 22, the bare host on it, and for an address not reached
     *     over ssh its host as the credentials name it.
     */
    public static String nameOf(final @Nullable String url) {
        final Address address = Address.ofUpstream(url);
        return address != null ? address.name() : String.valueOf(GitCredentialNames.hostOf(url));
    }

    /**
     * Tells whether git failed over the host's key rather than over a credential.
     *
     * @param said What git wrote.
     * @return {@code true} when this is about the host key.
     */
    public static boolean refusedTheHost(final String said) {
        return said.contains(UNKNOWN) || said.contains(CHANGED) || said.contains("ssh-askpass");
    }

    /**
     * Tells whether the host's key is not the one this machine remembers.
     *
     * @param said What git wrote.
     * @return {@code true} for a key that changed, which is the one to be afraid of.
     */
    public static boolean changed(final String said) {
        return said.contains(CHANGED);
    }

    /**
     * Returns the keys a host offers right now.
     *
     * @param context The machine.
     * @param host The host as {@link Address#of} reads it, on its port.
     * @return What it offered, empty when it could not be reached.
     * @throws IllegalArgumentException For a host that is no host.
     */
    public static List<Offered> offeredBy(final PoreloContext context, final String host) {
        final Address address = Address.of(host);
        final List<String> scan = new ArrayList<>(List.of("ssh-keyscan", "-T", "10"));
        if (address.port() != Address.SSH) {
            scan.addAll(List.of("-p", String.valueOf(address.port())));
        }
        scan.add(address.host());
        final CommandResult scanned = context.runner().run(Command.of(scan));
        if (!scanned.successful() && scanned.standardOutput().isBlank()) {
            return List.of();
        }
        final List<Offered> offered = new ArrayList<>();
        for (final String line : scanned.standardOutput().split("\n")) {
            final String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            final String[] fields = stripped.split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            final String fingerprint = fingerprintOf(fields[2]);
            if (!fingerprint.isEmpty()) {
                // Under the name ssh will look up, whatever the scan printed: what is written is
                // what a fetch asks for.
                offered.add(
                        new Offered(
                                fields[1],
                                fingerprint,
                                address.entry() + " " + fields[1] + " " + fields[2]));
            }
        }
        return List.copyOf(offered);
    }

    /**
     * Records one key for a host, and only if the host still offers exactly that one.
     *
     * <p><strong>The fingerprint comes from the person.</strong> They were shown what the host
     * offered, they compared it with what they were told out of band, and they say which. Asking
     * the host again here is not a second opinion - it is what stops a key that arrived between
     * being shown and being confirmed from being the one that is written.
     *
     * @param context The machine.
     * @param host The host as {@link Address#of} reads it, on its port.
     * @param fingerprint What the person confirmed, {@code SHA256:...}.
     * @return What was recorded, or {@code null} when the host offers no such key now.
     * @throws IOException If the file cannot be written.
     */
    public static @Nullable Offered trust(
            final PoreloContext context, final String host, final String fingerprint)
            throws IOException {
        final String wanted = fingerprint.strip();
        for (final Offered candidate : offeredBy(context, host)) {
            if (candidate.fingerprint().equals(wanted)) {
                final Path file = FollowCredential.knownHostsFile(context);
                Files.createDirectories(file.getParent());
                final String existing =
                        Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
                if (!existing.contains(candidate.line())) {
                    Files.writeString(
                            file,
                            existing
                                    + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                                    + candidate.line()
                                    + "\n",
                            StandardCharsets.UTF_8);
                }
                return candidate;
            }
        }
        return null;
    }

    /**
     * Tells whether this machine already remembers a key for a host.
     *
     * @param context The machine.
     * @param host The host as {@link Address#of} reads it, on its port: a key for port 22 says
     *     nothing about another.
     * @return {@code true} when something is recorded for it.
     */
    public static boolean known(final PoreloContext context, final String host) {
        final String entry = Address.of(host).entry();
        final Path file = FollowCredential.knownHostsFile(context);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    // By the names in the first field, exactly: as a prefix,
                    // '[forge.example.org.evil]:22' counted for forge.example.org.
                    .anyMatch(
                            line ->
                                    java.util.Arrays.asList(
                                                    line.strip().split("\\s+")[0].split(","))
                                            .contains(entry));
        } catch (final IOException ex) {
            return false;
        }
    }

    private static String fingerprintOf(final String base64) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Base64.getDecoder().decode(base64)));
        } catch (final java.security.NoSuchAlgorithmException | IllegalArgumentException ex) {
            return "";
        }
    }
}
