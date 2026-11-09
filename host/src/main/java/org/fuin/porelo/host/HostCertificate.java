package org.fuin.porelo.host;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The bounded input and ordered fields of OpenSSH's certificate inspection. */
record HostCertificate(
        String type,
        String fingerprint,
        String signedBy,
        String keyId,
        List<String> principals,
        @Nullable Instant after,
        Instant before) {
    static final String KIND = "ssh-ed25519-cert-v01@openssh.com";
    static final int MAXIMUM = 16384;
    private static final String FINGERPRINT = "SHA256:[A-Za-z0-9+/]{43}";
    private static final List<String> HEADERS =
            List.of(
                    "Type",
                    "Public key",
                    "Signing CA",
                    "Key ID",
                    "Serial",
                    "Valid",
                    "Principals",
                    "Critical Options",
                    "Extensions");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

    static String line(byte[] data) {
        if (data.length == 0 || data.length > MAXIMUM) {
            throw new HostRefusal(64, "the task certificate is empty or longer than 16384 bytes");
        }
        for (byte value : data) {
            if (value < 0) {
                throw new HostRefusal(64, "the task certificate is not ASCII");
            }
        }
        final String input = new String(data, StandardCharsets.US_ASCII);
        final String text = input.endsWith("\n") ? input.substring(0, input.length() - 1) : input;
        final String[] words = text.split(" ", -1);
        if (!text.matches("[ -~]+")
                || (words.length != 2 && words.length != 3)
                || !words[0].equals(KIND)) {
            throw invalidLine();
        }
        try {
            if (words[1].length() % 4 != 0) {
                throw invalidLine();
            }
            Base64.getDecoder().decode(words[1]);
        } catch (IllegalArgumentException ex) {
            throw invalidLine();
        }
        return words[0] + " " + words[1];
    }

    private static HostRefusal invalidLine() {
        return new HostRefusal(64, "standard input holds no single " + KIND + " line");
    }

    static HostCertificate read(String text) {
        try {
            return fields(text);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw new HostRefusal(
                    65,
                    "ssh-keygen answered no certificate of the shape a task's takes, with a start and an end");
        }
    }

    private static HostCertificate fields(String text) {
        final Map<String, String> fields = new LinkedHashMap<>();
        final List<String> principals = new ArrayList<>();
        String current = "";
        final var lines = text.lines().toList();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            if (line.startsWith("                ")) {
                if (current.isEmpty()) {
                    throw new IllegalArgumentException("Missing header");
                }
                if (current.equals("Principals")) {
                    principals.add(line.substring(16));
                }
                continue;
            }
            final var header =
                    java.util.regex.Pattern.compile(" {8}([A-Za-z ]+): ?(.*)").matcher(line);
            if (!header.matches()
                    || !HEADERS.contains(header.group(1))
                    || fields.containsKey(header.group(1))) {
                throw new IllegalArgumentException("Invalid header");
            }
            current = header.group(1);
            fields.put(current, header.group(2));
        }
        if (!new ArrayList<>(fields.keySet()).equals(HEADERS)) {
            throw new IllegalArgumentException("Invalid headers");
        }
        final String publicKey = required(fields, "Public key");
        final String signer = required(fields, "Signing CA");
        final String keyId = required(fields, "Key ID");
        final var span =
                java.util.regex.Pattern.compile("(?:from (\\S+) to|before) (\\S+)")
                        .matcher(required(fields, "Valid"));
        if (!publicKey.matches("ED25519-CERT " + FINGERPRINT)
                || !signer.matches("ED25519 " + FINGERPRINT + " \\(using ssh-ed25519\\)")
                || !keyId.matches("\".*\"")
                || !span.matches()) {
            throw new IllegalArgumentException("Invalid certificate fields");
        }
        return new HostCertificate(
                required(fields, "Type"),
                publicKey.substring(13),
                signer.split(" ")[1],
                keyId.substring(1, keyId.length() - 1),
                required(fields, "Principals").equals("(none)")
                        ? List.of()
                        : List.copyOf(principals),
                span.group(1) == null ? null : time(span.group(1)),
                time(span.group(2)));
    }

    private static String required(Map<String, String> fields, String name) {
        return java.util.Objects.requireNonNull(
                fields.get(name), "Validated headers include every certificate field");
    }

    private static Instant time(String text) {
        return LocalDateTime.parse(text, TIME).toInstant(ZoneOffset.UTC);
    }

    String project(String login, String task, String key, HostRecords.Person person, Instant now) {
        if (!type.equals(KIND + " user certificate")) {
            throw new HostRefusal(65, "the task certificate is no user certificate");
        }
        if (!signedBy.equals(HostRecords.fingerprint(key))) {
            throw new HostRefusal(
                    65,
                    "the task certificate is signed by "
                            + signedBy
                            + ", not by "
                            + login
                            + "'s current key");
        }
        final String[] principal =
                principals.size() == 1 ? principals.getFirst().split(":", -1) : new String[0];
        if (principal.length != 3
                || !principal[0].equals("task")
                || !principal[2].equals(task)
                || !(principal[1].equals("default") || principal[1].matches(HostRecords.PROJECT))) {
            throw new HostRefusal(
                    65,
                    "the task certificate names not exactly one principal task:<project>:" + task);
        }
        if (after != null && after.isAfter(now) || !now.isBefore(before)) {
            throw new HostRefusal(65, "the task certificate does not hold now");
        }
        final String project = principal[1];
        if (!project.equals("default")
                && person.members().stream()
                        .noneMatch(
                                member ->
                                        !member.equals("host-admin")
                                                && member.startsWith(project + " "))) {
            throw new HostRefusal(
                    65,
                    "the task certificate names project "
                            + project
                            + ", of which "
                            + login
                            + " is no member here");
        }
        return project;
    }

    Map<String, @Nullable Object> entry(String login, String task, String line) {
        return HostJson.fields(
                "task",
                task,
                "login",
                login,
                "fingerprint",
                fingerprint,
                "signed_by",
                signedBy,
                "key_id",
                keyId,
                "principals",
                principals,
                "valid_after",
                after == null ? null : after.toString(),
                "valid_before",
                before.toString(),
                "certificate",
                line);
    }

    @Override
    public String toString() {
        return "HostCertificate[contents withheld]";
    }
}
