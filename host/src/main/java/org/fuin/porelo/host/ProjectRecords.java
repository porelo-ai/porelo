package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** Validates the Python helper's records before credentials or mutations are allowed. */
final class ProjectRecords {
    static final String PROJECT = "[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?";
    static final String PERSON = "(?!porelo(?:-|$))[a-z][a-z0-9-]{0,31}";
    static final String REPOSITORY = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";
    static final String COMMIT = "[0-9a-f]{40}([0-9a-f]{24})?";
    static final String ACCOUNT = "[a-z_][a-z0-9_-]{0,31}";
    private static final byte[] KEY_HEADER =
            new byte[] {
                0, 0, 0, 11, 's', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', 0, 0, 0, 32
            };
    final ProjectFiles files;

    ProjectRecords(ProjectFiles files) {
        this.files = files;
    }

    static String signingKey(String value, String source, int code) {
        final String error =
                source + " must be one canonical ssh-ed25519 signing key without a comment";
        if (!value.startsWith("ssh-ed25519 ")) {
            throw new HostRefusal(code, error);
        }
        final String encoded = value.substring(12);
        try {
            final byte[] wire = Base64.getDecoder().decode(encoded);
            if (wire.length != 51
                    || !java.util.Arrays.equals(wire, 0, 19, KEY_HEADER, 0, 19)
                    || !Base64.getEncoder().encodeToString(wire).equals(encoded)) {
                throw new HostRefusal(code, error);
            }
        } catch (IllegalArgumentException ex) {
            throw new HostRefusal(code, error);
        }
        return value;
    }

    Map<String, String> values(Path path, int code) throws IOException {
        final Map<String, String> values = new LinkedHashMap<>();
        for (String line : files.text(path, false).lines().toList()) {
            final int separator = line.indexOf('=');
            if (separator < 0
                    || values.putIfAbsent(
                                    line.substring(0, separator), line.substring(separator + 1))
                            != null) {
                throw new HostRefusal(code, path + " has a malformed or duplicate setting");
            }
        }
        return values;
    }

    static String setting(
            Map<String, String> values, String key, String pattern, Path path, int code) {
        final String value = values.getOrDefault(key, "");
        if (!value.matches(pattern)) {
            throw new HostRefusal(code, path + " names no valid " + key);
        }
        return value;
    }

