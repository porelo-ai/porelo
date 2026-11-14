package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The host's status without a secret, which each follow writes for the host's admins and a non-root
 * caller of {@code org status} reads.
 */
final class HostStatusView {
    /** Where each follow writes the view, whole, for the admins' group to read. */
    static final String PATH = "/run/porelo-host/status.json";

    /** The group whose members read the view: the persons marked host admin for this host. */
    static final String ADMINS = "porelo-host-admins";

    private HostStatusView() {}

    /**
     * Copies from root's status only the fields that hold no secret, by name; a field root's status
     * gains later stays out until it is named here.
     *
     * @param status Root's whole status, as {@link HostOrganization#status()} answers it.
     * @param followed When the follow that writes this view ran.
     * @return The public view, in a fixed field order.
     */
    static Map<String, @Nullable Object> of(
            Map<String, @Nullable Object> status, Instant followed) {
        final List<Map<String, @Nullable Object>> persons = new ArrayList<>();
        for (Object value : array(status.get("persons"))) {
            final Map<String, @Nullable Object> person = object(value);
            final List<Map<String, @Nullable Object>> projects = new ArrayList<>();
            for (Object project : array(person.get("projects"))) {
                final Map<String, @Nullable Object> membership = object(project);
                projects.add(
                        fields(
                                "project",
                                membership.get("project"),
                                "role",
                                membership.get("role")));
            }
            persons.add(
                    fields(
                            "login",
                            person.get("login"),
                            "state",
                            person.get("state"),
                            "host_admin",
                            person.get("host_admin"),
                            "projects",
                            projects));
        }
        final List<Map<String, @Nullable Object>> projects = new ArrayList<>();
        for (Object value : array(status.get("projects"))) {
            final Map<String, @Nullable Object> project = object(value);
            final List<Map<String, @Nullable Object>> repositories = new ArrayList<>();
            for (Object row : array(project.get("repositories"))) {
                repositories.add(repository(object(row)));
            }
            projects.add(
                    fields(
                            "project",
                            project.get("project"),
                            "state",
                            project.get("state"),
                            "why",
                            project.get("why"),
                            "since",
                            project.get("since"),
                            "repositories",
                            repositories));
        }
        return fields(
                "host",
                status.get("host"),
                "organization",
                status.get("organization"),
                "commit",
                status.get("commit"),
                "followed",
                followed.truncatedTo(ChronoUnit.SECONDS).toString(),
                "persons",
                persons,
                "projects",
                projects);
    }

    private static Map<String, @Nullable Object> repository(Map<String, @Nullable Object> row) {
        final Map<String, @Nullable Object> result =
                fields(
                        "repository",
                        row.get("repository"),
                        "state",
                        row.get("state"),
                        "why",
                        row.get("why"));
        if (row.get("central") != null) {
            final Map<String, @Nullable Object> central = object(row.get("central"));
            final Map<String, @Nullable Object> measured = fields("state", central.get("state"));
            for (String field : List.of("why", "commits")) {
                if (central.containsKey(field)) {
                    measured.put(field, central.get(field));
                }
            }
            result.put("central", measured);
        }
        return result;
    }

    /**
     * Writes the view whole, by rename, readable by root and the admins' group alone.
     *
     * @param context The follow's checked files and clock.
     * @param group The admins' group id, as the follow keeps it.
     */
    static void write(FollowContext context, int group) throws IOException {
        final Map<String, @Nullable Object> view =
                of(new HostOrganization(context.host).status(), context.clock.instant());
        context.shared(context.path(PATH), HostJson.write(view) + "\n", group);
    }

    /**
     * Root's one-line form of a status, which the public view shares.
     *
     * @param status Root's status or the public view.
     * @return The host, its organization's state and the counts.
     */
    static String summary(Map<String, @Nullable Object> status) {
        return status.get("host")
                + ": "
                + (status.get("organization") == null
                        ? "no organization"
                        : status.get("organization"))
                + "; "
                + array(status.get("persons")).size()
                + " persons; "
                + array(status.get("projects")).size()
                + " projects";
    }

    /**
     * Prints the view for a caller without root: the file the last follow wrote, and its age. It
     * writes nothing and starts no child.
     *
     * @param files The host's fixed file tree.
     * @param clock Tells the view's age.
     * @param json Whether to print the view as JSON.
     * @param out Where the answer goes.
     * @return The exit code: zero, also when no follow wrote the view yet.
     */
    static int print(HostFiles files, Clock clock, boolean json, PrintWriter out) {
        try {
            final Path path = files.path(PATH);
            if (!files.check(path, 69, false, false, 0640)) {
                out.println(json ? HostJson.write(fields("followed", null)) : "not followed yet");
                return 0;
            }
            if (!Files.isReadable(path)) {
                throw new HostRefusal(
                        77,
                        "cannot read "
                                + path
                                + "; only the host's admins (group "
                                + ADMINS
                                + ") read the status without root");
            }
            final Map<String, @Nullable Object> view = view(path, files.read(path));
            final Duration age =
                    Duration.between(Instant.parse(string(view.get("followed"))), clock.instant());
            if (json) {
                view.put("age_seconds", Math.max(0, age.toSeconds()));
                out.println(HostJson.write(view));
            } else {
                out.println(
                        FollowContext.printable(
                                summary(view)
                                        + "; followed "
                                        + view.get("followed")
                                        + ", "
                                        + age(age)
                                        + " ago"));
            }
            return 0;
        } catch (HostRefusal ex) {
            final String message =
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "HostRefusal always has a safe diagnostic");
            throw new HostRefusal(
                    ex.exitCode(),
                    message.startsWith("porelo-host:") ? message : "porelo-host: " + message);
        }
    }

    private static Map<String, @Nullable Object> view(Path path, String text) {
        try {
            final Map<String, @Nullable Object> view = object(parse(text));
            string(view.get("host"));
            Instant.parse(string(view.get("followed")));
            array(view.get("persons"));
            array(view.get("projects"));
            return view;
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new HostRefusal(65, path + " holds no valid host status");
        }
    }

    /**
     * Says a duration in the largest unit that still reads as a count.
     *
     * @param age How old the view is; a negative age, from a clock set back, reads as zero.
     * @return For example {@code 5 minutes}.
     */
    static String age(Duration age) {
        final long seconds = Math.max(0, age.toSeconds());
        if (seconds < 120) {
            return seconds + " seconds";
        }
        if (seconds < 120 * 60) {
            return seconds / 60 + " minutes";
        }
        if (seconds < 48 * 3600) {
            return seconds / 3600 + " hours";
        }
        return seconds / 86400 + " days";
    }
}
