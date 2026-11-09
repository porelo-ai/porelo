package org.fuin.porelo.host;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finishes pending local and forge locks, retaining intent until every owed step succeeds. */
final class HostRevocations {
    private final HostFiles files;
    private final ProjectRuntime runtime;

    HostRevocations(HostFiles files, ProjectRuntime runtime) {
        this.files = files;
        this.runtime = runtime;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private static void attempt(List<HostRefusal> errors, Action action) {
        try {
            action.run();
        } catch (HostRefusal error) {
            errors.add(error);
        } catch (IOException ex) {
            errors.add(
                    new HostRefusal(
                            73, "cannot read or change the host's files during revocation"));
        }
    }

    List<String> retry(Map<String, HostRecords.Person> records) {
        final List<String> errors = new ArrayList<>();
        for (var entry : records.entrySet()) {
            if (entry.getValue().pending()) {
                try {
                    lock(entry.getKey(), entry.getValue(), records);
                } catch (HostRefusal error) {
                    errors.add(entry.getKey() + ": " + error.getMessage());
                }
            }
        }
        return errors;
    }

    private void local(List<String> arguments, int... accepted) {
        runtime.require(arguments.getFirst());
        runtime.command(arguments, accepted);
    }

    private void lock(
            String user, HostRecords.Person record, Map<String, HostRecords.Person> records) {
        final var account = runtime.find(user);
        final List<HostRefusal> errors = new ArrayList<>();
        attempt(errors, () -> files.writePerson(user, record.locked(), true));
        attempt(errors, () -> files.remove("/etc/ssh/authorized_keys/" + user));
        attempt(errors, () -> local(List.of("gpasswd", "--delete", user, "porelo-people"), 0, 3));
        attempt(
                errors,
                () -> {
                    files.remove("/etc/tmpfiles.d/porelo-person-" + user + ".conf");
                    files.removeDelivery(user, runtime);
                });
        if (account != null) {
            attempt(errors, () -> local(List.of("loginctl", "terminate-user", user), 0, 1));
            attempt(
                    errors,
                    () ->
                            local(
                                    List.of(
                                            "systemctl",
                                            "stop",
                                            "user@" + account.uid() + ".service")));
            attempt(errors, () -> local(List.of("usermod", "--lock", "--expiredate", "1", user)));
        }
        final boolean localRemoved = errors.isEmpty();
        records.put(
                user,
                new HostRecords.Person(record.members(), localRemoved, true, record.lockedOn()));
        attempt(errors, () -> files.writePerson(user, localRemoved, true));
        attempt(errors, () -> runtime.lock(user, () -> files.removeDelivery(user, runtime)));
        if (account != null && localRemoved) {
            attempt(
                    errors,
                    () -> {
                        if (!runtime.available("porelo")) {
                            return;
                        }
                        runtime.require("runuser", "env", "porelo", "usermod", "systemctl");
                        try {
                            runtime.command(List.of("usermod", "--expiredate", "", user));
                            runtime.command(
                                    List.of(
                                            "systemctl",
                                            "start",
                                            "user@" + account.uid() + ".service"));
                            runtime.command(
                                    runtime.switched(
                                            runtime.required(user),
                                            List.of("porelo", "task", "stop", "--all")));
                        } finally {
                            final int failures = errors.size();
                            attempt(
                                    errors,
                                    () -> local(List.of("usermod", "--expiredate", "1", user)));
                            attempt(
                                    errors,
                                    () ->
                                            local(
                                                    List.of(
                                                            "systemctl",
                                                            "stop",
                                                            "user@" + account.uid() + ".service")));
                            if (errors.size() != failures) {
                                records.put(
                                        user,
                                        new HostRecords.Person(
                                                record.members(), false, true, record.lockedOn()));
                                attempt(errors, () -> files.writePerson(user, false, true));
                            }
                        }
                    });
        }
        if (!errors.isEmpty()) {
            throw new HostRefusal(
                    errors.getFirst().exitCode(),
                    String.join(
                            "; ",
                            errors.stream()
                                    .map(error -> String.valueOf(error.getMessage()))
                                    .toList()));
        }
        try {
            files.writePerson(user, true, false);
        } catch (IOException ex) {
            throw new HostRefusal(73, "cannot read or change the host's files");
        }
        records.put(user, new HostRecords.Person(record.members(), true, false, record.lockedOn()));
    }
}