    String publisher(String project) throws IOException {
        final Path path = record(project);
        final Map<String, String> values;
        try {
            values = values(path, 65);
        } catch (HostRefusal ex) {
            final String message =
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "Host refusal always has a diagnostic");
            if (message.endsWith("is not UTF-8")) {
                throw new HostRefusal(65, message);
            }
            throw ex;
        }
        if (!Set.of("admin", "signed_by", "publisher", "removing").containsAll(values.keySet())) {
            throw new HostRefusal(65, path + " has a malformed or duplicate setting");
        }
        if (values.containsKey("removing") && !values.get("removing").equals("true")) {
            throw new HostRefusal(65, path + " has a malformed removal state");
        }
        setting(values, "admin", PERSON, path, 65);
        signingKey(values.getOrDefault("signed_by", ""), path + " signed_by signing key", 65);
        return setting(
                values,
                "publisher",
                java.util.regex.Pattern.quote("porelo-publisher-" + project),
                path,
                65);
    }

    Path record(String project) {
        return files.etc.resolve("projects/" + project + ".conf");
    }

    static void pushRegistration(Path path, String text, String project) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            final int at = line.indexOf('=');
            if (at < 0
                    || !Set.of("admin", "signed_by", "publisher").contains(line.substring(0, at))
                    || values.putIfAbsent(line.substring(0, at), line.substring(at + 1)) != null)
                throw new HostRefusal(65, path + " has a malformed or duplicate setting");
        }
        final String refusal = path + " holds an invalid project registration";
        if (!values.getOrDefault("admin", "").matches(HostRecords.USER)
                || !values.getOrDefault("publisher", "").equals("porelo-publisher-" + project))
            throw new HostRefusal(65, refusal);
        try {
            signingKey(values.getOrDefault("signed_by", ""), "project", 65);
        } catch (HostRefusal ex) {
            throw new HostRefusal(65, refusal);
        }
    }

    Path person(String admin) {
        return files.etc.resolve("people/" + admin + ".conf");
    }

    Path conf() {
        return files.etc.resolve("forge.conf");
    }

    record Person(List<String> members, boolean locked, boolean pending, List<String> card) {
        String format(List<String> membership) {
            final StringBuilder text = new StringBuilder();
            membership.stream()
                    .distinct()
                    .sorted(Comparator.comparing(ProjectRecords::memberOrder))
                    .forEach(item -> text.append("member=").append(item).append('\n'));
            text.append("locked=").append(locked).append('\n');
            card.forEach(line -> text.append(line).append('\n'));
            if (pending) {
                text.append("lock=pending\n");
            }
            return text.toString();
        }
    }

    Person personRecord(String user, Path path) throws IOException {
        final List<String> lines = new ArrayList<>(files.text(path, true).lines().toList());
        final boolean pending = !lines.isEmpty() && lines.getLast().equals("lock=pending");
        if (pending) {
            lines.removeLast();
        }
        final List<String> members = new ArrayList<>();
        String previous = "";
        int index = 0;
        while (index < lines.size() && lines.get(index).startsWith("member=")) {
            final String member = lines.get(index++).substring(7);
            final String order = memberOrder(member);
            if (order.compareTo(previous) <= 0) {
                throw malformed(user);
            }
            previous = order;
            members.add(member);
        }
        if (lines.size() < index + 3
                || !Set.of("locked=false", "locked=true").contains(lines.get(index))
                || !lines.get(index + 1).startsWith("ssh=")
                || !lines.get(index + 2).startsWith("expires=")) {
            throw malformed(user);
        }
        int tail = index + 3;
        if (tail < lines.size()
                && Set.of("reason=membership", "reason=key").contains(lines.get(tail))) {
            tail++;
        }
        if (tail < lines.size()
                && lines.get(tail).matches("locked_on=[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            tail++;
        }
        if (tail != lines.size()) {
            throw malformed(user);
        }
        signingKey(lines.get(index + 1).substring(4), "person card key", 65);
        return new Person(
                List.copyOf(members),
                lines.get(index).equals("locked=true"),
                pending,
                List.copyOf(lines.subList(index + 1, lines.size())));
    }

    Person admitted(String admin) throws IOException {
        final Path person = person(admin);
        if (!files.exists(person)) {
            throw new HostRefusal(65, admin + " is no person on this host");
        }
        final Person state = personRecord(admin, person);
        if (state.locked() || state.pending()) {
            throw new HostRefusal(65, admin + " is locked on this host");
        }
        if (state.members().isEmpty()) {
            throw new HostRefusal(65, admin + " holds no membership on this host");
        }
        return state;
    }

    void namedInOrganization(String project, String admin) throws IOException {
        final Path path = files.etc.resolve("organization/applied.json");
        files.directory(ProjectFiles.parent(path), false, true);
        final String unnamed =
                "the host's organization names no project " + project + " for " + admin;
        if (!files.exists(path)) {
            throw new HostRefusal(65, unnamed + "; none is applied yet");
        }
        final Object parsed =
                json(files.text(path, true), path + " holds no valid applied organization", 65);
        if (!(parsed instanceof Map<?, ?> applied)
                || !(applied.get("projects") instanceof List<?> projects)
                || !(applied.get("revoked") instanceof List<?> revoked)
                || revoked.stream().anyMatch(item -> !(item instanceof String))) {
            throw new HostRefusal(65, path + " holds no valid applied organization");
        }
        if (revoked.contains(admin)) {
            throw new HostRefusal(
                    65, admin + " is revoked in the host's organization and registers no project");
        }
        for (Object item : projects) {
            if (item instanceof Map<?, ?> entry
                    && project.equals(entry.get("project"))
                    && entry.get("members") instanceof List<?> members) {
                for (Object member : members) {
                    if (member instanceof Map<?, ?> row
                            && admin.equals(row.get("login"))
                            && "admin".equals(row.get("role"))) {
                        return;
                    }
                }
            }
        }
        throw new HostRefusal(65, unnamed);
    }

    record Affected(Path path, String content) {}

    List<Affected> peopleAfterRemoval(String project) throws IOException {
        final Path directory = files.etc.resolve("people");
        final List<Affected> affected = new ArrayList<>();
        if (!files.exists(directory)) {
            return affected;
        }
        files.directory(directory, true, false);
        for (Path path : files.list(directory)) {
            final String name = path.getFileName().toString();
            if (name.equals("purged")) {
                files.trusted(path, true);
                continue;
            }
            if (!name.matches(PERSON + "\\.conf")) {
                throw new HostRefusal(65, "the people directory holds an unknown record");
            }
            final Person state = personRecord(name.substring(0, name.length() - 5), path);
            final List<String> remaining =
                    state.members().stream()
                            .filter(item -> !item.startsWith(project + " "))
                            .toList();
            if (!remaining.equals(state.members())) {
                affected.add(new Affected(path, state.format(remaining)));
            }
        }
        return affected;
    }

    private static String memberOrder(String member) {
        if (member.equals("host-admin")) {
            return "0";
        }
        final String[] parts = member.split(" ", -1);
        if (parts.length != 2
                || !parts[0].matches(PROJECT)
                || !Set.of("project-admin", "developer").contains(parts[1])) {
            throw new HostRefusal(65, "a person record holds an invalid membership");
        }
        return "1" + parts[0] + "\0" + (parts[1].equals("project-admin") ? "0" : "1");
    }

    private static HostRefusal malformed(String user) {
        return new HostRefusal(65, "the record for " + user + " is malformed");
    }

    static @Nullable Object json(String text, String error, int code) {
        try {
            return Json.parseExact(text);
        } catch (JsonException ex) {
            throw new HostRefusal(code, error);
        }
    }

    static Map<String, @Nullable Object> object(Object... pairs) {
        final Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    static String pythonJson(@Nullable Object value, boolean compact) {
        if (value instanceof Map<?, ?> map) {
            final List<String> parts = new ArrayList<>();
            map.forEach(
                    (key, item) ->
                            parts.add(
                                    pythonJson(key, compact)
                                            + (compact ? ":" : ": ")
                                            + pythonJson(item, compact)));
            return "{" + String.join(compact ? "," : ", ", parts) + "}";
        }
        if (value instanceof List<?> items) {
            return "["
                    + items.stream()
                            .map(item -> pythonJson(item, compact))
                            .collect(java.util.stream.Collectors.joining(compact ? "," : ", "))
                    + "]";
        }
        if (value instanceof String text) {
            final String encoded = Json.write(text);
            final StringBuilder ascii = new StringBuilder();
            for (char character : encoded.toCharArray()) {
                if (character >= 127) {
                    ascii.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                } else {
                    ascii.append(character);
                }
            }
            return ascii.toString();
        }
        return Json.write(value);
    }
}
