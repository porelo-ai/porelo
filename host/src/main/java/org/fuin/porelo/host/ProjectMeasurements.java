package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Publishes the follow's timestamped central answer to its current unlocked members. */
final class ProjectMeasurements {
    /** All follows of one measurement together. */
    private static final java.time.Duration FOLLOWS = java.time.Duration.ofSeconds(120);

    /** One repository's export, fetch from central and landing together. */
    private static final java.time.Duration FOLLOW = java.time.Duration.ofSeconds(60);

    /** One repository's second measurement after its follow. */
    private static final java.time.Duration REMEASURE = java.time.Duration.ofSeconds(30);

    /** All central state questions of one measurement together. */
    private static final java.time.Duration CENTRAL_QUESTIONS = java.time.Duration.ofSeconds(120);

    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final Clock clock;
    private final ProjectPublication publication;

    ProjectMeasurements(ProjectFiles files, ProjectRuntime runtime, Clock clock) {
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
        publication = new ProjectPublication(files, runtime, clock);
    }

    /** Close access before any fallible trust publication or reader replacement. */
    void revoke() throws IOException {
        final Path directory = files.etc.resolve("organization/central");
        if (files.exists(directory)) {
            files.directory(directory, false, false);
            java.nio.file.Files.setAttribute(
                    directory, "unix:mode", 0700, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }
    }

    /** Revoke all former readers before resolving any replacement reader. */
    void prepare(List<String> projects) throws IOException {
        prepare(projects, true);
        final Path directory = files.etc.resolve("organization/central");
        if (files.exists(directory)) {
            java.nio.file.Files.setAttribute(
                    directory, "unix:mode", 0711, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }
    }

    private void prepare(List<String> projects, boolean pruneDeparted) throws IOException {
        final Path directory = files.etc.resolve("organization/central");
        if (projects.isEmpty() && !files.exists(directory)) return;
        files.mkdir(directory, 0711);
        revoke();
        for (Path path : files.list(directory)) {
            files.trusted(path, false);
            final String name = path.getFileName().toString();
            if (name.endsWith(".json") && projects.contains(name.substring(0, name.length() - 5))) {
                final String content = files.text(path, false);
                files.delete(path);
                publish(path, content, publication.readers(name.substring(0, name.length() - 5)));
                continue;
            } else if (name.endsWith(".json") && !pruneDeparted) {
                continue;
            } else if (!(ProjectFiles.isStaged(name)
                    || name.endsWith(".json.new")
                    || name.endsWith(".json"))) {
                throw new HostRefusal(65, "the central states directory holds an unknown file");
            }
            files.delete(path);
        }
    }

    void measure(String account, String project) throws IOException {
        measure(account, List.of(project));
    }

    void measure(String account, List<String> projects) throws IOException {
        measure(account, projects, List.of(), Map.of(), false);
    }

    /**
     * The follow's measurement: a carried repository other than the project's own (named in {@code
     * owns}) that only lags central's main is brought up to it first.
     */
    void measure(
            @Nullable String account,
            List<String> measured,
            List<Map<String, @Nullable Object>> statuses,
            Map<String, String> owns)
            throws IOException {
        measure(account, measured, statuses, owns, true);
    }

    private void measure(
            @Nullable String account,
            List<String> measured,
            List<Map<String, @Nullable Object>> statuses,
            Map<String, String> owns,
            boolean pruneDeparted)
            throws IOException {
        final List<String> projects =
                statuses.isEmpty()
                        ? measured
                        : statuses.stream()
                                .map(row -> HostJson.string(row.get("project")))
                                .toList();
        prepare(projects, pruneDeparted);
        final var readers = new java.util.LinkedHashMap<String, List<String>>();
        for (String project : projects) {
            readers.put(project, publication.readers(project));
        }
        final Path directory = files.etc.resolve("organization/central");
        files.mkdir(directory, 0711);
        revoke();
        for (Path path : files.list(directory)) {
            files.trusted(path, false);
            final String name = path.getFileName().toString();
            if (ProjectFiles.isStaged(name) || name.endsWith(".json.new")) {
                files.delete(path);
            } else if (!name.endsWith(".json")) {
                throw new HostRefusal(65, "the central states directory holds an unknown file");
            }
        }
        for (String project : projects) {
            final Path destination = directory.resolve(project + ".json");
            if (files.exists(destination)) {
                // Revoke every project's old readers before any potentially slow central question.
                publish(
                        destination,
                        files.text(destination, false),
                        java.util.Objects.requireNonNull(
                                readers.get(project),
                                "Every measured project has resolved readers"));
            }
        }
        // Central questions share one time budget so a follow stays bounded across projects.
        final var deadline = clock.instant().plus(runtime.limits().services(CENTRAL_QUESTIONS));
        final var keys = new ProjectReadKeys(files, runtime.until(clock, deadline));
        final Map<String, String> readAts = new java.util.HashMap<>();
        final Map<String, List<?>> measuredRows = new java.util.HashMap<>();
        // Every project's states are measured first, so a follow never costs another its answer.
        for (String project : projects) {
            readAts.put(
                    project,
                    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                            .withZone(java.time.ZoneOffset.UTC)
                            .format(clock.instant()));
            Object rows = List.of();
            if (account != null
                    && measured.contains(project)
                    && clock.instant().isBefore(deadline)) {
                try {
                    rows = keys.centralState(account, project).get("repositories");
                } catch (HostRefusal | IOException ex) {
                    rows = List.of();
                }
            }
            if (!clock.instant().isBefore(deadline)
                    || !(rows instanceof List<?> values)
                    || values.stream()
                            .anyMatch(
                                    value ->
                                            !(value instanceof Map<?, ?> row)
                                                    || !HostProjects.centralRow(row))) {
                rows = List.of();
            }
            measuredRows.put(project, (List<?>) rows);
        }
        if (account != null) {
            follow(account, projects, owns, measuredRows);
        }
        for (String project : projects) {
            final String readAt =
                    java.util.Objects.requireNonNull(
                            readAts.get(project), "Every project was measured");
            final List<?> rows =
                    java.util.Objects.requireNonNull(
                            measuredRows.get(project), "Every project was measured");
            final var answer = ProjectRecords.object("project", project, "read_at", readAt);
            answer.put("repositories", rows);
            final Path previous = directory.resolve(project + ".json");
            if (statuses.isEmpty() && files.exists(previous)) {
                final var earlier =
                        HostJson.object(
                                ProjectRecords.json(
                                        files.text(previous, false),
                                        "the previous central answer does not read",
                                        65));
                for (String field : List.of("state", "why", "host_repositories")) {
                    if (earlier.containsKey(field)) answer.put(field, earlier.get(field));
                }
            }
            statuses.stream()
                    .filter(row -> project.equals(row.get("project")))
                    .findFirst()
                    .ifPresent(
                            status -> {
                                answer.put("state", status.get("state"));
                                reason(answer, status.get("why"));
                                final var progress =
                                        new java.util.ArrayList<Map<String, @Nullable Object>>();
                                for (Object value : HostJson.array(status.get("repositories"))) {
                                    final var row = HostJson.object(value);
                                    final var entry =
                                            ProjectRecords.object(
                                                    "repository",
                                                    HostJson.string(row.get("repository")),
                                                    "state",
                                                    HostJson.string(row.get("state")));
                                    reason(entry, row.get("why"));
                                    progress.add(entry);
                                }
                                answer.put("host_repositories", progress);
                            });
            publish(
                    directory.resolve(project + ".json"),
                    ProjectRecords.pythonJson(answer, false) + "\n",
                    java.util.Objects.requireNonNull(
                            readers.get(project), "Every measured project has resolved readers"));
        }
        java.nio.file.Files.setAttribute(
                directory, "unix:mode", 0711, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Brings each carried repository other than the project's own that only lags central up to it.
     * Each follow has its own cap and starts only while that cap and the row's second measurement
     * still fit in the follows' budget; one that does not fit, fails or runs out leaves its row as
     * measured.
     */
    private void follow(
            String account,
            List<String> projects,
            Map<String, String> owns,
            Map<String, List<?>> measuredRows) {
        final var budget = clock.instant().plus(runtime.limits().machines(FOLLOWS));
        for (String project : projects) {
            final String own = owns.get(project);
            final List<?> rows = measuredRows.get(project);
            if (own == null || rows == null || rows.isEmpty()) {
                continue;
            }
            final List<Object> updated = new java.util.ArrayList<>(rows);
            for (int index = 0; index < updated.size(); index++) {
                final Map<?, ?> row = (Map<?, ?>) updated.get(index);
                final String repository = HostJson.string(row.get("repository"));
                if (!"moved-on".equals(row.get("state"))
                        || own.equalsIgnoreCase(repository)
                        || clock.instant()
                                .plus(runtime.limits().services(FOLLOW))
                                .plus(runtime.limits().machines(REMEASURE))
                                .isAfter(budget)) {
                    continue;
                }
                try {
                    new ProjectReadKeys(
                                    files,
                                    runtime.until(
                                            clock,
                                            clock.instant()
                                                    .plus(runtime.limits().services(FOLLOW))))
                            .follow(account, project, repository);
                } catch (HostRefusal | IOException ex) {
                    continue;
                }
                try {
                    final Map<String, @Nullable Object> again =
                            ProjectRecords.object("repository", repository);
                    again.putAll(
                            new ProjectReadKeys(
                                            files,
                                            runtime.until(
                                                    clock,
                                                    clock.instant()
                                                            .plus(
                                                                    runtime.limits()
                                                                            .machines(REMEASURE))))
                                    .row(account, project, repository));
                    if (HostProjects.centralRow(again)) {
                        updated.set(index, again);
                    }
                } catch (HostRefusal | IOException ex) {
                    // The row keeps what the first measurement said.
                }
            }
            measuredRows.put(project, updated);
        }
    }

    private static void reason(Map<String, @Nullable Object> answer, @Nullable Object value) {
        if (!(value instanceof String text) || text.isEmpty()) return;
        final StringBuilder shown = new StringBuilder();
        text.codePoints()
                .limit(200)
                .forEach(
                        point -> {
                            final int type = Character.getType(point);
                            shown.appendCodePoint(
                                    Character.isISOControl(point)
                                                    || type == Character.LINE_SEPARATOR
                                                    || type == Character.PARAGRAPH_SEPARATOR
                                                    || type == Character.FORMAT
                                                    || type == Character.UNASSIGNED
                                                    || type == Character.SURROGATE
                                                    || type == Character.PRIVATE_USE
                                            ? '?'
                                            : point);
                        });
        answer.put("why", shown.toString());
    }

    private void publish(Path destination, String text, List<String> readers) throws IOException {
        if (files.exists(destination)) {
            files.trusted(destination, false);
            files.delete(destination);
        }
        publication.publish(destination, text, readers);
    }
}
