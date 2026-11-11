package org.fuin.porelo.app;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;

/**
 * The host key an organization's card pins for a host that {@code --host} names.
 *
 * <p>A host the organization lists is reached trusting the key its card pins, and no other: not the
 * person's own {@code known_hosts}, not a key trusted through {@code trust-host}. The card counts
 * only from a read that is whole and fresh, signed by an admin the host lists now. A host it does
 * not list, or a machine with no organization, is reached as before.
 */
final class OrganizationHostPin {

    /** What ssh takes as a host on its command line: a name or an address, never an option. */
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}");

    /** A public host key on one line, as a card holds it. */
    private static final Pattern KEY =
            Pattern.compile(
                    "(ssh-ed25519|ecdsa-sha2-nistp(?:256|384|521)|ssh-rsa) [A-Za-z0-9+/]+={0,2}");

    /**
     * A host's pin.
     *
     * @param host The host's name in the organization.
     * @param address The address its card gives.
     * @param port The ssh port its card gives.
     * @param key The ssh host key its card pins.
     * @param admins The logins of its admins, whom a mismatch is told to.
     */
    record Pin(String host, String address, int port, String key, List<String> admins) {

        /** Compact constructor: the admins are copied. */
        Pin {
            admins = List.copyOf(admins);
        }

        /** The algorithms ssh may verify this key with. */
        String algorithms() {
            final String type = key.substring(0, key.indexOf(' '));
            return type.equals("ssh-rsa") ? "rsa-sha2-512,rsa-sha2-256" : type;
        }
    }

    /**
     * The hosts an organization read lists.
     *
     * @param hosts Its hosts.
     * @param whole Whether the read was fresh and whole; a stale one may hold a pin since changed.
     */
    record Listed(List<OrganizationQuestions.Host> hosts, boolean whole) {

        /** Compact constructor: the hosts are copied. */
        Listed {
            hosts = List.copyOf(hosts);
        }
    }

    /** Why no start reaches the host: its exit code and words. */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(final int code, final String message) {
            super(message);
            this.code = code;
        }

        /**
         * Returns its exit code.
         *
         * @return 65 when the organization's answer cannot be used, 69 when it cannot be had.
         */
        int code() {
            return code;
        }
    }

    /** Reads the organization's hosts; replaced by a test. */
    @FunctionalInterface
    interface Reader {

        /**
         * Reads the hosts.
         *
         * @param context The machine.
         * @return Its hosts, or {@code null} when this machine has no organization.
         * @throws Refused When the organization cannot be read at all.
         * @throws IOException When something unexpected happened.
         */
        @Nullable Listed read(PoreloContext context) throws Refused, IOException;
    }

    /** The organization this machine follows. */
    static final Reader ORGANIZATION = OrganizationHostPin::organization;

    private OrganizationHostPin() {}

    private static @Nullable Listed organization(final PoreloContext context)
            throws Refused, IOException {
        final IdentityOrganization.Reading reading;
        try {
            reading = IdentityOrganization.read(context, IdentityOrganization::real);
        } catch (IdentityChange.Refused ex) {
            throw new Refused(
                    69,
                    "your organization cannot be read, so whether it pins this host's key cannot"
                            + " be said; restore access to it (porelo org --json) and retry");
        }
        if (reading == null) {
            return null;
        }
        return new Listed(
                reading.answers().getHosts(), !reading.stale() && reading.answers().whole());
    }

    /**
     * Chooses the pin for a host.
     *
     * @param destination What {@code --host} names, {@code <login>@<host>}.
     * @param listed The organization's hosts, or {@code null} when this machine has none.
     * @return The pin, or {@code null} when the host is reached as before.
     * @throws Refused When the organization lists the host but its pin cannot be relied on.
     */
    static @Nullable Pin choose(final String destination, final @Nullable Listed listed)
            throws Refused {
        if (listed == null) {
            return null;
        }
        final String wanted = normal(destination.substring(destination.lastIndexOf('@') + 1));
        final List<OrganizationQuestions.Host> named =
                listed.hosts().stream()
                        .filter(
                                each ->
                                        wanted.equals(normal(each.host()))
                                                || each.card() != null
                                                        && wanted.equals(
                                                                normal(each.card().address())))
                        .toList();
        if (named.isEmpty()) {
            return null;
        }
        if (!listed.whole()) {
            throw new Refused(
                    69,
                    "your organization lists "
                            + wanted
                            + ", and it cannot be checked just now for the key it pins; restore"
                            + " access to it (porelo org --json) and retry");
        }
        if (named.size() > 1) {
            throw new Refused(
                    65,
                    "your organization lists more than one host as "
                            + wanted
                            + "; tell its admins: "
                            + String.join(
                                    ", ",
                                    named.stream()
                                            .flatMap(each -> admins(each).stream())
                                            .distinct()
                                            .toList()));
        }
        final OrganizationQuestions.Host host = named.getFirst();
        final Organization.Card card = host.card();
        if (card == null || card.sshHostKey().isBlank() || !signedByAnAdmin(host, card)) {
            return null;
        }
        final List<String> admins = admins(host);
        if (!ADDRESS.matcher(card.address()).matches()
                || card.sshPort() < 1
                || card.sshPort() > 65535
                || !KEY.matcher(card.sshHostKey()).matches()) {
            throw new Refused(
                    65,
                    "your organization's card of "
                            + host.host()
                            + " does not give an address, port and key ssh takes; tell its"
                            + " admins: "
                            + String.join(", ", admins));
        }
        return new Pin(host.host(), card.address(), card.sshPort(), card.sshHostKey(), admins);
    }

    /** A card counts only while a key that signed it is that of an admin the host lists now. */
    private static boolean signedByAnAdmin(
            final OrganizationQuestions.Host host, final Organization.Card card) {
        return host.admins().stream()
                .anyMatch(admin -> !admin.backup() && admin.fingerprint().equals(card.signedBy()));
    }

    private static List<String> admins(final OrganizationQuestions.Host host) {
        return host.admins().stream()
                .filter(admin -> !admin.backup())
                .map(Organization.Owner::login)
                .distinct()
                .toList();
    }

    /** A host as ssh would reach it, spelled one way: no case, no brackets, no trailing dot. */
    private static String normal(final String host) {
        String spelled = host.toLowerCase(Locale.ROOT);
        if (spelled.startsWith("[") && spelled.endsWith("]")) {
            spelled = spelled.substring(1, spelled.length() - 1);
        }
        while (spelled.endsWith(".")) {
            spelled = spelled.substring(0, spelled.length() - 1);
        }
        return spelled;
    }
}
