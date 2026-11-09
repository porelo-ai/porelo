package org.fuin.porelo.host;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** The canonical people records written by the host's organization follow. */
final class HostRecords {
    static final String USER = "(?!porelo(?:-|$))[a-z][a-z0-9-]{0,31}";
    static final String PROJECT = "[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?";
    static final String COMMIT = "[0-9a-f]{40}([0-9a-f]{24})?";
    private static final String PEOPLE = "/etc/porelo-host/people";
    private final HostFiles files;

    HostRecords(HostFiles files) {
        this.files = files;
    }

    record Person(
            List<String> members, boolean locked, boolean pending, @Nullable LocalDate lockedOn) {
        boolean admitted() {
            return !locked && !pending && !members.isEmpty();
        }
    }

    Map<String, Person> read() {
        final Map<String, Person> records = new TreeMap<>();
        for (Path path : files.list(files.path(PEOPLE), 69, 0700)) {
            final String name = path.getFileName().toString();
            if (name.equals("purged")) {
                purged();
            } else if (!name.endsWith(".conf")
                    || !name.substring(0, name.length() - 5).matches(USER)) {
                throw new HostRefusal(65, "the people directory holds an unknown record");
            } else {
                final String login = name.substring(0, name.length() - 5);
                files.check(path, 69, true, false, 0600);
                records.put(login, person(login, files.read(path)));
            }
        }
        return records;
    }

    List<String> purged() {
        final Path path = files.path(PEOPLE + "/purged");
        if (!files.check(path, 69, false, false, 0600)) {
            return List.of();
        }
        final List<String> names = files.read(path).lines().toList();
        if (names.stream().anyMatch(name -> !name.matches(USER))
                || names.stream().distinct().count() != names.size()) {
            throw new HostRefusal(65, "the purged login list is malformed");
        }
        return names;
    }

    private Person person(String login, String text) {
        final List<String> lines = new ArrayList<>(text.lines().toList());
        final boolean pending = !lines.isEmpty() && lines.getLast().equals("lock=pending");
        if (pending) {
            lines.removeLast();
        }
        LocalDate lockedOn = null;
        if (!lines.isEmpty() && lines.getLast().startsWith("locked_on=")) {
            final String value = lines.removeLast().substring(10);
            try {
                lockedOn = LocalDate.parse(value);
                if (!lockedOn.toString().equals(value)) {
                    throw new DateTimeParseException("Noncanonical day", value, 0);
                }
            } catch (DateTimeParseException ex) {
                throw new HostRefusal(65, "the person record holds an invalid lock day");
            }
        }
        final List<String> members = new ArrayList<>();
        String previous = "";
        int index = 0;
        while (index < lines.size() && lines.get(index).startsWith("member=")) {
            final String member = lines.get(index++).substring(7);
            final String order = membershipOrder(member);
            if (order.compareTo(previous) <= 0) {
                throw new HostRefusal(65, "the record for " + login + " has unordered memberships");
            }
            members.add(member);
            previous = order;
        }
        if ((lines.size() != index + 3 && lines.size() != index + 4)
                || !List.of("locked=false", "locked=true").contains(lines.get(index))
                || !lines.get(index + 1).startsWith("ssh=")
                || !lines.get(index + 2).startsWith("expires=")) {
            throw new HostRefusal(65, "the record for " + login + " is malformed or unsupported");
        }
        if (lines.size() == index + 4
                && !List.of("reason=membership", "reason=key").contains(lines.get(index + 3))) {
            throw new HostRefusal(65, "the person record holds an invalid lock reason");
        }
        if (!validKey(lines.get(index + 1).substring(4))) {
            throw new HostRefusal(65, "the record for " + login + " has no valid card key");
        }
        expiry(lines.get(index + 2).substring(8));
        return new Person(
                List.copyOf(members), lines.get(index).equals("locked=true"), pending, lockedOn);
    }

    private static String membershipOrder(String member) {
        if (member.equals("host-admin")) {
            return "0";
        }
        final String[] parts = member.split(" ", -1);
        if (parts.length == 2
                && parts[0].matches(PROJECT)
                && List.of("project-admin", "developer").contains(parts[1])) {
            return "1" + parts[0] + " " + (parts[1].equals("project-admin") ? "0" : "1");
        }
        throw new HostRefusal(65, "a record holds an invalid membership");
    }

    private static void expiry(String value) {
        if (value.isEmpty()) {
            return;
        }
        try {
            if (value.matches("[0-9]{14}Z")) {
                LocalDateTime.parse(
                        value,
                        DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
                                .withResolverStyle(ResolverStyle.STRICT));
            } else if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate.parse(value);
            } else {
                OffsetDateTime.parse(value.replace(' ', 'T'));
            }
        } catch (DateTimeParseException ex) {
            throw new HostRefusal(65, "invalid card expiry");
        }
    }

    static boolean validKey(String key) {
        if (!key.startsWith("ssh-ed25519 ")) {
            return false;
        }
        try {
            final String encoded = key.substring(12);
            final byte[] bytes = Base64.getDecoder().decode(encoded);
            final byte[] header =
                    ByteBuffer.allocate(19)
                            .putInt(11)
                            .put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII))
                            .putInt(32)
                            .array();
            return bytes.length == 51
                    && java.util.Arrays.equals(header, java.util.Arrays.copyOf(bytes, 19))
                    && Base64.getEncoder().encodeToString(bytes).equals(encoded);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static String fingerprint(String key) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Base64.getDecoder().decode(key.substring(12))));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }
}
