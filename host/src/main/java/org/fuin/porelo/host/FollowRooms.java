package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Gate room commands with the gate's credential on stdin alone. */
final class FollowRooms {
    static final String TRANSPORT =
            "/usr/libexec/porelo/transports/porelo-message-transport-matrix";
    private static final Pattern DISPLAY = Pattern.compile("[^\\p{Cc}\\p{Cf}@:]{1,64}");

    private FollowRooms() {}

    static boolean installed(FollowContext c) {
        return c.host.exists("/etc/systemd/system/porelo-matrix-gate.socket");
    }

    private static byte[] secrets(FollowContext c) throws IOException {
        return input(c, Map.of());
    }

    record Names(Map<String, String> values, List<String> refused) {}

    static @Nullable String display(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        final String name = text.strip();
        return DISPLAY.matcher(name).matches() ? name : null;
    }

    static Names names(Map<String, @Nullable Object> applied, List<String> persons) {
        final Map<String, String> names = new TreeMap<>();
        final List<String> refused = new ArrayList<>();
        for (Object value : array(applied.get("persons"))) {
            final var person = object(value);
            final String login = string(person.get("login"));
            if (!persons.contains(login)) {
                continue;
            }
            final String name = display(person.get("name"));
            if (Boolean.TRUE.equals(person.get("name_refused"))) {
                refused.add(login);
            } else if (person.get("name") == null) {
                continue;
            } else if (name != null) {
                names.put(login, name);
            } else {
                refused.add(login);
            }
        }
        return new Names(names, refused);
    }

    private static byte[] input(FollowContext c, Map<String, String> names) throws IOException {
        final var account = c.runtime.required("porelo-matrix");
        final String path = account.home() + "/.config/porelo/matrix/gate/admin-token";
        final var result = c.as(account.login(), List.of("cat", "--", path), new byte[0]);
        final String token = result.output().replaceFirst("\n$", "");
        if (result.exitCode() != 0) {
            throw new HostRefusal(
                    69, "the homeserver administrator's token could not be read from " + path);
        }
        if (!token.matches("[!-~]{1,4096}")) {
            throw new HostRefusal(69, path + " holds no homeserver administrator's token");
        }
        final var input = fields("secrets", fields("PORELO_MATRIX_ADMIN_TOKEN", token));
        if (!names.isEmpty()) {
            input.put("settings", fields("names", names));
        }
        return ProjectRecords.pythonJson(input, false).getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, @Nullable Object> room(
            FollowContext c, String verb, String project, List<String> persons) throws IOException {
        return room(c, verb, project, persons, Map.of());
    }

    static Map<String, @Nullable Object> room(
            FollowContext c,
            String verb,
            String project,
            List<String> persons,
            Map<String, String> names)
            throws IOException {
        c.runtime.require("runuser", "env", "cat", TRANSPORT);
        final List<String> argv = new ArrayList<>(List.of(TRANSPORT, verb, "--project", project));
        if (verb.equals("keep-room")) {
            argv.addAll(List.of("--persons", String.join(",", persons)));
        }
        final byte[] input = input(c, names);
        final HostPrograms.Result result;
        try {
            result = c.as("porelo-matrix", argv, input);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        if (result.exitCode() != 0) {
            throw new HostRefusal(
                    result.exitCode() == 75
                                    || result.exitCode() == 78
                                    || result.exitCode() == 64 && !names.isEmpty()
                            ? result.exitCode()
                            : 69,
                    verb + " exited " + result.exitCode());
        }
        if (verb.equals("drop-room")) {
            return fields();
        }
        final Object parsed =
                ProjectRecords.json(result.output(), "keep-room answered malformed JSON", 69);
        if (!(parsed instanceof Map<?, ?> roomAnswer)
                || !(roomAnswer.get("room") instanceof String room)
                || !room.matches("[!-~]+")) {
            throw new HostRefusal(69, "keep-room answered no room");
        }
        final var answer = object(parsed);
        final Object value = answer.getOrDefault("removed", List.of());
        if (!(value instanceof List<?> removed)
                || removed.stream()
                        .anyMatch(
                                name ->
                                        !(name instanceof String text)
                                                || !text.matches("[!-~]+"))) {
            throw new HostRefusal(69, "keep-room answered no room");
        }
        if (!removed.isEmpty()) {
            c.err.println(
                    "porelo-host: "
                            + project
                            + ": out of its room: "
                            + String.join(",", removed.stream().map(HostJson::string).toList()));
        }
        return fields(
                "homeserver", "unix:/run/porelo-matrix/client.sock", "room", answer.get("room"));
    }

    static void expel(FollowContext c, String login) throws IOException {
        if (c.runtime.find("porelo-matrix") == null) {
            return;
        }
        final byte[] input = secrets(c);
        try {
            if (c.as("porelo-matrix", List.of(TRANSPORT, "expel", login), input).exitCode() != 0) {
                throw new HostRefusal(69, "the transport could not expel the person");
            }
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }
}
