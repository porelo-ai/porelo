package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Publishes the follow's timestamped central answer to its current unlocked members. */
final class ProjectMeasurements {
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
            } else if (!(name.startsWith(".porelo-host-person.")
                    || name.endsWith(".json.new")
                    || name.endsWith(".json"))) {
                throw new HostRefusal(65, "the central states directory holds an unknown file");
            }
            files.delete(path);
        }
        java.nio.file.Files.setAttribute(
                directory, "unix:mode", 0711, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    void measure(String account, String project) throws IOException {
        measure(account, List.of(project));
    }

    void measure(String account, List<String> projects) throws IOException {
        prepare(projects);
        final var readers = new java.util.LinkedHashMap<String, List<String>>();
        for (String project : projects) {
            readers.put(project, publication.readers(project));
        }
        final Path directory = files.etc.resolve("organization/central");
        files.mkdir(directory, 0711);
        java.nio.file.Files.setAttribute(
                directory, "unix:mode", 0711, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        for (Path path : files.list(directory)) {
            files.trusted(path, false);
            final String name = path.getFileName().toString();
            if (name.startsWith(".porelo-host-person.") || name.endsWith(".json.new")) {
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
        // porelo-host-person follow_central shares CENTRAL_ASK_SECONDS across the entire pass.
        final var deadline = clock.instant().plusSeconds(120);
        final var keys = new ProjectReadKeys(files, runtime.until(clock, deadline));
        for (String project : projects) {
            final String readAt =
                    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                            .withZone(java.time.ZoneOffset.UTC)
                            .format(clock.instant());
            Object rows = List.of();
            if (clock.instant().isBefore(deadline)) {
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
            final var answer = ProjectRecords.object("project", project, "read_at", readAt);
            answer.put("repositories", rows);
            publish(
                    directory.resolve(project + ".json"),
                    ProjectRecords.pythonJson(answer, false) + "\n",
                    java.util.Objects.requireNonNull(
                            readers.get(project), "Every measured project has resolved readers"));
        }
    }

    private void publish(Path destination, String text, List<String> readers) throws IOException {
        if (files.exists(destination)) {
            files.trusted(destination, false);
            files.delete(destination);
        }
        publication.publish(destination, text, readers);
    }
}
