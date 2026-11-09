package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Gate room commands with the gate's credential on stdin alone. */
final class FollowRooms {
    static final String TRANSPORT =
            "/usr/libexec/porelo/transports/porelo-message-transport-matrix";

    private FollowRooms() {}

    static boolean installed(FollowContext c) {
        return c.host.exists("/etc/systemd/system/porelo-matrix-gate.socket");
    }

    private static byte[] secrets(FollowContext c) throws IOException {
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
        return ProjectRecords.pythonJson(
                        fields("secrets", fields("PORELO_MATRIX_ADMIN_TOKEN", token)), false)
                .getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, @Nullable Object> room(
            FollowContext c, String verb, String project, List<String> persons) throws IOException {
        c.runtime.require("runuser", "env", "cat", TRANSPORT);
        final List<String> argv = new ArrayList<>(List.of(TRANSPORT, verb, "--project", project));
        if (verb.equals("keep-room")) {
            argv.addAll(List.of("--persons", String.join(",", persons)));
        }
        final byte[] input = secrets(c);
        final HostPrograms.Result result;
        try {
            result = c.as("porelo-matrix", argv, input);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        if (result.exitCode() != 0) {
            throw new HostRefusal(
                    result.exitCode() == 75 || result.exitCode() == 78 ? result.exitCode() : 69,
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
                    "porelo-host-person: "
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
