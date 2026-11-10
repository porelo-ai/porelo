package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Reconciles the host's person admissions and grace period from the accepted organization. */
final class FollowPeople {
    private static final String GECOS = ",,,,umask=0022";
    private final FollowContext c;
    private final ProjectRecords records;

    FollowPeople(FollowContext context) {
        c = context;
        records = new ProjectRecords(c.files);
    }

    Map<String, ProjectRecords.Person> records() throws IOException {
        final Map<String, ProjectRecords.Person> result = new TreeMap<>();
        // The shared record reader judges all records, including their dates, before any access
        // changes.
        for (String login : new HostRecords(c.host).read().keySet()) {
            result.put(login, records.personRecord(login, records.person(login)));
        }
        return result;
    }

    Map<String, Long> accounts() throws IOException {
        final var result = c.run(List.of("getent", "passwd"), new byte[0], Duration.ofSeconds(10));
        if (result.exitCode() != 0) {
            throw new HostRefusal(69, "the host's accounts could not be read");
        }
        final Map<String, Long> accounts = new TreeMap<>();
        for (String line : result.output().lines().toList()) {
            final String[] fields = line.split(":", -1);
            if (fields.length != 7
                    || !fields[2].matches("[0-9]{1,10}")
                    || Long.parseLong(fields[2]) >= 4294967295L
                    || accounts.putIfAbsent(fields[0], Long.valueOf(fields[2])) != null) {
                throw new HostRefusal(69, "the host's accounts could not be read");
            }
        }
        return accounts;
    }

