package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The existing central-state reader policy and atomic named-user ACL publication. */
final class ProjectPublication {
    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final java.time.Clock clock;

    ProjectPublication(ProjectFiles files, ProjectRuntime runtime, java.time.Clock clock) {
        this.clock = clock;
        this.files = files;
        this.runtime = runtime;
    }

    List<String> readers(String project) throws IOException {
        final var records = new ProjectRecords(files);
        final Path appliedPath = files.etc.resolve("organization/applied.json");
        final Object parsed =
                ProjectRecords.json(
                        files.text(appliedPath, true),
                        appliedPath + " holds no valid applied organization",
                        65);
        if (!(parsed instanceof Map<?, ?> applied)
                || !(applied.get("projects") instanceof List<?> projects)
                || !(applied.get("persons") instanceof List<?> persons)
                || !(applied.get("revoked") instanceof List<?> revoked)
                || revoked.stream()
                        .anyMatch(
                                value ->
                                        !(value instanceof String login)
                                                || !login.matches(ProjectRecords.PERSON))) {
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
                final var cards =
                        persons.stream()
                                .filter(
                                        candidate ->
                                                candidate instanceof Map<?, ?> card
                                                        && login.equals(card.get("login")))
                                .toList();
                if (revoked.contains(login)
                        || cards.size() != 1
                        || !(cards.getFirst() instanceof Map<?, ?> card)
                        || !Boolean.FALSE.equals(card.get("revoked"))
                        || !card.containsKey("expires")
                        || expired(card.get("expires"))) continue;
                if (!files.exists(records.person(login))) {
                    continue;
                }
                final var person = records.personRecord(login, records.person(login));
                if (person.locked()
                        || person.pending()
                        || expired(
                                person.card().get(1).substring("expires=".length()).isEmpty()
                                        ? null
                                        : person.card().get(1).substring("expires=".length()))) {
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
                        || !fields[2].matches("[1-9][0-9]{0,9}")
                        || Long.parseLong(fields[2]) >= 4294967295L) {
                    throw new HostRefusal(69, "the project member's account could not be read");
                }
                readers.add("user:" + fields[2] + ":r--");
            }
            return readers;
        }
        throw new HostRefusal(65, "the host's organization names no project " + project);
    }

    private boolean expired(@org.jspecify.annotations.Nullable Object value) {
        final var end = FollowState.expiry(value);
        return end != null && !clock.instant().isBefore(end);
    }

    void publish(Path destination, String content, List<String> readers) throws IOException {
        files.directory(ProjectFiles.parent(destination), false, false);
        final Path staged =
                ProjectFiles.stagePrivate(destination, ".porelo-host-person.", content, null);
        try {
            complete(staged, destination, readers);
        } finally {
            files.delete(staged);
        }
    }

    /** The daemon's read limit must never stop the host recording further judged pushes. */
    void publish(Path destination, Path ledger, List<String> readers) throws IOException {
        files.directory(ProjectFiles.parent(destination), false, false);
        files.trusted(ledger, true);
        final Path staged =
                ProjectFiles.stagePrivate(destination, ".porelo-host-person.", "", null);
        try {
            try (var source =
                            java.nio.file.Files.newInputStream(
                                    ledger, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    var sink =
                            java.nio.channels.FileChannel.open(
                                    staged,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                source.transferTo(java.nio.channels.Channels.newOutputStream(sink));
                sink.force(true);
            }
            complete(staged, destination, readers);
        } finally {
            files.delete(staged);
        }
    }

    private void complete(Path staged, Path destination, List<String> readers) throws IOException {
        files.trusted(staged, true);
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
                throw new HostRefusal(73, "the published answer's readers could not be set");
            }
        }
        files.replace(staged, destination);
    }
}
