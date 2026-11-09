package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Reports project progress and the last measured central state without contacting a forge. */
final class HostProjects {
    private static final String PROGRESS = "/etc/porelo-host/organization/host-projects.json";
    private static final Pattern ADDRESS =
            Pattern.compile("[!-~]{0,1024}[:/]([A-Za-z0-9_][A-Za-z0-9_.-]{0,95}?)(?:\\.git)?/?");
    private static final String NO_CENTRAL =
            "no central address, so this host cannot take it; name one in the project file for a task here";
    private final HostFiles files;

    HostProjects(HostFiles files) {
        this.files = files;
    }

    List<Map<String, @Nullable Object>> status(@Nullable Map<String, @Nullable Object> applied) {
        final Map<String, @Nullable Object> entries = progress();
        if (applied != null) {
            for (Object value : array(applied.get("projects"))) {
                final Map<String, @Nullable Object> project = object(value);
                entries.putIfAbsent(
                        name(project.get("project")),
                        fields(
                                "state",
                                "carried",
                                "why",
                                project.get("why"),
                                "since",
                                project.get("since")));
            }
            optionalObject(applied.get("project_refusals"))
                    .forEach(
                            (project, why) -> {
                                final Map<String, @Nullable Object> entry =
                                        optionalObject(
                                                entries.get(
                                                        project.equals("a project")
                                                                ? project
                                                                : name(project)));
                                entry.put("state", "refused");
                                entry.put("why", why);
                                entries.put(project, entry);
                            });
        }
        final List<Map<String, @Nullable Object>> result = new ArrayList<>();
        new TreeMap<>(entries)
                .forEach(
                        (project, value) -> {
                            final Map<String, @Nullable Object> item = object(value);
                            result.add(
                                    fields(
                                            "project",
                                            project,
                                            "state",
                                            item.get("state"),
                                            "why",
                                            item.get("why"),
                                            "since",
                                            item.get("since"),
                                            "repositories",
                                            repositories(item, project)));
                        });
        return result;
    }

    private static String name(@Nullable Object value) {
        final String name = string(value);
        if (!name.matches(HostRecords.PROJECT)) {
            throw new IllegalArgumentException("Invalid project name");
        }
        return name;
    }

    private Map<String, @Nullable Object> progress() {
        final Path path = files.path(PROGRESS);
        if (!files.check(path, 69, false, false, 0600)) {
            return fields();
        }
        final Object parsed;
        try {
            parsed = parse(files.read(path));
        } catch (IllegalArgumentException ex) {
            throw new HostRefusal(65, "the host project progress does not read");
        }
        try {
            final Map<String, @Nullable Object> entries = object(parsed);
            entries.forEach(
                    (project, value) -> {
                        name(project);
                        final Object state = object(value).get("state");
                        if (!(state instanceof String)
                                || !List.of(
                                                "pending",
                                                "carried",
                                                "waiting-for-token",
                                                "held",
                                                "refused")
                                        .contains(state)) {
                            throw new IllegalArgumentException("Unknown progress state");
                        }
                    });
            return entries;
        } catch (IllegalArgumentException ex) {
            throw new HostRefusal(65, "the host project progress is malformed");
        }
    }

    private List<Map<String, @Nullable Object>> repositories(
            Map<String, @Nullable Object> item, String project) {
        final Map<String, @Nullable Object> waiting = optionalObject(item.get("repositories_why"));
        final Map<String, @Nullable Object> file = optionalObject(item.get("file"));
        final Object named = item.get("named");
        final List<?> taken = item.get("taken") == null ? List.of() : array(item.get("taken"));
        final String own =
                file.get("address") == null ? "" : repositoryName(string(file.get("address")));
        final List<Map<String, @Nullable Object>> result = new ArrayList<>();
        Map<String, @Nullable Object> centralRows = null;
        for (var entry : new TreeMap<>(optionalObject(file.get("repositories"))).entrySet()) {
            final String repository = entry.getKey();
            final Map<String, @Nullable Object> row = object(entry.getValue());
            final Object central = row.get("central");
            final String forgeName =
                    central == null
                            ? repository.toLowerCase(Locale.ROOT)
                            : repositoryName(string(central));
            final Map<String, @Nullable Object> said;
            if (named != null && !array(named).contains(repository) && !forgeName.equals(own)) {
                said =
                        fields(
                                "repository",
                                repository,
                                "state",
                                waiting.containsKey(repository) ? "held" : "not-carried",
                                "why",
                                waiting.get(repository));
            } else if (central == null) {
                said = fields("repository", repository, "state", "no-central", "why", NO_CENTRAL);
            } else if (!waiting.containsKey(repository)
                    && (taken.contains(central)
                            || (Boolean.TRUE.equals(item.get("migrated"))
                                    && central.equals(file.get("address"))))) {
                if (centralRows == null) {
                    centralRows = centralRows(project);
                }
                said =
                        fields(
                                "repository",
                                repository,
                                "state",
                                "taken",
                                "why",
                                null,
                                "central",
                                centralRows.getOrDefault(
                                        forgeName,
                                        fields("state", "cannot-say", "why", "unreachable")));
            } else {
                final Object why = waiting.get(repository);
                said =
                        fields(
                                "repository",
                                repository,
                                "state",
                                "waiting",
                                "why",
                                why == null || why.equals("")
                                        ? "waits for " + project + " to be on this host's forge"
                                        : why);
            }
            result.add(said);
        }
        return result;
    }

    private static String repositoryName(String address) {
        final var match = ADDRESS.matcher(address);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid central address");
        }
        return match.group(1).toLowerCase(Locale.ROOT);
    }

    private Map<String, @Nullable Object> centralRows(String project) {
        final Path path =
                files.path("/etc/porelo-host/organization/central/" + name(project) + ".json");
        // Unsafe paths remain refusals even when malformed measurements fall back.
        if (!files.check(path, 69, false, false, -1)) {
            return fields();
        }
        try {
            final Map<String, @Nullable Object> answer = object(parse(files.read(path)));
            if (!project.equals(answer.get("project"))) {
                return fields();
            }
            final Map<String, @Nullable Object> result = fields();
            for (Object value : array(answer.get("repositories"))) {
                final Map<String, @Nullable Object> row = object(value);
                if (!centralRow(row)) {
                    return fields();
                }
                final String repository = string(row.remove("repository")).toLowerCase(Locale.ROOT);
                result.put(repository, row);
            }
            return result;
        } catch (IllegalArgumentException | HostRefusal ex) {
            // A missing measurement must never vouch for work being at central.
            return fields();
        }
    }

    static boolean centralRow(Map<?, ?> row) {
        if (!(row.get("repository") instanceof String repository)
                || !repository.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            return false;
        }
        final String state = string(row.get("state"));
        return switch (state) {
            case "ahead" ->
                    row.keySet().equals(Set.of("repository", "state", "commits"))
                            && (row.get("commits") instanceof java.math.BigInteger commits
                                            && commits.signum() > 0
                                    || row.get("commits") instanceof Long count && count > 0);
            case "cannot-say" ->
                    row.keySet().equals(Set.of("repository", "state", "why"))
                            && row.get("why") instanceof String
                            && List.of("no-read-key", "unreachable", "no-central-main", "local")
                                    .contains(row.get("why"));
            case "up-to-date", "moved-on" -> row.keySet().equals(Set.of("repository", "state"));
            default -> false;
        };
    }
}
