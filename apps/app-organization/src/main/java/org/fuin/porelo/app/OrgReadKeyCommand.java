package org.fuin.porelo.app;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.ForgeReadKey;
import picocli.CommandLine.Option;

/** Reads a deploy key and its forge token without printing either file's contents. */
abstract class OrgReadKeyCommand extends OrgReading {

    /** Asks the forge; replaced by a test, which has none. */
    @FunctionalInterface
    interface Forge {
        ForgeReadKey at(String address, String token, HttpClient client);
    }

    private static final int MOST_KEY_BYTES = 16 * 1024;

    private static final int MOST_TOKEN_BYTES = 8 * 1024;

    private static final Pattern KEY =
            Pattern.compile(
                    "((?:ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+|sk-[a-z0-9@.-]+))"
                            + " ([A-Za-z0-9+/]+={0,2})(?: [^\\n]*)?\\n?");

    @Option(names = "--settings", required = true, description = "The organization's settings.")
    private Path settings = Path.of("");

    @Option(names = "--key", required = true, description = "The public half of the host's key.")
    private Path key = Path.of("");

    Forge forge =
            (address, token, client) ->
                    new ForgeReadKey(
                            ForgeReadKey.api(ForgeAddress.of(address).forge()),
                            address,
                            token,
                            client);

    final Read read(final PoreloContext machine) throws IOException {
        final String token = token(machine);
        final String publicKey = publicKey();
        final String address = OrgFetchCommand.address(settings);
        try {
            return new Read(forge.at(address, token, OrganizationAccess.http(machine)), publicKey);
        } catch (IllegalArgumentException ex) {
            throw new Refused(Refused.USAGE, "--settings names no repository's ssh address", null);
        }
    }

    record Read(ForgeReadKey forge, String key) {}

    private static String token(final PoreloContext machine) throws IOException {
        final byte[] read = machine.input().stream().readNBytes(MOST_TOKEN_BYTES + 1);
        if (read.length > MOST_TOKEN_BYTES) {
            throw new Refused(Refused.USAGE, "standard input holds more than a token", null);
        }
        final String token = new String(read, StandardCharsets.UTF_8).strip();
        if (token.isEmpty()) {
            throw new Refused(Refused.USAGE, "standard input holds no token", null);
        }
        if (token.chars().anyMatch(each -> each <= ' ' || each >= 0x7f)) {
            throw new Refused(Refused.USAGE, "standard input holds more than a token", null);
        }
        return token;
    }

    /** The key's type and base64: the machine's comment stays here. */
    private String publicKey() throws IOException {
        OrgFetchCommand.regular(key, "--key");
        final Refused none = new Refused(Refused.USAGE, "--key holds no public key", null);
        if (Files.size(key) > MOST_KEY_BYTES) {
            throw none;
        }
        final Matcher matcher;
        try {
            matcher = KEY.matcher(Files.readString(key, StandardCharsets.UTF_8));
        } catch (java.nio.charset.MalformedInputException ex) {
            throw none;
        }
        if (!matcher.matches() || !namesItself(matcher.group(1), matcher.group(2))) {
            throw none;
        }
        return matcher.group(1) + " " + matcher.group(2);
    }

    /** A key's bytes begin with its type, so that words that look like a key are not taken. */
    private static boolean namesItself(final String type, final String base64) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        final byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length <= 4 + name.length
                || bytes[0] != 0
                || bytes[1] != 0
                || bytes[2] != 0
                || (bytes[3] & 0xff) != name.length) {
            return false;
        }
        return java.util.Arrays.equals(bytes, 4, 4 + name.length, name, 0, name.length);
    }
}
