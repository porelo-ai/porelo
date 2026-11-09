package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Finds a task certificate record for root or the authenticated sudo person. */
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

    Map<String, @Nullable Object> show(@Nullable HostPerson person, String fingerprint) {
        final Map<String, HostRecords.Person> records = new HostRecords(files).read();
        final HostRecords.Person caller = person == null ? null : records.get(person.login());
        if (person != null) {
            if (caller == null || !caller.admitted()) {
                throw new HostRefusal(65, person.login() + " is no unlocked person of this host");
            }
            if (new HostOrganization(files).revoked(person.login())) {
                throw new HostRefusal(
                        65, "the host's organization names " + person.login() + " revoked");
            }
        }
        Map<String, @Nullable Object> found = null;
        for (Task task : read()) {
            if (found == null
                    && fingerprint.equals(task.entry().get("fingerprint"))
                    && (caller == null || task.visibleTo(caller))) {
                found = task.entry();
            }
        }
        if (found != null) {
            return found;
        }
        throw new HostRefusal(65, "no task certificate is recorded for " + fingerprint);
    }

    record Task(String login, String name, Map<String, @Nullable Object> entry) {
        boolean visibleTo(HostRecords.Person person) {
            for (String member : person.members()) {
                final String[] membership = member.split(" ", -1);
                if (membership.length == 2
                        && List.of("task:" + membership[0] + ":" + name)
                                .equals(entry.get("principals"))) {
                    return true;
                }
            }
            return false;
        }

        Instant end() {
            final String time = string(entry.get("valid_before"));
            return LocalDateTime.parse(
                            time.endsWith("Z") ? time.substring(0, time.length() - 1) : time, TIME)
                    .toInstant(ZoneOffset.UTC);
        }
    }

    List<Task> read() {
        final Path root = files.path("/var/lib/porelo-host/tasks");
        if (!files.check(root, 73, false, true, 0700)) {
            return List.of();
        }
        final List<Task> found = new ArrayList<>();
        for (Path directory : files.list(root, 73, 0700)) {
            final String user = directory.getFileName().toString();
            if (!user.matches(HostRecords.USER)) {
                throw new HostRefusal(65, root + " holds an unknown entry");
            }
            for (Path path : files.list(directory, 73, 0700)) {
                final String name = path.getFileName().toString();
                if (!name.endsWith(".json")
                        || !name.substring(0, name.length() - 5)
                                .matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                    throw new HostRefusal(65, directory + " holds an unknown entry");
                }
                final String task = name.substring(0, name.length() - 5);
                found.add(new Task(user, task, taskRecord(path, user, task)));
            }
        }
        return found;
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