    long minimumUid() {
        if (!c.host.exists("/etc/login.defs")) {
            return 1000;
        }
        for (String line : c.host.read("/etc/login.defs").lines().toList()) {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length == 2
                    && fields[0].equals("UID_MIN")
                    && fields[1].matches("[0-9]{1,10}")) {
                return Long.parseLong(fields[1]);
            }
        }
        return 1000;
    }

    List<String> readerLogins(Map<String, @Nullable Object> project) throws IOException {
        final var persons = records();
        final var accounts = accounts();
        final List<String> result = new ArrayList<>();
        for (Object value : array(project.get("members"))) {
            final var member = object(value);
            final String login = string(member.get("login"));
            final var person = persons.get(login);
            if (Boolean.FALSE.equals(member.get("locked"))
                    && accounts.containsKey(login)
                    && person != null
                    && !person.locked()
                    && !person.pending()) {
                result.add(login);
            }
        }
        return result;
    }

    List<String> readers(Map<String, @Nullable Object> project) throws IOException {
        final var accounts = accounts();
        final List<String> result = new ArrayList<>();
        for (String login : readerLogins(project)) {
            result.add("user:" + accounts.get(login) + ":r--");
        }
        return result;
    }

    private static @Nullable String field(ProjectRecords.Person person, String field) {
        return person.card().stream()
                .filter(line -> line.startsWith(field + "="))
                .map(line -> line.substring(field.length() + 1))
                .findFirst()
                .orElse(null);
    }

    private void record(
            String login,
            List<String> memberships,
            boolean locked,
            String key,
            @Nullable Object expires,
            @Nullable String reason,
            @Nullable String day,
            boolean pending)
            throws IOException {
        final List<String> card =
                new ArrayList<>(
                        List.of("ssh=" + key, "expires=" + (expires == null ? "" : expires)));
        if (reason != null && !reason.isEmpty()) {
            card.add("reason=" + reason);
        }
        if (day != null) {
            card.add("locked_on=" + day);
        }
        c.write(
                records.person(login),
                new ProjectRecords.Person(memberships, locked, pending, card).format(memberships),
                0600);
    }

    void revokedBefore(
            Map<String, @Nullable Object> applied,
            @Nullable Map<String, @Nullable Object> previous,
            Map<String, ProjectRecords.Person> people)
            throws IOException {
        final Set<String> gone = new HashSet<>();
        for (Object v : array(applied.get("revoked"))) {
            gone.add(string(v));
        }
        if (previous != null) {
            for (Object v : array(previous.get("revoked"))) {
                gone.add(string(v));
            }
        }
        final Set<String> keys = new java.util.LinkedHashSet<>();
        for (var state : previous == null ? List.of(applied) : List.of(previous, applied)) {
            for (Object value : array(state.get("persons"))) {
                final var person = object(value);
                if ((state == applied ? array(applied.get("revoked")) : gone)
                        .contains(person.get("login"))) {
                    keys.add(string(person.get("ssh")));
                }
            }
        }
        people.forEach(
                (login, person) -> {
                    if (gone.contains(login)) {
                        keys.add(FollowState.keyOf(person));
                    }
                });
        if (!keys.isEmpty()) {
            krl(keys);
        }
    }

    private void krl(Set<String> keys) throws IOException {
        final Path target = c.path("/etc/ssh/porelo-revoked.krl");
        c.host.check(target, 73, false, false, -1);
        c.directories(ProjectFiles.parent(target), 0755);
        final Path directory =
                Files.createTempDirectory(ProjectFiles.parent(target), ".porelo-krl-");
        try {
            final Path source = directory.resolve("keys"),
                    output = directory.resolve("revoked.krl");
            c.write(source, String.join("\n", keys) + (keys.isEmpty() ? "" : "\n"), 0600);
            final List<String> argv = new ArrayList<>(List.of("ssh-keygen", "-q", "-k"));
            if (c.files.exists(target)) {
                Files.copy(target, output);
                argv.add("-u");
            }
            argv.addAll(List.of("-f", output.toString(), source.toString()));
            c.command(argv);
            final byte[] header;
            try (var stream = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) {
                header = stream.readNBytes(8);
            }
            if (!java.util.Arrays.equals(
                    header, "SSHKRL\n\0".getBytes(StandardCharsets.US_ASCII))) {
                throw new HostRefusal(69, "ssh-keygen produced no valid KRL");
            }
            Files.setAttribute(output, "unix:uid", c.files.owner, LinkOption.NOFOLLOW_LINKS);
            Files.setAttribute(output, "unix:mode", 0644, LinkOption.NOFOLLOW_LINKS);
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            output,
                            java.nio.file.StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            c.files.replace(output, target);
        } finally {
            c.files.removeTree(directory);
        }
    }

    private boolean revoked(String key) throws IOException {
        final Path krl = c.path("/etc/ssh/porelo-revoked.krl");
        c.host.check(krl, 69, true, false, -1);
        final Path directory = c.scratch("porelo-key");
        try {
            final Path file = directory.resolve("key.pub");
            c.write(file, key + "\n", 0600);
            final var result =
                    c.run(
                            List.of("ssh-keygen", "-Q", "-f", krl.toString(), file.toString()),
                            new byte[0],
                            Duration.ofSeconds(60));
            if (result.exitCode() == 0) {
                return false;
            }
            if (result.exitCode() != 1 || !result.output().contains("REVOKED")) {
                throw new HostRefusal(69, "OpenSSH could not query the host KRL");
            }
            return true;
        } finally {
            c.files.removeTree(directory);
        }
    }

    void cards(
            Map<String, @Nullable Object> applied, @Nullable Map<String, @Nullable Object> previous)
            throws IOException {
        final var states = new HostRecords(c.host).read();
        final List<String> errors =
                new ArrayList<>(new HostRevocations(c.host, c.runtime).retry(states));
        revokedBefore(applied, previous, records());
        // Even an organization without a revoked key needs an initialized KRL for the current-key
        // questions.
        if (!c.files.exists(c.path("/etc/ssh/porelo-revoked.krl"))) {
            krl(Set.of());
        }
        final var people = records();
        for (Object value : array(applied.get("persons"))) {
            final var person = object(value);
            final String login = string(person.get("login"));
            final var record = people.get(login);
            if (record == null) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(person.get("revoked"))
                        || revoked(string(person.get("ssh")))) {
                    lock(login, record, "key", field(record, "locked_on"));
                } else if (!record.pending() && !record.locked()) {
                    authorized(login, string(person.get("ssh")), person.get("expires"));
                    record(
                            login,
                            record.members(),
                            false,
                            string(person.get("ssh")),
                            person.get("expires"),
                            field(record, "reason"),
                            field(record, "locked_on"),
                            false);
                }
            } catch (HostRefusal ex) {
                errors.add(login + ": " + ex.getMessage());
            }
        }
        groups();
        if (!errors.isEmpty()) {
            throw new HostRefusal(
                    69,
                    "the organization is applied; the next follow retries host revocation: "
                            + String.join("; ", errors));
        }
    }

    private void lock(
            String login, ProjectRecords.Person person, String reason, @Nullable String day)
            throws IOException {
        record(
                login,
                person.members(),
                person.locked(),
                FollowState.keyOf(person),
                field(person, "expires"),
                reason,
                day,
                person.pending());
        final var states = new HostRecords(c.host).read();
        new HostRevocations(c.host, c.runtime)
                .lock(
                        login,
                        java.util.Objects.requireNonNull(
                                states.get(login), "The person was recorded before locking"),
                        states);
    }

    void memberships(Map<String, @Nullable Object> applied) throws IOException {
        final var persons = new TreeMap<String, Map<String, @Nullable Object>>();
        final var memberships = new TreeMap<String, List<String>>();
        final Set<String> listed = new HashSet<>();
        for (Object value : array(applied.get("persons"))) {
            final var person = object(value);
            final String login = string(person.get("login"));
            persons.put(login, person);
            memberships.put(login, new ArrayList<>());
        }
        for (Object value : array(applied.get("projects"))) {
            final var project = object(value);
            for (Object m : array(project.get("members"))) {
                final var member = object(m);
                final String login = string(member.get("login"));
                listed.add(login);
                if (Boolean.FALSE.equals(member.get("locked"))) {
                    java.util.Objects.requireNonNull(
                                    memberships.get(login),
                                    "Validated project members name fetched persons")
                            .add(
                                    project.get("project")
                                            + " "
                                            + ("admin".equals(member.get("role"))
                                                    ? "project-admin"
                                                    : "developer"));
                }
            }
        }
        for (Object value : array(applied.get("admins"))) {
            final String login = string(object(value).get("login"));
            java.util.Objects.requireNonNull(
                            memberships.get(login), "Validated admins name fetched persons")
                    .add("host-admin");
            listed.add(login);
        }
        final var records = records();
        final Set<String> names = new TreeSet<>(records.keySet());
        names.addAll(persons.keySet());
        final List<String> errors = new ArrayList<>();
        final Set<String> purged = new HashSet<>(new HostRecords(c.host).purged());
        for (String login : names) {
            final var person = persons.get(login);
            final var record = records.get(login);
            final List<String> desired = memberships.getOrDefault(login, List.of());
            final Instant expiry =
                    person == null ? null : FollowState.expiry(person.get("expires"));
            try {
                final boolean permitted =
                        person != null
                                && !purged.contains(login)
                                && !Boolean.TRUE.equals(person.get("revoked"))
                                && !revoked(string(person.get("ssh")))
                                && (expiry == null || expiry.isAfter(c.clock.instant()));
                if (!permitted || desired.isEmpty()) {
                    if (record == null) {
                        continue;
                    }
                    final String day =
                            listed.contains(login)
                                    ? null
                                    : field(record, "locked_on") == null
                                            ? LocalDate.now(c.clock).toString()
                                            : field(record, "locked_on");
                    if (!record.locked() || record.pending()) {
                        lock(login, record, permitted ? "membership" : "key", day);
                    }
                    record(
                            login,
                            List.of(),
                            true,
                            FollowState.keyOf(record),
                            field(record, "expires"),
                            permitted ? "membership" : "key",
                            day,
                            false);
                    if (day != null
                            && !LocalDate.now(c.clock).isBefore(LocalDate.parse(day).plusDays(7))) {
                        purge(login);
                    }
                } else {
                    admit(
                            login,
                            record,
                            desired,
                            java.util.Objects.requireNonNull(
                                    person, "Permitted membership has a verified person"));
                }
            } catch (HostRefusal ex) {
                errors.add(login + ": " + ex.getMessage());
            }
        }
        groups();
        if (!errors.isEmpty()) {
            throw new HostRefusal(69, String.join("; ", errors));
        }
    }

    private void authorized(String login, String key, @Nullable Object expiry) throws IOException {
        final Instant end = FollowState.expiry(expiry);
        final String option =
                end == null
                        ? ""
                        : "expiry-time=\""
                                + DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
                                        .withZone(ZoneOffset.UTC)
                                        .format(end)
                                + "\" ";
        c.write("/etc/ssh/authorized_keys/" + login, option + key + "\n", 0644);
    }

    private Set<String> groupNames() throws IOException {
        final String text = c.command(List.of("getent", "group"));
        final Set<String> names = new HashSet<>();
        for (String line : text.lines().toList()) {
            final String[] row = line.split(":", -1);
            if (row.length != 4) {
                throw new HostRefusal(69, "the host's groups could not be read");
            }
            names.add(row[0]);
        }
        return names;
    }

    private void join(String login, Set<String> groups) throws IOException {
        if (groups.add("porelo-people")) {
            c.command(List.of("groupadd", "--system", "porelo-people"));
        }
        final List<String> names = new ArrayList<>();
        for (String group : List.of("kvm", "libvirt", "porelo-people")) {
            if (groups.contains(group)) {
                names.add(group);
            }
        }
        c.command(List.of("usermod", "--append", "--groups", String.join(",", names), login));
    }

    private void subids(String login) throws IOException {
        for (String kind : List.of("subuid", "subgid")) {
            final String interval = range(login, kind);
            if (interval != null) {
                c.command(
                        List.of(
                                "usermod",
                                kind.equals("subuid") ? "--add-subuids" : "--add-subgids",
                                interval,
                                login));
            }
        }
    }

    private @Nullable String range(String login, String kind) {

        long start = 100000;
        boolean held = false;
        for (String line : c.host.read("/etc/" + kind).lines().toList()) {
            final String[] row = line.split(":", -1);
            if (row.length != 3
                    || !row[0].matches("[a-zA-Z0-9_-]+")
                    || !row[1].matches("[0-9]{1,10}")
                    || !row[2].matches("[0-9]{1,10}")) {
                throw new HostRefusal(69, kind + " holds a malformed subordinate ID range");
            }
            final long first = Long.parseLong(row[1]), count = Long.parseLong(row[2]);
            if (count < 1 || first + count > 4294967295L) {
                throw new HostRefusal(69, kind + " holds a malformed subordinate ID range");
            }
            start = Math.max(start, first + count);
            held |= row[0].equals(login);
        }
        if (held) {
            return null;
        }
        if (start + 65536 > 4294967295L) {
            throw new HostRefusal(69, "there is no free subordinate ID range");
        }
        return start + "-" + (start + 65535);
    }

    private void admit(
            String login,
            ProjectRecords.@Nullable Person record,
            List<String> desired,
            Map<String, @Nullable Object> person)
            throws IOException {
        if (record != null && record.pending()) {
            throw new HostRefusal(69, "a lock is unfinished, so the person is not admitted again");
        }
        if (record != null && record.locked() && c.runtime.find(login) == null) {
            return;
        }
        final String key = string(person.get("ssh"));
        final Object expiry = person.get("expires");
        final Set<String> groups = groupNames();
        if (record == null) {
            if (groups.contains(login)) {
                throw new HostRefusal(65, "an organization login belongs to an existing group");
            }
            for (String path :
                    List.of(
                            "/etc/ssh/authorized_keys/" + login,
                            "/etc/porelo-host/people/" + login + ".conf",
                            "/etc/tmpfiles.d/porelo-person-" + login + ".conf")) {
                c.host.check(c.path(path), 73, false, false, -1);
            }
            c.runtime.configuration(login);
            range(login, "subuid");
            range(login, "subgid");
            c.runtime.require("useradd", "usermod", "loginctl", "systemctl");
            if (!groups.contains("porelo-people")) {
                c.runtime.require("groupadd");
            }
            c.command(
                    List.of(
                            "useradd",
                            "--create-home",
                            "--user-group",
                            "--shell",
                            "/bin/bash",
                            "--comment",
                            GECOS,
                            "--key",
                            "SUB_UID_COUNT=0",
                            "--key",
                            "SUB_GID_COUNT=0",
                            login));
            try {
                join(login, groups);
                c.command(List.of("loginctl", "enable-linger", login));
                subids(login);
                runtime(login);
                authorized(login, key, expiry);
                forgeAdmit(login);
                record(login, desired, false, key, expiry, null, null, false);
            } catch (HostRefusal | IOException ex) {
                record(login, List.of(), false, key, expiry, null, null, true);
                try {
                    lock(
                            login,
                            java.util.Objects.requireNonNull(
                                    records().get(login),
                                    "Admission failure recorded the created account"),
                            "membership",
                            null);
                } catch (HostRefusal failure) {
                    ex.addSuppressed(failure);
                }
                throw new HostRefusal(
                        ex instanceof HostRefusal refusal ? refusal.exitCode() : 73,
                        (ex instanceof HostRefusal
                                        ? ex.getMessage()
                                        : "cannot read or change the host's files")
                                + "; the account is locked, and purge "
                                + login
                                + " removes it");
            }
            daemon(login);
            return;
        }
        if (!record.locked()
                && record.members()
                        .equals(
                                desired.stream()
                                        .sorted(
                                                Comparator.comparing(
                                                        v ->
                                                                v.equals("host-admin")
                                                                        ? "0"
                                                                        : "1" + v))
                                        .toList())
                && key.equals(FollowState.keyOf(record))
                && java.util.Objects.equals(
                        expiry == null ? "" : expiry, field(record, "expires"))) {
            return;
        }
        if (record.locked()) {
            forgeDrop(login, false);
            join(login, groups);
            c.command(List.of("loginctl", "enable-linger", login));
            subids(login);
            c.command(List.of("usermod", "--expiredate", "", login));
            forgeAdmit(login);
            forgeUnlock(login);
        }
        join(login, groups);
        runtime(login);
        authorized(login, key, expiry);
        record(login, desired, false, key, expiry, null, null, false);
        if (record.locked()) {
            c.command(List.of("usermod", "--comment", GECOS, login));
            daemon(login);
        }
    }

    private void runtime(String login) throws IOException {
        final var account = c.runtime.required(login);
        final Path directory = c.path("/run/porelo-host/people/" + login);
        c.directories(ProjectFiles.parent(directory), 0755);
        if (!c.files.exists(directory)) {
            Files.createDirectory(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            final var passwd =
                    c.run(List.of("getent", "passwd", login), new byte[0], Duration.ofSeconds(10));
            final String[] fields = passwd.output().strip().split(":", -1);
            if (passwd.exitCode() != 0 || fields.length != 7 || !fields[3].matches("[0-9]{1,10}")) {
                throw new HostRefusal(69, "the person's primary group could not be read");
            }
            c.files.owner(directory, (int) account.uid(), Integer.parseInt(fields[3]));
        } else {
            final var attributes = c.files.attributes(directory);
            if (!Long.valueOf(account.uid())
                            .equals(
                                    ((Number)
                                                    java.util.Objects.requireNonNull(
                                                            attributes.get("uid"),
                                                            "Unix attributes include uid"))
                                            .longValue())
                    || (((int)
                                            java.util.Objects.requireNonNull(
                                                    attributes.get("mode"),
                                                    "Unix attributes include mode"))
                                    & 0177777)
                            != 0040700) {
                throw new HostRefusal(
                        73,
                        "the forge token delivery directory is not a private directory of the person's");
            }
        }
        final Path link = directory.resolve("porelod.sock");
        final String daemon = "/run/user/" + account.uid() + "/porelo/porelod.sock";
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createSymbolicLink(link, Path.of(daemon));
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                /* The person keeps their entry. */
            }
        }
        final String content =
                "d /run/porelo-host/people/"
                        + login
                        + " 0700 "
                        + login
                        + " "
                        + login
                        + " -\nL /run/porelo-host/people/"
                        + login
                        + "/porelod.sock - - - - "
                        + daemon
                        + "\n";
        final String rule = "/etc/tmpfiles.d/porelo-person-" + login + ".conf";
        if (!c.host.exists(rule) || !c.host.read(rule).equals(content)) {
            c.write(rule, content, 0644);
        }
    }

    private void daemon(String login) throws IOException {
        try {
            c.command(
                    List.of(
                            "systemctl",
                            "start",
                            "user@" + c.runtime.required(login).uid() + ".service"));
            final var setup = c.as(login, List.of("porelo", "setup"), new byte[0]);
            if (setup.exitCode() != 0) {
                throw new HostRefusal(69, "porelo setup failed");
            }
            final var active =
                    c.as(
                            login,
                            List.of("systemctl", "--user", "is-active", "--quiet", "porelod"),
                            new byte[0]);
            if (active.exitCode() != 0) {
                throw new HostRefusal(69, "porelod is not active");
            }
        } catch (HostRefusal ex) {
            c.err.println(
                    "porelo-host-person: porelod of "
                            + login
                            + " is not running ("
                            + ex.getMessage()
                            + "); 'porelo setup' as "
                            + login
                            + " starts it");
        }
    }

    Set<String> forgeUsers(String account) throws IOException {
        final var result = c.runtime.admin(account, "list");
        final List<String> lines = result.output().lines().toList();
        if (result.exitCode() != 0
                || lines.isEmpty()
                || !lines.getFirst().matches("ID\\s+Username\\s+Email\\b.*")) {
            throw new HostRefusal(69, "Forgejo answered no valid user list");
        }
        final Set<String> users = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            final String[] row = line.strip().split("\\s+");
            if (row.length < 3 || !row[0].matches("[0-9]+")) {
                throw new HostRefusal(69, "Forgejo answered no valid user list");
            }
            users.add(row[1]);
        }
        return users;
    }

    private void forgeAdmit(String login) throws IOException {
        final var config = c.runtime.configuration(login);
        if (config == null) {
            return;
        }
        final Set<String> users = forgeUsers(config.account());
        final String domain = c.setting("/etc/porelo-host/forge.conf", "name");
        if (!domain.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw new HostRefusal(69, "forge.conf names no valid name");
        }
        for (String identity : config.identities().subList(0, 2)) {
            if (!users.contains(identity)
                    && c.runtime
                                    .admin(
                                            config.account(),
                                            "create",
                                            "--username",
                                            identity,
                                            "--email",
                                            identity + "@" + domain,
                                            "--random-password",
                                            "--must-change-password=false")
                                    .exitCode()
                            != 0) {
                throw new HostRefusal(69, "Forgejo could not create the person's identity");
            }
        }
    }

    private void forgeUnlock(String login) throws IOException {
        final var config = c.runtime.configuration(login);
        if (config == null) {
            return;
        }
        final Set<String> users = forgeUsers(config.account());
        try (var session = c.runtime.session(login)) {
            for (String identity : config.identities()) {
                if (users.contains(identity)) {
                    session.forge()
                            .requestUser(
                                    "PATCH",
                                    identity,
                                    200,
                                    Map.of(
                                            "login_name",
                                            identity,
                                            "source_id",
                                            0,
                                            "prohibit_login",
                                            false));
                }
            }
        } catch (org.fuin.porelo.organization.gittree.HttpForgeAdmin.Refusal ex) {
            throw new HostRefusal(
                    69,
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "The forge's refusal carries a safe diagnostic"));
        }
    }

    private void forgeDrop(String login, boolean purge) throws IOException {
        final var config = c.runtime.configuration(login);
        if (config == null) {
            return;
        }
        final Set<String> users = forgeUsers(config.account());
        try (var session = c.runtime.session(login)) {
            for (String identity : config.identities()) {
                if (users.contains(identity)) {
                    session.drop(identity, null);
                    if (purge) {
                        session.forge().requestUser("DELETE", identity, 204, null);
                    }
                }
            }
        } catch (org.fuin.porelo.organization.gittree.HttpForgeAdmin.Refusal ex) {
            throw new HostRefusal(
                    69,
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "The forge's refusal carries a safe diagnostic"));
        }
    }

    private void purge(String login) throws IOException {
        final var record =
                java.util.Objects.requireNonNull(
                        records().get(login), "Only a recorded person can be purged");
        lock(login, record, "membership", field(record, "locked_on"));
        c.host.writePerson(login, true, true);
        FollowRooms.expel(c, login);
        forgeDrop(login, true);
        c.host.remove("/etc/tmpfiles.d/porelo-person-" + login + ".conf");
        c.host.removeDelivery(login, c.runtime);
        final var account = c.runtime.find(login);
        if (account != null) {
            boolean deleted = false;
            try {
                if (c.runtime.available("porelo")) {
                    c.host.writePerson(login, false, true);
                    try {
                        c.command(List.of("usermod", "--expiredate", "", login));
                        c.command(
                                List.of(
                                        "systemctl",
                                        "start",
                                        "user@" + account.uid() + ".service"));
                        final var stopped =
                                c.as(
                                        login,
                                        List.of("porelo", "task", "stop", "--all"),
                                        new byte[0]);
                        if (stopped.exitCode() != 0) {
                            throw new HostRefusal(69, "porelo task stop failed");
                        }
                        final var clear =
                                c.as(
                                        login,
                                        List.of(
                                                "porelo", "vault", "clear", "--yes", "--force",
                                                "--json"),
                                        new byte[0]);
                        if (clear.exitCode() != 0
                                && !(clear.exitCode() == 1 && onlyTransports(clear.output()))) {
                            throw new HostRefusal(
                                    69,
                                    "porelo vault clear kept more than the accounts a transport keeps elsewhere");
                        }
                    } finally {
                        finishCleanup(login, account.uid());
                    }
                }
                c.command(List.of("loginctl", "disable-linger", login));
                final var ended =
                        c.run(
                                List.of("loginctl", "terminate-user", login),
                                new byte[0],
                                Duration.ofSeconds(60));
                if (ended.exitCode() != 0 && ended.exitCode() != 1) {
                    throw new HostRefusal(69, "loginctl failed");
                }
                c.command(List.of("systemctl", "stop", "user@" + account.uid() + ".service"));
                c.command(List.of("userdel", "--remove", login));
                deleted = true;
            } finally {
                if (!deleted) {
                    finishCleanup(login, account.uid());
                }
            }
        }
        for (String kind : List.of("subuid", "subgid")) {
            final String text = c.host.read("/etc/" + kind),
                    remaining =
                            java.util.Arrays.stream(text.split("(?<=\n)", -1))
                                    .filter(line -> !line.startsWith(login + ":"))
                                    .collect(java.util.stream.Collectors.joining());
            if (!remaining.equals(text)) {
                c.write(
                        "/etc/" + kind,
                        remaining,
                        ((Number)
                                                Files.getAttribute(
                                                        c.path("/etc/" + kind),
                                                        "unix:mode",
                                                        LinkOption.NOFOLLOW_LINKS))
                                        .intValue()
                                & 07777);
            }
        }
        final Set<String> purged = new TreeSet<>(new HostRecords(c.host).purged());
        purged.add(login);
        c.write("/etc/porelo-host/people/purged", String.join("\n", purged) + "\n", 0600);
        c.files.delete(records.person(login));
    }

    private void finishCleanup(String login, long uid) throws IOException {
        try {
            c.command(List.of("usermod", "--expiredate", "1", login));
        } finally {
            c.command(List.of("systemctl", "stop", "user@" + uid + ".service"));
        }
        c.host.writePerson(login, true, true);
    }

    private static boolean onlyTransports(String json) {
        final Object parsed =
                ProjectRecords.json(json, "porelo vault clear answered no valid JSON", 69);
        if (!(parsed instanceof Map<?, ?> map)
                || !Boolean.FALSE.equals(map.get("refused"))
                || !(map.get("items") instanceof List<?> items)) {
            return false;
        }
        final List<?> kept =
                items.stream()
                        .filter(
                                value ->
                                        value instanceof Map<?, ?> row
                                                && "NOT_REMOVED".equals(row.get("status")))
                        .toList();
        return !kept.isEmpty()
                && kept.stream()
                        .allMatch(
                                value ->
                                        value instanceof Map<?, ?> row
                                                && (java.util.Arrays.asList(
                                                                        "transport", "conversation")
                                                                .contains(row.get("kind"))
                                                        || row.get("transport")
                                                                        instanceof String transport
                                                                && !transport.isEmpty()));
    }

    private void groups() throws IOException {
        final var persons = records();
        final Set<String> desired = new TreeSet<>();
        persons.forEach(
                (login, p) -> {
                    if (!p.locked() && !p.pending() && !p.members().isEmpty()) {
                        desired.add(login);
                    }
                });
        final String groups = c.command(List.of("getent", "group"));
        final Set<String> names = new HashSet<>(), current = new TreeSet<>();
        for (String line : groups.lines().toList()) {
            final String[] row = line.split(":", -1);
            if (row.length != 4) {
                throw new HostRefusal(69, "the host's groups could not be read");
            }
            names.add(row[0]);
            if (row[0].equals("porelo-people") && !row[3].isEmpty()) {
                current.addAll(List.of(row[3].split(",")));
            }
        }
        for (String login : current) {
            if (!desired.contains(login)) {
                if (!login.matches("[a-z_][a-z0-9_-]{0,31}")) {
                    throw new HostRefusal(65, "porelo-people holds an invalid login");
                }
                c.command(List.of("gpasswd", "--delete", login, "porelo-people"));
            }
        }
        for (String login : desired) {
            if (!current.contains(login)) {
                join(login, names);
            }
            renewManager(login, groups);
        }
    }

    private void renewManager(String login, String groups) throws IOException {
        final String uid = "" + c.runtime.required(login).uid();
        final var shown =
                c.run(
                        List.of(
                                "systemctl",
                                "show",
                                "--property=MainPID",
                                "--value",
                                "user@" + uid + ".service"),
                        new byte[0],
                        Duration.ofSeconds(60));
        final String pid = shown.output().strip();
        if (shown.exitCode() != 0 || !pid.matches("[1-9][0-9]*")) {
            return;
        }
        final String gid =
                groups.lines()
                        .filter(line -> line.startsWith("porelo-people:"))
                        .map(line -> line.split(":")[2])
                        .findFirst()
                        .orElse("");
        final Path status = c.path("/proc/" + pid + "/status");
        final String text;
        try (var stream = Files.newInputStream(status)) {
            final byte[] bytes = stream.readNBytes(65537);
            if (bytes.length > 65536) {
                return;
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return;
        }
        final var held = text.lines().filter(line -> line.startsWith("Groups:")).findFirst();
        if (held.isEmpty() || List.of(held.get().split("\\s+")).contains(gid)) {
            return;
        }
        try {
            final var conmon =
                    c.run(
                            List.of("pgrep", "-u", login, "-x", "conmon"),
                            new byte[0],
                            Duration.ofSeconds(60));
            if (conmon.exitCode() == 0) {
                c.err.println(
                        "porelo-host-person: porelod of "
                                + login
                                + " lacks porelo-people until their tasks end; the follow after that restarts it");
                return;
            }
            if (conmon.exitCode() != 1) {
                throw new HostRefusal(69, "pgrep failed");
            }
            c.command(List.of("systemctl", "restart", "user@" + uid + ".service"));
            daemon(login);
        } catch (HostRefusal ex) {
            c.err.println(
                    "porelo-host-person: porelod of "
                            + login
                            + " lacks porelo-people and was not restarted ("
                            + ex.getMessage()
                            + ")");
        }
    }
}
