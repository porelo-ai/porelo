package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Publishes the follow's timestamped central answer to its current unlocked members. */
final class ProjectMeasurements {
    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final Clock clock;

    ProjectMeasurements(ProjectFiles files, ProjectRuntime runtime, Clock clock) {
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
    }

    /** Prune stale projects and revoke every former reader before any central question starts. */
    void prepare(List<String> projects) throws IOException {
        final Path directory = files.etc.resolve("organization/central");
        if (projects.isEmpty() && !files.exists(directory)) {
            return;
        }
        files.mkdir(directory, 0711);
        java.nio.file.Files.setAttribute(
                directory, "unix:mode", 0711, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        for (Path path : files.list(directory)) {
            files.trusted(path, false);
            final String name = path.getFileName().toString();
            if (name.startsWith(".porelo-host-person.")
                    || name.endsWith(".json.new")
                    || name.endsWith(".json")
                            && !projects.contains(name.substring(0, name.length() - 5))) {
                files.delete(path);
            } else if (!name.endsWith(".json")) {
                throw new HostRefusal(65, "the central states directory holds an unknown file");
            }
        }
        for (String project : projects) {
            final Path path = directory.resolve(project + ".json");
            if (files.exists(path)) {
                publish(path, files.text(path, false), readers(project));
            }
        }
    }

    void measure(String account, String project) throws IOException {
        measure(account, List.of(project));
    }

    void measure(String account, List<String> projects) throws IOException {
        final var readers = new java.util.LinkedHashMap<String, List<String>>();
        for (String project : projects) {
            readers.put(project, readers(project));
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

    private List<String> readers(String project) throws IOException {
        final var records = new ProjectRecords(files);
        final Path appliedPath = files.etc.resolve("organization/applied.json");
        final Object parsed =
                ProjectRecords.json(
                        files.text(appliedPath, true),
                        appliedPath + " holds no valid applied organization",
                        65);
        if (!(parsed instanceof Map<?, ?> applied)
                || !(applied.get("projects") instanceof List<?> projects)) {
            throw new HostRefusal(65, appliedPath + " holds no valid applied organization");
        }
        final List<String> readers = new ArrayList<>();
        for (Object value : projects) {
            if (!(value instanceof Map<?, ?> entry) || !project.equals(entry.get("project"))) {
                continue;
            }
            if (!(entry.get("members") instanceof List<?> members)) {
                throw new HostRefusal(65, appliedPath + " holds no valid applied organization");
            }
            for (Object item : members) {
                if (!(item instanceof Map<?, ?> member)
                        || !Boolean.FALSE.equals(member.get("locked"))
                        || !(member.get("login") instanceof String login)
                        || !login.matches(ProjectRecords.PERSON)) {
                    continue;
                }
                if (!files.exists(records.person(login))) {
                    continue;
                }
                final var person = records.personRecord(login, records.person(login));
                if (person.locked() || person.pending()) {
                    continue;
                }
                final var passwd =
                        runtime.run(
                                List.of(
                                        runtime.checked("/usr/bin/getent").toString(),
                                        "passwd",
                                        login),
                                new byte[0],
                                ProjectRuntime.SAFE_ENV,
                                java.time.Duration.ofSeconds(60));
                if (passwd.exitCode() == 2) {
                    continue;
                }
                final String[] fields = passwd.output().strip().split(":", -1);
                if (passwd.exitCode() != 0
                        || fields.length != 7
                        || !fields[0].equals(login)
                        || !fields[2].matches("[1-9][0-9]*")) {
                    throw new HostRefusal(69, "the project member's account could not be read");
                }
                readers.add("user:" + fields[2] + ":r--");
            }
            return readers;
        }
        throw new HostRefusal(65, "the host's organization names no project " + project);
    }

    private void publish(Path destination, String content, List<String> readers)
            throws IOException {
        final Path staged = files.stage(destination, ".porelo-host-person.", content);
        try {
            if (!readers.isEmpty()) {
                final var set =
                        runtime.run(
                                List.of(
                                        runtime.checked("/usr/bin/setfacl").toString(),
                                        "--modify",
                                        String.join(",", readers),
                                        staged.toString()),
                                new byte[0],
                                ProjectRuntime.SAFE_ENV,
                                java.time.Duration.ofSeconds(60));
                if (set.exitCode() != 0) {
                    throw new HostRefusal(73, "the central answer's readers could not be set");
                }
            }
            files.replace(staged, destination);
        } finally {
            files.delete(staged);
        }
    }
}
