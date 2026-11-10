package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Validates a complete fetched contract before any host access can change. */
final class FollowState {
    private final FollowContext context;

    FollowState(FollowContext context) {
        this.context = context;
    }

    static @Nullable Instant expiry(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new HostRefusal(65, "invalid card expiry");
        }
        try {
            if (text.matches("[0-9]{14}Z")) {
                return java.time.LocalDateTime.parse(
                                text,
                                DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
                                        .withResolverStyle(ResolverStyle.STRICT))
                        .toInstant(ZoneOffset.UTC);
            }
            if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(text.replace(' ', 'T')).toInstant();
        } catch (DateTimeParseException ex) {
            throw new HostRefusal(65, "invalid card expiry");
        }
    }

    static boolean login(@Nullable Object value) {
        return value instanceof String text
                && text.matches(HostRecords.USER)
                && !text.equals("root");
    }

    static boolean key(@Nullable Object value) {
        return value instanceof String text && HostRecords.validKey(text);
    }

    static String keyOf(ProjectRecords.Person person) {
        return person.card().getFirst().substring(4);
    }

    Map<String, @Nullable Object> take(
            Map<String, @Nullable Object> answer,
            String host,
            @Nullable Map<String, @Nullable Object> previous,
            Map<String, ProjectRecords.Person> records,
            Map<String, Long> accounts,
            long minimumUid)
            throws IOException {
        if (!(answer.get("revision") instanceof String revision)
                || !revision.matches(HostRecords.COMMIT)
                || !(answer.get("host_card") instanceof Map<?, ?> card)
                || !(answer.get("persons") instanceof List<?> people)
                || !(answer.get("projects") instanceof List<?> projects)
                || !(answer.get("admins") instanceof List<?> admins)) {
            throw new HostRefusal(
                    65,
                    "porelo org fetch answered malformed revision, host card, persons, projects or admins");
        }
        if (!host.equals(card.get("host"))) {
            throw new HostRefusal(65, "porelo org fetch answered a host card for another host");
        }
        final List<Object> revisions = new ArrayList<>();
        if (previous != null) {
            revisions.addAll(
                    previous.containsKey("revisions")
                            ? array(previous.get("revisions"))
                            : List.of(string(previous.get("commit"))));
            if (!revision.equals(previous.get("commit")) && revisions.contains(revision)) {
                final String words =
                        "refused-state: an older organization revision was served again";
                context.refusal(words);
                throw new HostRefusal(65, words);
            }
        }
        final Map<String, String> holders = new HashMap<>();
        records.forEach((login, record) -> holders.put(keyOf(record), login));
        if (previous != null) {
            for (Object old : array(previous.get("persons"))) {
                final var row = object(old);
                holders.put(string(row.get("ssh")), string(row.get("login")));
            }
        }
        final Set<String> logins = new HashSet<>(), keys = new HashSet<>();
        final List<Map<String, @Nullable Object>> persons = new ArrayList<>();
        for (Object value : people) {
            if (!(value instanceof Map<?, ?> row)
                    || !login(row.get("login"))
                    || !key(row.get("key"))
                    || !(row.get("revoked") instanceof Boolean)
                    || !row.containsKey("expires")
                    || !logins.add(string(row.get("login")))
                    || !keys.add(string(row.get("key")))) {
                throw new HostRefusal(
                        65, "porelo org fetch answered a malformed or duplicate person");
            }
            expiry(row.get("expires"));
            final String name = string(row.get("login")), ssh = string(row.get("key"));
            final Long uid = accounts.get(name);
            if (!holders.getOrDefault(ssh, name).equals(name)
                    || uid != null && (uid < minimumUid || !records.containsKey(name))) {
                throw new HostRefusal(
                        65, name + ": the organization's key or login belongs to another account");
            }
            final var person = object(row);
            person.put("ssh", ssh);
            persons.add(person);
        }
        validateAdmins(admins, logins, false);
        if (admins.isEmpty()) {
            throw new HostRefusal(65, "porelo org fetch answered no host admin keys");
        }
        final Object organizationAdmins = answer.getOrDefault("organization_admins", List.of());
        if (!(organizationAdmins instanceof List<?> orgAdmins)) {
            throw new HostRefusal(
                    65, "porelo org fetch answered malformed organization admin keys");
        }
        validateAdmins(orgAdmins, logins, true);
        final Set<String> names = new HashSet<>(), duplicates = new HashSet<>();
        for (Object value : projects) {
            if (value instanceof Map<?, ?> row
                    && row.get("project") instanceof String name
                    && name.matches(HostRecords.PROJECT)
                    && !names.add(name)) {
                duplicates.add(name);
            }
        }
        final List<Map<String, @Nullable Object>> accepted = new ArrayList<>();
        final Map<String, @Nullable Object> refusals = fields();
        for (Object value : projects) {
            final String name =
                    value instanceof Map<?, ?> row
                                    && row.get("project") instanceof String text
                                    && text.matches(HostRecords.PROJECT)
                            ? text
                            : "a project";
            try {
                final var project = project(value, logins, duplicates);
                Object address = project.get("address");
                String why = null;
                if (address != null && !FollowProjects.address(address)) {
                    address = null;
                    why = "the organization names an unusable address for this project";
                    context.err.println("porelo-host: " + name + ": " + why);
                }
                project.put("name", name);
                project.put("address", address);
                project.put("why", why);
                Object since = null;
                if (previous != null) {
                    for (Object old : array(previous.get("projects"))) {
                        final var p = object(old);
                        if (name.equals(p.get("project"))) {
                            since = p.get("since");
                        }
                    }
                }
                project.put(
                        "since", since == null ? LocalDate.now(context.clock).toString() : since);
                accepted.add(project);
            } catch (HostRefusal ex) {
                refusals.put(name, ex.getMessage());
                context.err.println("porelo-host: " + name + ": " + ex.getMessage());
            }
        }
        final Set<String> revoked = new TreeSet<>();
        final Map<String, Map<String, @Nullable Object>> held = new HashMap<>();
        if (previous != null) {
            for (Object v : array(previous.get("revoked"))) {
                revoked.add(string(v));
            }
            for (Object old : array(previous.get("persons"))) {
                final var p = object(old);
                held.put(string(p.get("login")), p);
            }
        }
        for (String name : revoked) {
            if (!logins.contains(name) && held.containsKey(name)) {
                persons.add(new java.util.LinkedHashMap<>(held.get(name)));
            }
        }
        for (var person : persons) {
            final String name = string(person.get("login"));
            if (Boolean.TRUE.equals(person.get("revoked"))) {
                revoked.add(name);
            } else if (held.containsKey(name)
                    && !string(person.get("key")).equals(held.get(name).get("ssh"))) {
                revoked.remove(name);
            }
        }
        for (var person : persons) {
            person.put("revoked", revoked.contains(string(person.get("login"))));
        }
        revisions.add(revision);
        return fields(
                "revision",
                revision,
                "commit",
                revision,
                "host_card",
                card,
                "persons",
                persons,
                "admins",
                admins,
                "organization_admins",
                orgAdmins,
                "projects",
                accepted,
                "project_refusals",
                refusals,
                "revoked",
                List.copyOf(revoked),
                "revisions",
                new ArrayList<>(new LinkedHashSet<>(revisions)));
    }

    private static void validateAdmins(List<?> admins, Set<String> logins, boolean organization) {
        final Set<String> keys = new HashSet<>();
        for (Object value : admins) {
            if (!(value instanceof Map<?, ?> row)
                    || !login(row.get("login"))
                    || !organization && !logins.contains(row.get("login"))
                    || !key(row.get("key"))
                    || !keys.add(string(row.get("key")))
                    || !HostRecords.fingerprint(string(row.get("key")))
                            .equals(row.get("fingerprint"))
                    || !(row.get("backup") instanceof Boolean)) {
                throw new HostRefusal(
                        65,
                        "porelo org fetch answered malformed "
                                + (organization ? "organization admin keys" : "host admin keys"));
            }
        }
    }

    static Map<String, @Nullable Object> project(
            @Nullable Object value, Set<String> logins, Set<String> duplicates) {
        if (!(value instanceof Map<?, ?> row)
                || !(row.get("project") instanceof String name)
                || !name.matches(HostRecords.PROJECT)
                || name.equals("default")
                || duplicates.contains(name)
                || !(row.get("members") instanceof List<?> members)
                || !(row.get("signing_keys") instanceof List<?> signing)
                || !row.containsKey("address")) {
            throw new HostRefusal(65, "porelo org fetch answered a malformed or duplicate project");
        }
        final Object named = row.get("repositories");
        if (named != null
                && (!(named instanceof List<?> list)
                        || list.stream()
                                .anyMatch(
                                        n ->
                                                !(n instanceof String text)
                                                        || !text.matches("[a-z0-9._-]{1,100}"))
                        || new HashSet<>(list).size() != list.size())) {
            throw new HostRefusal(
                    65, "porelo org fetch answered malformed repositories for the project");
        }
        final Set<String> seen = new HashSet<>();
        final List<Object> kept = new ArrayList<>();
        for (Object member : members) {
            if (member instanceof Map<?, ?> m
                    && m.get("login") instanceof String login
                    && !logins.contains(login)) {
                continue;
            }
            if (!(member instanceof Map<?, ?> m)
                    || !(m.get("login") instanceof String login)
                    || !logins.contains(login)
                    || !seen.add(login)
                    || !java.util.Arrays.asList("admin", "developer").contains(m.get("role"))
                    || !(m.get("locked") instanceof Boolean)) {
                throw new HostRefusal(65, "porelo org fetch answered malformed project members");
            }
            kept.add(member);
        }
        for (Object entry : signing) {
            if (!(entry instanceof Map<?, ?> k)
                    || !(k.get("login") instanceof String login)
                    || !login.matches(HostRecords.USER)
                    || !java.util.Arrays.asList("admin", "developer").contains(k.get("role"))
                    || !key(k.get("key"))
                    || !k.keySet()
                            .containsAll(List.of("valid_from", "valid_until", "revoked_since"))) {
                throw new HostRefusal(
                        65, "porelo org fetch answered malformed project signing keys");
            }
            for (String field : List.of("valid_from", "valid_until", "revoked_since")) {
                expiry(k.get(field));
            }
        }
        final var result = object(row);
        result.put("members", kept);
        return result;
    }
}
