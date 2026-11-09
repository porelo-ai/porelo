package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Root's read-only view of the organization as last applied on this host. */
final class HostOrganization {
    private static final String ORGANIZATION = "/etc/porelo-host/organization/";
    private final HostFiles files;

    HostOrganization(HostFiles files) {
        this.files = files;
    }

    boolean revoked(String login) {
        final Map<String, @Nullable Object> applied = applied();
        return applied != null && array(applied.get("revoked")).contains(login);
    }

    Map<String, @Nullable Object> status() {
        final String host = host();
        final String pinned = pin();
        final Map<String, @Nullable Object> applied = applied();
        final Map<String, @Nullable Object> persons = new TreeMap<>();
        final HostRecords records = new HostRecords(files);
        records.read()
                .forEach(
                        (login, person) -> {
                            final List<Map<String, @Nullable Object>> projects = new ArrayList<>();
                            for (String member : person.members()) {
                                if (!member.equals("host-admin")) {
                                    final String[] parts = member.split(" ");
                                    projects.add(fields("project", parts[0], "role", parts[1]));
                                }
                            }
                            final var row =
                                    fields(
                                            "login",
                                            login,
                                            "state",
                                            person.pending()
                                                    ? "pending"
                                                    : person.locked() ? "locked" : "active",
                                            "host_admin",
                                            person.members().contains("host-admin"),
                                            "projects",
                                            projects);
                            final var lockedOn = person.lockedOn();
                            if (lockedOn != null) {
                                row.put("purge_on", lockedOn.plusDays(7).toString());
                            }
                            persons.put(login, row);
                        });
        for (String login : records.purged()) {
            persons.putIfAbsent(login, named(login, "purged"));
        }
        if (applied != null) {
            for (Object value : array(applied.get("persons"))) {
                final String login = string(object(value).get("login"));
                persons.putIfAbsent(login, named(login, "named"));
            }
        }
        return fields(
                "host",
                host,
                "organization",
                state(),
                "refused",
                refusal(),
                "commit",
                applied == null ? null : applied.get("commit"),
                "pinned",
                pinned,
                "persons",
                new ArrayList<>(persons.values()),
                "projects",
                new HostProjects(files).status(applied));
    }

    private static Map<String, @Nullable Object> named(String login, String state) {
        return fields("login", login, "state", state, "host_admin", false, "projects", List.of());
    }

    private String host() {
        final List<String> values =
                files.read("/etc/porelo-host/forge.conf")
                        .lines()
                        .filter(line -> line.startsWith("host="))
                        .map(line -> line.substring(5))
                        .toList();
        if (values.size() != 1) {
            throw new HostRefusal(69, "/etc/porelo-host/forge.conf names no single valid host");
        }
        if (!values.getFirst().matches("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?")) {
            throw new HostRefusal(69, "forge.conf names no valid host");
        }
        return values.getFirst();
    }

    private @Nullable String pin() {
        final Path pins = files.path(ORGANIZATION + "pin");
        if (files.check(pins, 69, false, false, 0600)) {
            final List<String> rows = files.read(pins).lines().toList();
            if (rows.isEmpty()) {
                throw new HostRefusal(65, "the organization pin holds no valid host admin keys");
            }
            for (String row : rows) {
                final String[] parts = row.split(" ", 2);
                if (parts.length != 2
                        || !parts[0].matches(HostRecords.USER)
                        || parts[0].equals("root")
                        || !HostRecords.validKey(parts[1])) {
                    throw new HostRefusal(
                            65, "the organization pin holds no valid host admin keys");
                }
            }
            return HostRecords.fingerprint(rows.getFirst().split(" ", 2)[1]);
        }
        final Path legacy = files.path(ORGANIZATION + "pin.conf");
        files.check(
                java.util.Objects.requireNonNull(
                        legacy.getParent(), "Pin has an organization directory"),
                69,
                false,
                true,
                0700);
        if (!files.check(legacy, 69, false, false, 0600)) {
            return null;
        }
        final List<String> rows = files.read(legacy).lines().toList();
        if (rows.size() != 2
                || !rows.get(0).startsWith("admin=")
                || !rows.get(1).startsWith("key=")
                || !rows.get(0).substring(6).matches(HostRecords.USER)
                || !HostRecords.validKey(rows.get(1).substring(4))) {
            throw new HostRefusal(65, legacy + " holds no valid pinned host admin");
        }
        return HostRecords.fingerprint(rows.get(1).substring(4));
    }

    @Nullable Map<String, @Nullable Object> applied() {
        final Path path = files.path(ORGANIZATION + "applied.json");
        if (!files.check(path, 69, false, false, 0600)) {
            return null;
        }
        final String text = files.read(path);
        try {
            final Map<String, @Nullable Object> applied = object(parse(text));
            if (!string(applied.get("commit")).matches(HostRecords.COMMIT)) {
                throw new IllegalArgumentException("Invalid commit");
            }
            for (String field : List.of("projects", "persons")) {
                for (Object row : array(applied.get(field))) {
                    object(row);
                }
            }
            for (Object login : array(applied.get("revoked"))) {
                string(login);
            }
            return applied;
        } catch (IllegalArgumentException ex) {
            throw new HostRefusal(65, path + " holds no valid applied organization");
        }
    }

    private @Nullable String state() {
        if (!files.exists(ORGANIZATION + "pin")) {
            return null;
        }
        return files.exists(ORGANIZATION + "read/organization.added")
                ? "following"
                : "waiting-for-token";
    }

    private @Nullable Object refusal() {
        final String path = ORGANIZATION + "refused.json";
        if (!files.exists(path)) {
            return null;
        }
        final Map<String, @Nullable Object> answer = object(parse(files.read(path)));
        if (!answer.containsKey("words")) {
            throw new IllegalArgumentException("The follow refusal must contain words");
        }
        return answer.get("words");
    }
}
