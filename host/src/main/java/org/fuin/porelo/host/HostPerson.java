package org.fuin.porelo.host;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The person a verb acts for, authenticated by sudo and the system account database. */
record HostPerson(String login, long uid) {
    @FunctionalInterface
    interface Accounts {
        String passwd(String login) throws IOException;
    }

    static HostPerson from(Map<String, String> environment) {
        return from(
                environment,
                login -> {
                    final HostPrograms.Result result =
                            new HostPrograms().run(List.of("getent", "passwd", login));
                    if (result.exitCode() == 2) {
                        return "";
                    }
                    if (result.exitCode() != 0) {
                        throw new IOException("Account database lookup failed");
                    }
                    return result.output();
                });
    }

    static HostPerson from(Map<String, String> environment, Accounts accounts) {
        final String login = environment.get("SUDO_USER");
        if (login == null || login.isEmpty()) {
            throw refused("SUDO_USER is required; invoke through the installed sudo rule");
        }
        if (login.equals("root")) {
            throw refused("SUDO_USER must name a person other than root");
        }
        if (!login.matches("^[a-z_][a-z0-9_-]{0,31}$")) {
            throw refused("SUDO_USER is not a valid login name");
        }
        final long sudoUid = uid(environment.getOrDefault("SUDO_UID", ""));
        final String passwd;
        try {
            passwd = accounts.passwd(login);
        } catch (IOException ex) {
            throw new HostRefusal(
                    69,
                    "porelo-host: cannot query the system account database; check getent and NSS");
        }
        final List<String> lines = passwd.lines().toList();
        if (lines.isEmpty()) {
            throw refused("SUDO_USER is unknown to the system account database");
        }
        if (lines.size() != 1) {
            throw refused("system account database must answer one matching person");
        }
        final String[] fields = lines.getFirst().split(":", -1);
        if (fields.length != 7 || !fields[0].equals(login)) {
            throw refused("system account database must answer the requested person");
        }
        final long accountUid = uid(fields[2]);
        if (accountUid == 0 || accountUid != sudoUid) {
            throw refused("SUDO_UID must agree with the person's non-root system uid");
        }
        return new HostPerson(login, accountUid);
    }

    private static long uid(String value) {
        if (value.matches("[0-9]{1,10}")) {
            final long uid = Long.parseLong(value);
            if (uid < 4294967295L) {
                return uid;
            }
        }
        throw refused("SUDO_UID and the system account uid must be valid numeric uids");
    }

    private static HostRefusal refused(String reason) {
        return new HostRefusal(77, "porelo-host: " + reason);
    }
}
