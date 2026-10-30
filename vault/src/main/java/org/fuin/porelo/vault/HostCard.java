package org.fuin.porelo.vault;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * A host's card: where it is reached, the key its ssh answers with, and the ports of its services.
 *
 * <p>The file is a YAML body followed by one armored SSH signature over the body's exact bytes, as
 * a {@link PersonCard} is, so {@code ssh-keygen -Y verify} checks it as well as Porelo does. The
 * signature is the host admin's, made on their own machine, since nothing on the host holds their
 * key. The body names no signer: whoever reads the card says whose key they trust, and the
 * signature's own key is checked against that.
 *
 * @param host The host's name, a lower-case DNS label.
 * @param address Where it is reached, a host name or an IP address.
 * @param sshPort The port its ssh listens on.
 * @param sshHostKey The key its ssh answers with, {@code ssh-ed25519 AAAA...}, as {@code
 *     known_hosts} holds it.
 * @param forgeWebPort The port of the forge's web interface.
 * @param forgeGitPort The port of the forge's git over ssh.
 * @param matrixPort The port of its Matrix server.
 * @param body The signed bytes, exactly as written.
 * @param signature The armored signature.
 */
public record HostCard(
        String host,
        String address,
        int sshPort,
        String sshHostKey,
        int forgeWebPort,
        int forgeGitPort,
        int matrixPort,
        String body,
        String signature) {

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";

    private static final String END = "-----END SSH SIGNATURE-----";

    /** The fields of each mapping, by its path; {@code services} is the one nesting a card has. */
    private static final Map<String, Set<String>> MAPPINGS =
            Map.of(
                    "", Set.of("host", "address", "ssh_port", "ssh_host_key", "services"),
                    "services", Set.of("forge", "matrix"),
                    "services.forge", Set.of("web_port", "git_port"),
                    "services.matrix", Set.of("port"));

    private static final List<String> REQUIRED =
            List.of(
                    "host",
                    "address",
                    "ssh_port",
                    "ssh_host_key",
                    "services.forge.web_port",
                    "services.forge.git_port",
                    "services.matrix.port");

    /**
     * A host's name: one lower-case DNS label, as a host name and a Matrix server name carry it.
     */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /** An IPv4 address as written plainly: four decimal parts from 0 to 255, no leading zero. */
    private static final Pattern IPV4 =
            Pattern.compile("((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.|$)){4}(?<!\\.)");

    /** A host name: dot-separated DNS labels. An IP address is read as one, by {@link #address}. */
    private static final Pattern NAME =
            Pattern.compile(
                    "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                            + "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    /**
     * Decimal as written: no sign, no leading zero, no octal, hex or underscores YAML 1.1 reads.
     */
    private static final Pattern PORT = Pattern.compile("[1-9][0-9]{0,4}");

    private static final Pattern HOST_KEY =
            Pattern.compile(
                    "(ssh-ed25519|ecdsa-sha2-nistp(?:256|384|521)|ssh-rsa) ([A-Za-z0-9+/]+={0,2})");

    private static final Scalar NONE = new Scalar("", true);

    /**
     * Reads a card, checking its form but not yet its signature.
     *
     * @param text The file's content.
     * @param origin Where it came from, for messages.
     * @return The card.
     * @throws CardException If the body is not a host card or not one signature follows it.
     */
    public static HostCard read(final String text, final String origin) {
        final int start = signatureStart(text);
        if (start < 0) {
            throw new CardException(origin + " carries no signature");
        }
        final String body = text.substring(0, start);
        final Map<String, Scalar> fields = fields(body, origin);
        for (final String field : REQUIRED) {
            if (fields.getOrDefault(field, NONE).value().isBlank()) {
                throw new CardException(origin + ": '" + field + "' is missing");
            }
        }
        // A person's machine writes these into its ssh configuration a line at a time.
        for (final Map.Entry<String, Scalar> field : fields.entrySet()) {
            if (field.getValue().value().chars().anyMatch(Character::isISOControl)) {
                throw new CardException(
                        origin + ": '" + field.getKey() + "' holds a control character");
            }
        }
        final String host = fields.getOrDefault("host", NONE).value();
        if (!HOST.matcher(host).matches()) {
            throw new CardException(
                    origin
                            + ": '"
                            + printable(host)
                            + "' is not a host name, a lower-case DNS label");
        }
        final String address = fields.getOrDefault("address", NONE).value();
        if (!address(address)) {
            throw new CardException(
                    origin
                            + ": '"
                            + printable(address)
                            + "' is not an address, a host name or an IP address");
        }
        final String hostKey = fields.getOrDefault("ssh_host_key", NONE).value();
        hostKey(hostKey, origin + ": 'ssh_host_key'");
        return new HostCard(
                host,
                address,
                port(fields, "ssh_port", origin),
                hostKey,
                port(fields, "services.forge.web_port", origin),
                port(fields, "services.forge.git_port", origin),
                port(fields, "services.matrix.port", origin),
                body,
                block(text.substring(start), origin));
    }

    /**
     * Writes a card and signs it.
     *
     * @param host The host's name.
     * @param address Where it is reached.
     * @param sshPort The port its ssh listens on.
     * @param sshHostKey The key its ssh answers with, {@code ssh-ed25519 AAAA...}, with no comment.
     * @param forgeWebPort The port of the forge's web interface.
     * @param forgeGitPort The port of the forge's git over ssh.
     * @param matrixPort The port of its Matrix server.
     * @param key The host admin's key, which signs.
     * @return The file's content.
     */
    public static String write(
            final String host,
            final String address,
            final int sshPort,
            final String sshHostKey,
            final int forgeWebPort,
            final int forgeGitPort,
            final int matrixPort,
            final SigningKey key) {
        final StringBuilder body = new StringBuilder();
        body.append("host: ").append(quoted(host)).append('\n');
        body.append("address: ").append(quoted(address)).append('\n');
        body.append("ssh_port: ").append(sshPort).append('\n');
        body.append("ssh_host_key: ").append(quoted(sshHostKey)).append('\n');
        body.append("services:\n");
        body.append("  forge:\n");
        body.append("    web_port: ").append(forgeWebPort).append('\n');
        body.append("    git_port: ").append(forgeGitPort).append('\n');
        body.append("  matrix:\n");
        body.append("    port: ").append(matrixPort).append('\n');
        final String file =
                body
                        + SshSignature.sign(
                                key,
                                body.toString().getBytes(StandardCharsets.UTF_8),
                                PersonCard.NAMESPACE);
        // Read back, so a card this writes is one this reads: a port out of range is refused here.
        read(file, "the host card for " + printable(host));
        return file;
    }

    /**
     * Returns the fingerprint of the key the signature claims, before anything is verified.
     *
     * @return {@code SHA256:...}, as {@code ssh-keygen -l} prints it.
     * @throws CardException If the signature cannot be read.
     */
    public String signer() {
        return fingerprint(signerBlob());
    }

    /**
     * Checks that the card is signed by the key trusted for its host admin, and by nothing else.
     *
     * @param fingerprint The fingerprint trusted, {@code SHA256:...}.
     * @throws CardException Naming another signer, or a signature that does not verify.
     */
    public void checkSignedBy(final String fingerprint) {
        final byte[] blob = signerBlob();
        final String signer = fingerprint(blob);
        if (!signer.equals(fingerprint)) {
            throw new CardException(
                    "the host card of "
                            + host
                            + " is signed by "
                            + signer
                            + ", not "
                            + fingerprint);
        }
        if (!SshSignature.verify(
                body.getBytes(StandardCharsets.UTF_8), signature, blob, PersonCard.NAMESPACE)) {
            throw new CardException(
                    "the host card of " + host + " does not verify against " + signer);
        }
    }

    private byte[] signerBlob() {
        final byte[] blob = SshSignature.signerOf(signature);
        if (blob == null) {
            throw new CardException("the host card of " + host + " carries no readable signature");
        }
        return blob;
    }

    private static String fingerprint(final byte[] blob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static int port(
            final Map<String, Scalar> fields, final String field, final String origin) {
        final Scalar port = fields.getOrDefault(field, NONE);
        // Plain, so the number signed is the number read: a quoted one is text YAML never typed.
        if (!port.plain()
                || !PORT.matcher(port.value()).matches()
                || Integer.parseInt(port.value()) > 65535) {
            throw new CardException(
                    origin + ": '" + field + "' is not a port, a plain number from 1 to 65535");
        }
        return Integer.parseInt(port.value());
    }

    private static void hostKey(final String line, final String what) {
        // Exactly, not stripped: a person's machine writes the line as it stands into known_hosts.
        final Matcher matcher = HOST_KEY.matcher(line);
        if (!matcher.matches()) {
            throw new CardException(what + " is not an OpenSSH public key, 'ssh-ed25519 AAAA...'");
        }
        // The blob's own type, not just the prefix: a key of another kind under the line's name
        // would otherwise be pinned for a host that never answers with it.
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(matcher.group(2)));
            final String type = new String(SshWire.readString(buffer), StandardCharsets.US_ASCII);
            if (!type.equals(matcher.group(1)) || !wellFormed(type, buffer)) {
                throw new CardException(
                        what + " is not an OpenSSH public key, 'ssh-ed25519 AAAA...'");
            }
        } catch (final RuntimeException ex) {
            if (ex instanceof CardException card) {
                throw card;
            }
            throw new CardException(what + " is not an OpenSSH public key, 'ssh-ed25519 AAAA...'");
        }
    }

    /**
     * Says whether the rest of a key's blob is that kind's, field by field and nothing after: a
     * line a host never answers with would be pinned for it, and OpenSSH refuses one with more.
     */
    private static boolean wellFormed(final String type, final ByteBuffer buffer) {
        if ("ssh-ed25519".equals(type)) {
            return SshWire.readString(buffer).length == 32 && !buffer.hasRemaining();
        }
        if ("ssh-rsa".equals(type)) {
            final byte[] exponent = SshWire.readString(buffer);
            final byte[] modulus = SshWire.readString(buffer);
            // Positive numbers in their shortest form, an odd exponent and 2048 bits at least, as
            // ssh-keygen takes them.
            return mpint(exponent)
                    && mpint(modulus)
                    && new BigInteger(exponent).testBit(0)
                    && new BigInteger(exponent).compareTo(BigInteger.TWO) > 0
                    && new BigInteger(modulus).bitLength() >= 2048
                    && !buffer.hasRemaining();
        }
        // ecdsa-sha2-<curve>: the curve named again, then an uncompressed point on that curve.
        final String curve = type.substring("ecdsa-sha2-".length());
        final String named = new String(SshWire.readString(buffer), StandardCharsets.US_ASCII);
        final byte[] point = SshWire.readString(buffer);
        final int size =
                switch (curve) {
                    case "nistp256" -> 32;
                    case "nistp384" -> 48;
                    default -> 66;
                };
        return curve.equals(named)
                && point.length == 1 + 2 * size
                && point[0] == 4
                && !buffer.hasRemaining()
                && onCurve(
                        curve,
                        new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + size)),
                        new BigInteger(1, Arrays.copyOfRange(point, 1 + size, point.length)));
    }

    /** An SSH mpint that is positive and has no leading byte it does not need. */
    private static boolean mpint(final byte[] value) {
        if (value.length == 0 || (value[0] & 0x80) != 0) {
            return false;
        }
        return value[0] != 0 || value.length > 1 && (value[1] & 0x80) != 0;
    }

    /** Says whether a point satisfies its NIST curve's equation, y² = x³ + ax + b mod p. */
    private static boolean onCurve(final String curve, final BigInteger x, final BigInteger y) {
        final java.security.spec.ECParameterSpec parameters;
        try {
            final java.security.AlgorithmParameters found =
                    java.security.AlgorithmParameters.getInstance("EC");
            found.init(
                    new java.security.spec.ECGenParameterSpec(
                            "secp" + curve.substring("nistp".length()) + "r1"));
            parameters = found.getParameterSpec(java.security.spec.ECParameterSpec.class);
        } catch (final java.security.GeneralSecurityException ex) {
            return false;
        }
        final java.security.spec.EllipticCurve equation = parameters.getCurve();
        final BigInteger p = ((java.security.spec.ECFieldFp) equation.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        final BigInteger right =
                x.pow(3).add(equation.getA().multiply(x)).add(equation.getB()).mod(p);
        return y.pow(2).mod(p).equals(right);
    }

    /**
     * Says whether a text is a host name or an IP address, read without asking any resolver: an
     * address the host's files carry must be one thing, never a port or a list.
     */
    static boolean address(final String text) {
        if (text.length() > 253) {
            return false;
        }
        if (text.matches("[0-9.]+")) {
            // Four decimal parts and no leading zero: ssh reads 0177 as octal and 127.1 as
            // 127.0.0.1, so any other spelling would name another address than the card shows.
            return IPV4.matcher(text).matches();
        }
        if (text.indexOf(':') >= 0) {
            // IPv6, and nothing a resolver would need: no zone, no brackets, no IPv4 part.
            if (!text.matches("[0-9A-Fa-f:]+")) {
                return false;
            }
            try {
                java.net.InetAddress.ofLiteral(text);
                return true;
            } catch (final IllegalArgumentException ex) {
                return false;
            }
        }
        // A last label in digits or hex is an address to a resolver, 0x7f000001 among them.
        final String last = text.substring(text.lastIndexOf('.') + 1);
        return NAME.matcher(text).matches()
                && !last.matches("[0-9]+")
                && !last.matches("0[xX][0-9A-Fa-f]*");
    }

    /** The armor's inside: lines of base64 alone, as ssh-keygen wraps them, padded at the end. */
    private static final Pattern ARMOR =
            Pattern.compile("([A-Za-z0-9+/]{1,76}\n)*[A-Za-z0-9+/]{1,76}={0,2}\n");

    private static int signatureStart(final String text) {
        if (text.startsWith(BEGIN)) {
            return 0;
        }
        final int at = text.indexOf("\n" + BEGIN);
        return at < 0 ? -1 : at + 1;
    }

    private static String block(final String text, final String origin) {
        final int end = text.indexOf(END);
        if (end < 0) {
            throw new CardException(origin + ": a signature is not closed");
        }
        // As ssh-keygen writes it: the marks on lines of their own, one of each.
        if (!text.startsWith(BEGIN + "\n")
                || text.charAt(end - 1) != '\n'
                || text.indexOf(BEGIN, 1) >= 0 && text.indexOf(BEGIN, 1) < end
                || !ARMOR.matcher(text.substring(BEGIN.length() + 1, end)).matches()) {
            throw new CardException(
                    origin + ": the signature is not armored as ssh-keygen writes it");
        }
        int next = end + END.length();
        if (next < text.length() && text.charAt(next) == '\n') {
            next++;
        }
        if (text.startsWith(BEGIN, next)) {
            throw new CardException(origin + " carries more than one signature");
        }
        if (next < text.length()) {
            throw new CardException(origin + ": only its signature may follow the card");
        }
        return text.substring(0, next);
    }

    /**
     * Reads the body from the parser's events into its fields by path, {@code
     * services.forge.web_port}.
     *
     * <p>Not through a constructor, for the reasons {@link PersonCard} gives: merge keys, aliases,
     * tags and YAML 1.1's numbers are refused, and every value stays the text that was signed. The
     * only mappings are those {@link #MAPPINGS} names, each where it names it.
     */
    private static Map<String, Scalar> fields(final String body, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        final List<Event> events = new ArrayList<>();
        try {
            new Yaml(new SafeConstructor(options))
                    .parse(new java.io.StringReader(body))
                    .forEach(events::add);
        } catch (final RuntimeException ex) {
            throw new CardException("Cannot read " + origin + ": " + ex.getMessage());
        }
        if (events.size() < 5
                || !events.get(0).is(Event.ID.StreamStart)
                || !events.get(1).is(Event.ID.DocumentStart)
                || !opens(events.get(2), origin)) {
            throw new CardException(origin + " is not a host card");
        }
        final Map<String, Scalar> fields = new LinkedHashMap<>();
        final int end = mapping(events, 3, "", origin, fields);
        if (end != events.size() - 2
                || !events.get(end).is(Event.ID.DocumentEnd)
                || !events.get(end + 1).is(Event.ID.StreamEnd)) {
            throw new CardException(origin + " is not a host card");
        }
        return fields;
    }

    /** Reads one mapping's fields from just after its start; returns the index after its end. */
    private static int mapping(
            final List<Event> events,
            final int from,
            final String path,
            final String origin,
            final Map<String, Scalar> fields) {
        final Set<String> allowed =
                Objects.requireNonNull(MAPPINGS.get(path), "only a mapping MAPPINGS names is read");
        final Set<String> seen = new HashSet<>();
        int at = from;
        while (!events.get(at).is(Event.ID.MappingEnd)) {
            final String field = scalar(events.get(at), origin).value();
            final String named = path.isEmpty() ? field : path + "." + field;
            if (!allowed.contains(field)) {
                throw new CardException(
                        origin + ": '" + printable(named) + "' is not a field of a host card");
            }
            if (!seen.add(field)) {
                throw new CardException(origin + ": '" + named + "' is given twice");
            }
            final Event value = events.get(at + 1);
            if (!MAPPINGS.containsKey(named)) {
                fields.put(named, scalar(value, origin));
                at += 2;
            } else if (opens(value, origin)) {
                at = mapping(events, at + 2, named, origin, fields);
            } else if (scalar(value, origin).equals(NONE)) {
                // Nothing where a mapping belongs holds no field, so the one missing is named.
                at += 2;
            } else {
                throw new CardException(origin + ": '" + named + "' is not a mapping");
            }
        }
        return at + 1;
    }

    private static boolean opens(final Event event, final String origin) {
        if (!(event instanceof MappingStartEvent start)) {
            return false;
        }
        if (start.getAnchor() != null || start.getTag() != null) {
            throw new CardException(origin + " is not a host card: no anchors or tags");
        }
        return true;
    }

    private static Scalar scalar(final Event event, final String origin) {
        if (!(event instanceof ScalarEvent scalar)
                || scalar.getAnchor() != null
                || scalar.getTag() != null) {
            throw new CardException(origin + " is not a host card: only fields of text or numbers");
        }
        final String value = scalar.getValue();
        // A plain null is no value, as YAML reads it.
        return scalar.isPlain() && List.of("", "~", "null", "Null", "NULL").contains(value)
                ? NONE
                : new Scalar(value, scalar.isPlain());
    }

    /** As YAML's double-quoted scalar; a control character is refused rather than escaped. */
    private static String quoted(final String value) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new CardException("'" + printable(value) + "' holds a control character");
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String printable(final String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }

    /** A value as written, and whether it was plain, which a port must be. */
    private record Scalar(String value, boolean plain) {}
}
