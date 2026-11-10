package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Root's explicit person maintenance shares the follow's lock and account operations. */
final class HostPeople {
    private final FollowContext c;

    HostPeople(FollowContext context) {
        c = context;
    }

    Map<String, @Nullable Object> purge(String login) throws IOException {
        if (!login.matches(HostRecords.USER)) {
            throw new HostRefusal(64, "the person name must match [a-z][a-z0-9-]{0,31}");
        }
        final var conf = c.path("/etc/porelo-host/forge.conf");
        c.host.check(conf, 69, true, false, -1);
        try (var lock = HostConfigurationLock.acquire(conf, false)) {
            new FollowPeople(c).purge(login);
            return fields("person", login, "purged", true);
        }
    }

    Map<String, @Nullable Object> restoreRuntime() throws IOException {
        final var conf = c.path("/etc/porelo-host/forge.conf");
        c.host.check(conf, 69, true, false, -1);
        try (var lock = HostConfigurationLock.acquire(conf, false)) {
            final var records = new HostRecords(c.host).read();
            final var people = new FollowPeople(c);
            int changed = 0;
            for (var entry : records.entrySet()) {
                if (entry.getValue().admitted()) {
                    c.runtime.require("systemctl");
                    if (people.runtime(entry.getKey())) {
                        changed++;
                    }
                    c.command(
                            List.of(
                                    "systemctl",
                                    "restart",
                                    "user@"
                                            + c.runtime.required(entry.getKey()).uid()
                                            + ".service"));
                }
            }
            return fields("changed", changed);
        }
    }

    static void unlisted(FollowContext c, String login) {
        final var applied = new HostOrganization(c.host).applied();
        if (applied == null
                || !(applied.get("projects") instanceof List<?> projects)
                || !(applied.get("admins") instanceof List<?> admins)
                || !(applied.getOrDefault("project_refusals", Map.of())
                        instanceof Map<?, ?> refusals)
                || !refusals.isEmpty()) {
            throw incomplete();
        }
        for (Object project : projects) {
            if (!(project instanceof Map<?, ?> row)
                    || !(row.get("project") instanceof String name)
                    || !name.matches(HostRecords.PROJECT)
                    || !(row.get("members") instanceof List<?> members)) {
                throw incomplete();
            }
            absent(members, login);
        }
        absent(admins, login);
    }

    private static void absent(List<?> members, String login) {
        for (Object value : members) {
            if (!(value instanceof Map<?, ?> member) || !FollowState.login(member.get("login"))) {
                throw incomplete();
            }
            if (login.equals(member.get("login"))) {
                throw new HostRefusal(65, "the person is still listed in the host's organization");
            }
        }
    }

    private static HostRefusal incomplete() {
        return new HostRefusal(
                65,
                "the applied organization has no complete membership inventory; run org follow successfully before purging");
    }
}
