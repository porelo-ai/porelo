package org.fuin.porelo.host;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.UUID;

/** Person tokens rotate independently of a task's gate and a concurrent command's lend. */
final class HostTokens {
    private static final String LEND = "porelo-host-lend-";
    private static final String SCOPES =
            "write:repository,write:issue,write:organization,read:user";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                    .withResolverStyle(ResolverStyle.STRICT);
    private final HostFiles files;
    private final ProjectRuntime runtime;
    private final Clock clock;

    HostTokens(HostFiles files, ProjectRuntime runtime, Clock clock) {
        this.files = files;
        this.runtime = runtime;
        this.clock = clock;
    }

    String issue(HostPerson person, boolean lend) {
        final HostRecords records = new HostRecords(files);
        final String login = person.login();
        records.admitted(login);
        if (records.revoked(login)) {
            throw new HostRefusal(65, "the host's organization names " + login + " revoked");
        }
        if (runtime.configuration(login) == null) {
            throw new HostRefusal(69, "the host runs no forge, so there is no forge token to make");
        }
        final Instant now = clock.instant();
        try (var session = runtime.session(login)) {
            session.dropMatching(
                    login,
                    name -> lend ? due(name, now) : !name.startsWith(LEND) || due(name, now));
            final String name =
                    (lend
                                    ? LEND + TIME.format(now.atOffset(ZoneOffset.UTC)) + "-"
                                    : "porelo-host-forge-person-")
                            + UUID.randomUUID().toString().replace("-", "");
            return session.token(login, SCOPES, name);
        }
    }

    private static boolean due(String name, Instant now) {
        if (!name.startsWith(LEND)) {
            return false;
        }
        try {
            final String time = name.substring(LEND.length()).split("-", 2)[0];
            final Instant made = LocalDateTime.parse(time, TIME).toInstant(ZoneOffset.UTC);
            return !made.isAfter(now.minus(Duration.ofDays(1)));
        } catch (DateTimeParseException ex) {
            return true;
        }
    }
}
