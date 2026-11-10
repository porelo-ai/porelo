package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Root's timer and SSH window follow the signed organization through one exclusive lock. */
final class HostFollow {
    private final FollowContext context;
    private final ProjectMigration.Central central;

    HostFollow(HostFiles host, PrintWriter err) {
        this(
                host,
                new ProjectFiles(),
                new ProjectRuntime(host),
                Clock.systemUTC(),
                err,
                (origin, token) ->
                        org.fuin.porelo.organization.gittree.HttpForgeAdmin.central(
                                java.net.URI.create(origin), token));
    }

    HostFollow(
            HostFiles host,
            ProjectFiles files,
            ProjectRuntime runtime,
            Clock clock,
            PrintWriter err,
            ProjectMigration.Central central) {
        context = new FollowContext(host, files, runtime, clock, err);
        this.central = central;
    }

    Map<String, @Nullable Object> run(
            String verb, Map<String, String> environment, InputStream input) throws IOException {
        root(verb, environment);
        final byte[] supplied = verb.equals("follow") ? new byte[0] : input.readNBytes(4097);
        byte[] token = new byte[0];
        try {
            final Path conf = context.path("/etc/porelo-host/forge.conf");
            context.host.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, false)) {
                if (verb.equals("renew-pin")) {
                    return renew(supplied);
                }
                final FollowContext bounded =
                        context.until(context.clock.instant().plusSeconds(300));
                if (verb.equals("refresh")) {
                    FollowContext.ascii(supplied, "token");
                    final String value = new String(supplied, StandardCharsets.US_ASCII).strip();
                    if (!value.isEmpty() && !value.matches("[!-~]+")) {
                        throw new HostRefusal(65, "the token is not one printable line");
                    }
                    token = value.getBytes(StandardCharsets.US_ASCII);
                    if (token.length > 0) {
                        if (added()) {
                            if (bounded.runtime.configuration("porelo-admin") == null
                                    && progress().values().stream()
                                            .noneMatch(
                                                    v ->
                                                            "waiting-for-token"
                                                                    .equals(
                                                                            object(v)
                                                                                    .get(
                                                                                            "state")))) {
                                throw new HostRefusal(
                                        65,
                                        "no project waits for a token; org refresh requires empty standard input");
                            }
                        } else {
                            addReadKey(bounded, token);
                        }
                    }
                } else {
                    token = new byte[0];
                }
                return follow(bounded, token);
            }
        } finally {
            Arrays.fill(token, (byte) 0);
            Arrays.fill(supplied, (byte) 0);
        }
    }

    static void root(String verb, Map<String, String> environment) {
        if (environment.containsKey("SUDO_USER")) {
            throw new HostRefusal(
                    65, "org " + verb + " is root's alone and never runs through sudo");
        }
    }

    boolean added() {
        return context.host.exists(FollowContext.ORG + "read/organization.added");
    }

    Map<String, @Nullable Object> progress() {
        final Path path = context.path(FollowContext.ORG + "host-projects.json");
        if (!context.host.check(path, 69, false, false, 0600)) {
            return fields();
        }
        final Object parsed =
                ProjectRecords.json(
                        context.host.read(path), "the host project progress does not read", 65);
        if (!(parsed instanceof Map<?, ?> entries)
                || entries.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !(e.getKey() instanceof String name)
                                                || !name.matches(HostRecords.PROJECT)
                                                || !(e.getValue() instanceof Map<?, ?> item)
                                                || !List.of(
                                                                "pending",
                                                                "carried",
                                                                "waiting-for-token",
                                                                "held",
                                                                "refused")
                                                        .contains(item.get("state")))) {
            throw new HostRefusal(65, "the host project progress is malformed");
        }
        return object(parsed);
    }

    private Map<String, @Nullable Object> renew(byte[] supplied) throws IOException {
        FollowContext.ascii(supplied, "pin");
        final String text = new String(supplied, StandardCharsets.US_ASCII);
        final List<String> rows = text.lines().toList();
        final java.util.Set<String> keys = new java.util.HashSet<>();
        if (rows.isEmpty()
                || rows.stream()
                        .anyMatch(
                                row -> {
                                    final String[] parts = row.split(" ", 2);
                                    return parts.length != 2
                                            || !FollowState.login(parts[0])
                                            || !HostRecords.validKey(parts[1])
                                            || !keys.add(parts[1]);
                                })) {
            throw new HostRefusal(
                    65, "the pin takes one line of a login and a key for each host admin key");
        }
        if (!context.files.exists(context.path(FollowContext.ORG + "pin"))) {
            throw new HostRefusal(65, "this host follows no organization");
        }
        context.write(FollowContext.ORG + "pin", String.join("\n", rows) + "\n", 0600);
        context.host.remove(FollowContext.ORG + "refused.json");
        return new HostOrganization(context.host).status();
    }

    private void pin() {
        final Path path = context.path(FollowContext.ORG + "pin");
        context.host.check(path, 69, true, false, 0600);
        final List<String> rows = context.host.read(path).lines().toList();
        if (rows.isEmpty()
                || rows.stream()
                        .anyMatch(
                                row -> {
                                    final String[] parts = row.split(" ", 2);
                                    return parts.length != 2
                                            || !FollowState.login(parts[0])
                                            || !HostRecords.validKey(parts[1]);
                                })) {
            throw new HostRefusal(65, "the organization pin holds no valid host admin keys");
        }
    }

    private void addReadKey(FollowContext c, byte[] token) throws IOException {
        final String unit = "porelo-host-org-read-key",
                credentials = "/run/credentials/" + unit + ".service/";
        final Map<String, Path> staged = new LinkedHashMap<>();
        staged.put("organization.yml", c.path(FollowContext.ORG + "organization.yml"));
        staged.put("read.pub", c.path(FollowContext.ORG + "read/organization.pub"));
        for (Path path : staged.values()) {
            c.host.check(path, 69, true, false, -1);
        }
        final var result =
                c.isolated(
                        unit,
                        staged,
                        List.of(
                                "org",
                                "add-read-key",
                                "--settings",
                                credentials + "organization.yml",
                                "--key",
                                credentials + "read.pub",
                                "--json"),
                        "porelo org add-read-key",
                        "porelo org add-read-key refused",
                        token,
                        true,
                        false,
                        null);
        if (!(result.get("added") instanceof Boolean)) {
            throw new HostRefusal(65, "porelo org add-read-key answered malformed");
        }
        c.write(FollowContext.ORG + "read/organization.added", "added\n", 0600);
    }

    private Map<String, @Nullable Object> follow(FollowContext c, byte[] token) throws IOException {
        c.runtime.require("systemd-run", "porelo", "setfacl");
        final String host = c.setting("/etc/porelo-host/forge.conf", "host");
        if (!host.matches("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?")) {
            throw new HostRefusal(69, "forge.conf names no valid host");
        }
        if (!added()) {
            return new HostOrganization(c.host).status();
        }
        pin();
        final var previous = new HostOrganization(c.host).applied();
        final String unit = "porelo-host-org-fetch",
                credential = "/run/credentials/" + unit + ".service/";
        final Map<String, Path> staged = new LinkedHashMap<>();
        for (String name : List.of("organization.yml", "organization", "known_hosts", "pin")) {
            final Path path =
                    c.path(FollowContext.ORG + (name.equals("organization") ? "read/" : "") + name);
            c.host.check(path, 69, true, false, 0600);
            staged.put(name, path);
        }
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "org",
                                "fetch",
                                "--host",
                                host,
                                "--settings",
                                credential + "organization.yml",
                                "--ssh-key",
                                credential + "organization",
                                "--known-hosts",
                                credential + "known_hosts",
                                "--pin",
                                credential + "pin"));
        if (previous != null) {
            arguments.addAll(List.of("--since", string(previous.get("commit"))));
        }
        arguments.add("--json");
        final var answer =
                c.isolated(
                        unit,
                        staged,
                        arguments,
                        "porelo org fetch",
                        "porelo org fetch refused the state",
                        new byte[0],
                        true,
                        true,
                        null);
        final FollowPeople people = new FollowPeople(c);
        final var records = people.records();
        final var applied =
                new FollowState(c)
                        .take(
                                answer,
                                host,
                                previous,
                                records,
                                people.accounts(),
                                people.minimumUid());
        final java.time.Instant end = c.deadline;
        final FollowProjects projects =
                new FollowProjects(
                        c,
                        (origin, tokenValue) ->
                                central.open(origin, tokenValue).until(c.clock, end),
                        this::progress);
        final var publications = publications(c, applied, projects);
        final var signers = signers(c, answer, applied);
        people.revokedBefore(applied, previous, records);
        writePin(c, applied);
        c.write(FollowContext.ORG + "applied.json", pretty(applied, 0) + "\n", 0600);
        final var arrivals = new ProjectArrivals(c.files, c.runtime, c.clock);
        new ProjectMeasurements(c.files, c.runtime, c.clock).revoke();
        arrivals.revoke();
        c.host.remove(FollowContext.ORG + "refused.json");
        publish(c, applied, publications, signers, people);
        final List<String> errors = new ArrayList<>();
        attempt(errors, () -> people.cards(applied, previous));
        attempt(errors, () -> people.memberships(applied));
        attempt(errors, () -> projects.reconcile(applied, previous, token));
        attempt(errors, () -> projects.members(applied, previous, people));
        attempt(errors, () -> projects.repositories(applied, token));
        if (token.length > 0 || projects.takenOff) {
            attempt(errors, () -> projects.members(applied, previous, people));
        }
        publish(c, applied, publications(c, applied, projects), signers, people);
        attempt(
                errors,
                () ->
                        arrivals.prepare(
                                array(applied.get("projects")).stream()
                                        .map(value -> string(object(value).get("project")))
                                        .toList()));
        attempt(errors, () -> projects.measure(applied));
        if (!errors.isEmpty()) {
            throw new HostRefusal(
                    69,
                    "the organization is taken; the next follow retries: "
                            + String.join("; ", errors));
        }
        return new HostOrganization(c.host).status();
    }

    @FunctionalInterface
    interface Action {
        void run() throws IOException;
    }

    static void attempt(List<String> errors, Action action) throws IOException {
        try {
            action.run();
        } catch (HostRefusal error) {
            errors.add(String.valueOf(error.getMessage()));
        } catch (IOException error) {
            errors.add("the host step failed to complete (OSError)");
        }
    }

    private Map<String, Map<String, @Nullable Object>> signers(
            FollowContext c,
            Map<String, @Nullable Object> answer,
            Map<String, @Nullable Object> applied)
            throws IOException {
        final Map<String, Map<String, @Nullable Object>> result = new LinkedHashMap<>();
        final Path directory = c.scratch("porelo-org-signers");
        try {
            final var state = new LinkedHashMap<>(answer);
            final var accepted = new ArrayList<Object>();
            for (Object value : array(applied.get("projects"))) {
                final String name = string(object(value).get("project"));
                for (Object original : array(answer.get("projects"))) {
                    if (original instanceof Map<?, ?> row && name.equals(row.get("project"))) {
                        accepted.add(original);
                    }
                }
            }
            state.put("projects", accepted);
            final Path path = directory.resolve("state");
            c.write(path, ProjectRecords.pythonJson(state, false), 0600);
            for (Object value : array(applied.get("projects"))) {
                final var project = object(value);
                final String name = string(project.get("project"));
                try {
                    final var keys =
                            c.isolated(
                                    "porelo-host-org-signers",
                                    Map.of("state", path),
                                    List.of(
                                            "org",
                                            "signers",
                                            "--state",
                                            "/run/credentials/porelo-host-org-signers.service/state",
                                            "--project",
                                            name,
                                            "--json"),
                                    "porelo org signers",
                                    "porelo org signers refused the project",
                                    new byte[0],
                                    false,
                                    false,
                                    null);
                    if (!name.equals(keys.get("project"))
                            || !string(answer.get("revision")).equals(keys.get("revision"))
                            || !(keys.get("signers") instanceof String)
                            || !(keys.get("certifiers") instanceof String)) {
                        throw new HostRefusal(
                                65,
                                "porelo org signers answered another project, revision or malformed keys");
                    }
                    result.put(name, keys);
                } catch (HostRefusal ex) {
                    project.put("why", ex.getMessage());
                    object(applied.get("project_refusals")).put(name, ex.getMessage());
                    // Replace the source entry, since checked JSON views are defensive maps.
                    replaceProject(applied, name, project);
                    final var refused = object(applied.get("project_refusals"));
                    refused.put(name, ex.getMessage());
                    applied.put("project_refusals", refused);
                    result.put(name, fields("signers", "", "certifiers", ""));
                }
            }
        } finally {
            c.files.removeTree(directory);
        }
        return result;
    }

    static void replaceProject(
            Map<String, @Nullable Object> applied,
            String name,
            Map<String, @Nullable Object> project) {
        final List<Object> projects = new ArrayList<>(array(applied.get("projects")));
        for (int i = 0; i < projects.size(); i++) {
            if (name.equals(object(projects.get(i)).get("project"))) {
                projects.set(i, project);
            }
        }
        applied.put("projects", projects);
    }

    private static Map<String, Map<String, @Nullable Object>> publications(
            FollowContext c, Map<String, @Nullable Object> applied, FollowProjects projects) {
        final Map<String, Map<String, @Nullable Object>> files = new LinkedHashMap<>();
        for (Object value : array(applied.get("projects"))) {
            final var p = object(value);
            files.put(string(p.get("project")), projects.publication(p, applied));
        }
        return files;
    }

    private static void writePin(FollowContext c, Map<String, @Nullable Object> applied)
            throws IOException {
        final StringBuilder pin = new StringBuilder(), root = new StringBuilder();
        final java.util.Set<String> held = new java.util.HashSet<>();
        for (Object value : array(applied.get("admins"))) {
            final var a = object(value);
            pin.append(a.get("login")).append(' ').append(a.get("key")).append('\n');
            held.add(string(a.get("key")));
            root.append(a.get("key")).append('\n');
        }
        for (Object value : array(applied.get("organization_admins"))) {
            final var a = object(value);
            if (!held.contains(a.get("key"))) {
                pin.append(a.get("login")).append(' ').append(a.get("key")).append('\n');
            }
        }
        if (!c.host.read(FollowContext.ORG + "pin").equals(pin.toString())) {
            c.write(FollowContext.ORG + "pin", pin.toString(), 0600);
        }
        final String path = "/etc/ssh/authorized_keys/root";
        if (!c.host.exists(path) || !c.host.read(path).equals(root.toString())) {
            c.write(path, root.toString(), 0644);
        }
    }

    private static void publish(
            FollowContext c,
            Map<String, @Nullable Object> applied,
            Map<String, Map<String, @Nullable Object>> files,
            Map<String, Map<String, @Nullable Object>> signers,
            FollowPeople people)
            throws IOException {
        final Path directory = c.path(FollowContext.ORG + "projects");
        c.directories(directory, 0711);
        Files.setAttribute(ProjectFiles.parent(directory), "unix:mode", 0711);
        Files.setAttribute(directory, "unix:mode", 0711);
        prune(
                c,
                directory,
                files.keySet(),
                HostRecords.PROJECT,
                "the organization projects directory holds an unknown file",
                true);
        final boolean forge = c.runtime.configuration(admin(applied)) != null;
        final Map<String, List<String>> named = new TreeMap<>();
        for (Object value : array(applied.get("projects"))) {
            final var p = object(value);
            final String name = string(p.get("project"));
            final List<String> readers = people.readers(p);
            c.publish(
                    directory.resolve(name + ".json"),
                    ProjectRecords.pythonJson(files.get(name), false) + "\n",
                    readers);
            final Path signerDirectory = c.path("/etc/porelo-host/signers");
            c.directories(signerDirectory, 0755);
            Files.setAttribute(signerDirectory, "unix:mode", 0755);
            c.write(
                    signerDirectory.resolve(name + ".certifiers"),
                    string(
                            java.util.Objects.requireNonNull(
                                            signers.get(name),
                                            "Every accepted project has a signer answer")
                                    .get("certifiers")),
                    0644);
            if (forge) {
                c.write(
                        signerDirectory.resolve(name + ".allowed"),
                        string(
                                java.util.Objects.requireNonNull(
                                                signers.get(name),
                                                "Every accepted project has a signer answer")
                                        .get("signers")),
                        0644);
            }
            for (String login : people.readerLogins(p)) {
                named.computeIfAbsent(login, k -> new ArrayList<>()).add(name);
            }
        }
        final Path persons = c.path(FollowContext.ORG + "persons");
        c.directories(persons, 0711);
        Files.setAttribute(persons, "unix:mode", 0711);
        prune(
                c,
                persons,
                named.keySet(),
                HostRecords.USER,
                "the organization persons directory holds an unknown file",
                false);
        final Map<String, Long> accounts = people.accounts();
        for (var entry : named.entrySet()) {
            c.publish(
                    persons.resolve(entry.getKey() + ".json"),
                    ProjectRecords.pythonJson(
                                    fields("projects", entry.getValue().stream().sorted().toList()),
                                    false)
                            + "\n",
                    List.of("user:" + accounts.get(entry.getKey()) + ":r--"));
        }
    }

    static void prune(
            FollowContext c,
            Path directory,
            java.util.Set<String> kept,
            String pattern,
            String error,
            boolean signers)
            throws IOException {
        for (Path path : c.files.list(directory)) {
            c.files.trusted(path, false);
            final String name = path.getFileName().toString();
            if (ProjectFiles.isStaged(name) || name.endsWith(".json.new")) {
                c.files.delete(path);
                continue;
            }
            if (!name.endsWith(".json") || !name.substring(0, name.length() - 5).matches(pattern)) {
                throw new HostRefusal(65, error);
            }
            final String project = name.substring(0, name.length() - 5);
            if (!kept.contains(project)) {
                c.files.delete(path);
                if (signers) {
                    for (String suffix : List.of("allowed", "certifiers")) {
                        final Path signer =
                                c.path("/etc/porelo-host/signers/" + project + "." + suffix);
                        if (c.files.exists(signer)) {
                            c.files.trusted(signer, false);
                            c.files.delete(signer);
                        }
                    }
                }
            }
        }
    }

    static String admin(Map<String, @Nullable Object> applied) {
        return string(object(array(applied.get("admins")).getFirst()).get("login"));
    }

    static String pretty(@Nullable Object value, int depth) {
        final String indent = " ".repeat(depth + 1), close = " ".repeat(depth);
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            final List<String> rows = new ArrayList<>();
            map.forEach(
                    (key, item) ->
                            rows.add(
                                    indent
                                            + ProjectRecords.pythonJson(key, false)
                                            + ": "
                                            + pretty(item, depth + 1)));
            return "{\n" + String.join(",\n", rows) + "\n" + close + "}";
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            return "[\n"
                    + list.stream()
                            .map(item -> indent + pretty(item, depth + 1))
                            .collect(java.util.stream.Collectors.joining(",\n"))
                    + "\n"
                    + close
                    + "]";
        }
        return ProjectRecords.pythonJson(value, false);
    }
}
