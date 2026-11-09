package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Finds a task certificate record belonging to the authenticated sudo person. */
final class HostTasks {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);
    private final HostFiles files;

    HostTasks(HostFiles files) {
        this.files = files;
    }

    static void checkFingerprint(String fingerprint) {
        if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw new HostRefusal(
                    64,
                    "a task must match [a-z0-9][a-z0-9._-]{0,63}, a task key's fingerprint SHA256:<43 base64>");
        }
    }

    Map<String, @Nullable Object> show(HostPerson person, String fingerprint) {
        final HostRecords.Person record = new HostRecords(files).read().get(person.login());
        if (record == null || !record.admitted()) {
            throw new HostRefusal(65, person.login() + " is no unlocked person of this host");
        }
        final Path root = files.path("/var/lib/porelo-host/tasks");
        final List<Path> paths;
        if (files.check(root, 73, false, true, 0700)) {
            paths = files.list(root.resolve(person.login()), 73, 0700);
        } else {
            paths = List.of();
        }
        Map<String, @Nullable Object> found = null;
        for (Path path : paths) {
            final String name = path.getFileName().toString();
            if (!name.endsWith(".json")
                    || !name.substring(0, name.length() - 5).matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new HostRefusal(65, path.getParent() + " holds an unknown entry");
            }
            final Map<String, @Nullable Object> entry =
                    taskRecord(path, person.login(), name.substring(0, name.length() - 5));
            if (found == null && fingerprint.equals(entry.get("fingerprint"))) {
                found = entry;
            }
        }
        if (found != null) {
            return found;
        }
        throw new HostRefusal(65, "no task certificate is recorded for " + fingerprint);
    }

    private Map<String, @Nullable Object> taskRecord(Path path, String login, String task) {
        files.check(path, 73, true, false, 0600);
        final String text = files.read(path);
        try {
            final Map<String, @Nullable Object> entry = object(parse(text));
            if (!task.equals(entry.get("task"))
                    || !login.equals(entry.get("login"))
                    || !(entry.get("certificate") instanceof String)
                    || !(entry.get("fingerprint") instanceof String)) {
                throw new IllegalArgumentException("Invalid task fields");
            }
            final String time = string(entry.get("valid_before"));
            LocalDateTime.parse(
                    time.endsWith("Z") ? time.substring(0, time.length() - 1) : time, TIME);
            return entry;
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new HostRefusal(65, path + " holds no valid task record");
        }
    }
}
