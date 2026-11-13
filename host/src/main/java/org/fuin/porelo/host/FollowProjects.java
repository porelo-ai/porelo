package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.jspecify.annotations.Nullable;

/**
 * Reconciles project progress, signed file copies and forge policy using the existing project
 * operations.
 */
final class FollowProjects {
    static final Pattern CENTRAL =
            Pattern.compile(
                    "[!-~]{0,1024}[:/](?<repository>[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}?)(?:\\.git)?/?");
    static final String NO_CENTRAL =
            "no central address, so this host cannot take it; name one in the project file for a task here";
    private final FollowContext c;
    private final ProjectMigration.Central central;
    private final Supplier<Map<String, @Nullable Object>> progress;
    boolean takenOff;

    FollowProjects(
            FollowContext context,
            ProjectMigration.Central central,
            Supplier<Map<String, @Nullable Object>> progress) {
        c = context;
        this.central = central;
        this.progress = progress;
    }

    static boolean address(@Nullable Object value) {
        return value instanceof String text && CENTRAL.matcher(text).matches();
    }

    static String repository(String address) {
        final var matcher = CENTRAL.matcher(address);
        if (!matcher.matches()) {
            throw new HostRefusal(65, "the project names no usable central address");
        }
        return matcher.group("repository");
    }

    static String takenAs(String name, Map<String, @Nullable Object> row) {
        return row.get("central") instanceof String central ? repository(central) : name;
    }

    private void save(Map<String, @Nullable Object> entries) throws IOException {
        c.write(
                FollowContext.ORG + "host-projects.json",
                HostFollow.pretty(entries, 0) + "\n",
                0600);
    }

    Map<String, @Nullable Object> publication(
            Map<String, @Nullable Object> project, Map<String, @Nullable Object> applied) {
        Object fetch = null, builds = null;
        final String name = string(project.get("project"));
        if (c.runtime.configuration(HostFollow.admin(applied)) != null
                && project.get("address") instanceof String central) {
            final var services = optionalObject(object(applied.get("host_card")).get("services"));
            final var forge = optionalObject(services.get("forge"));
            final Object port = forge.get("git_port");
            if (port instanceof Number n
                    && integral(port)
                    && n.longValue() >= 1
                    && n.longValue() <= 65535
                    && (!(port instanceof java.math.BigInteger big) || big.bitLength() <= 16)) {
                fetch =
                        "http://127.0.0.1:"
                                + port
                                + "/"
                                + name
                                + "/"
                                + repository(central)
                                + ".git";
                try {
                    final String domain = c.setting("/etc/porelo-host/forge.conf", "name");
                    if (!domain.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")) {
                        throw new HostRefusal(69, "forge.conf names no valid name");
                    }
                    builds =
                            fields(
                                    "forge",
                                    "forgejo",
                                    "api",
                                    "http://127.0.0.1:" + port + "/api/v1",
                                    "credential",
                                    domain + ".person",
                                    "logs",
                                    "failure");
                } catch (HostRefusal ex) {
                    c.err.println("porelo-host: " + name + ": no builds named: " + ex.getMessage());
                }
            } else {
                project.put("why", "the host card names no forge git port");
                c.err.println("porelo-host: " + name + ": " + project.get("why"));
                HostFollow.replaceProject(applied, name, project);
            }
        }
        final var answer =
                fields(
                        "project",
                        name,
                        "host",
                        object(applied.get("host_card")).get("host"),
                        "revision",
                        applied.get("revision"),
                        "address",
                        project.get("address"),
                        "fetch_from",
                        fetch,
                        "signing_keys",
                        project.get("signing_keys"));
        if (builds != null) {
            answer.put("builds", builds);
        }
        final var item =
                FollowRooms.installed(c) ? optionalObject(progress.get().get(name)) : fields();
        answer.put("homeserver", item.get("homeserver"));
        answer.put("room", item.get("room"));
        return answer;
    }

    private Map<String, @Nullable Object> projectCommand(
            String verb,
            List<String> args,
            @Nullable Map<String, @Nullable Object> supplied,
            FollowContext context)
            throws IOException {
        final StringWriter stdout = new StringWriter(), stderr = new StringWriter();
        final HostProject helper =
                new HostProject(c.files, context.runtime, central, (conf, descriptor) -> () -> {});
        final byte[] input =
                supplied == null
                        ? new byte[0]
                        : ProjectRecords.pythonJson(supplied, false)
                                .getBytes(StandardCharsets.UTF_8);
        final int code;
        try {
            code =
                    helper.run(
                            verb,
                            args,
                            new ByteArrayInputStream(input),
                            new PrintWriter(stdout),
                            new PrintWriter(stderr),
                            true);
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
        if (code == 0) {
            if (verb.equals("remove") || verb.equals("take-off")) {
                left(
                        verb.equals("remove")
                                ? args.getFirst()
                                : args.getFirst()
                                        + "/"
                                        + java.util.Objects.requireNonNull(
                                                        supplied,
                                                        "Take-off requires its repository request")
                                                .get("repository"),
                        stdout.toString());
            }
            return stdout.toString().isBlank()
                    ? fields()
                    : object(
                            ProjectRecords.json(
                                    stdout.toString(),
                                    "the project helper answered no valid JSON",
                                    69));
        }
        if (verb.equals("remove") && code == 65) {
            final Object parsed =
                    ProjectRecords.json(
                            stdout.toString(),
                            "project removal was refused or could not verify every commit",
                            69);
            if (!(parsed instanceof Map<?, ?> held)
                    || !args.getFirst().equals(held.get("project"))
                    || !(held.get("unpushed") instanceof Map<?, ?> commits)) {
                throw new HostRefusal(
                        69, "project removal was refused or could not verify every commit");
            }
            final List<String> rows = new ArrayList<>();
            for (var entry : commits.entrySet()) {
                if (!(entry.getKey() instanceof String name)
                        || !name.matches("[a-z0-9._-]{1,100}")
                        || !(entry.getValue() instanceof List<?> values)
                        || values.stream()
                                .anyMatch(
                                        v ->
                                                !(v instanceof String text)
                                                        || !text.matches(HostRecords.COMMIT))) {
                    throw new HostRefusal(
                            69, "project removal was refused or could not verify every commit");
                }
                rows.add(
                        name
                                + ": "
                                + String.join(
                                        ", ", values.stream().map(HostJson::string).toList()));
            }
            if (!rows.isEmpty()) {
                throw new HostRefusal(65, "unpushed commits: " + String.join("; ", rows));
            }
            final String words = helperWords(stderr.toString(), supplied);
            throw new HostRefusal(
                    65,
                    words == null
                            ? "project removal was refused without a verified commit comparison"
                            : words);
        }
        final String words = helperWords(stderr.toString(), supplied);
        if (words == null) {
            throw new HostRefusal(69, "the project helper " + verb + " exited " + code);
        }
        c.err.println("porelo-host: " + args.getFirst() + ": " + words + " (exit " + code + ")");
        throw new ProjectRefusal(words, code);
    }

    private static final class ProjectRefusal extends RuntimeException {
        final String words;
        final int told;

        ProjectRefusal(String words, int told) {
            super(words);
            this.words = words;
            this.told = told;
        }

        String shown() {
            return words + " (exit " + told + ")";
        }
    }

    static @Nullable String helperWords(
            String stderr, @Nullable Map<String, @Nullable Object> supplied) {
        final List<String> lines =
                stderr.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getLast().startsWith("porelo-host: ")) {
            return null;
        }
        final String raw = FollowContext.printable(lines.getLast());
        if (supplied != null
                && supplied.get("token") instanceof String token
                && !token.isEmpty()
                && FollowContext.visible(lines.getLast()).contains(token)) {
            return null;
        }
        final String words = raw.substring("porelo-host: ".length());
        final String bounded = words.substring(0, Math.min(words.length(), 300)).strip();
        return bounded.isEmpty() ? null : bounded;
    }

    private void left(String project, String json) {
        try {
            final var answer = object(ProjectRecords.json(json, "", 69));
            for (Object value : array(answer.getOrDefault("left_at_central", List.of()))) {
                final var row = object(value);
                c.err.println(
                        "porelo-host: "
                                + project
                                + ": taken off the host; its read-only deploy key \""
                                + FollowContext.printable(string(row.get("title")))
                                + "\" stays at "
                                + FollowContext.printable(string(row.get("address")))
                                + " until that repository's owner removes it");
            }
        } catch (HostRefusal | IllegalArgumentException ex) {
            c.err.println(
                    "porelo-host: "
                            + project
                            + ": taken off the host; what it left at the central forge does not read");
        }
    }

    private List<String> projectAdmin(Map<String, @Nullable Object> project) throws IOException {
        final var people = new FollowPeople(c).records();
        final List<?> keys = array(project.get("signing_keys"));
        for (Object value : array(project.get("members"))) {
            final var member = object(value);
            final String login = string(member.get("login"));
            final var record = people.get(login);
            if (!"admin".equals(member.get("role"))
                    || Boolean.TRUE.equals(member.get("locked"))
                    || record == null
                    || record.locked()
                    || record.pending()) {
                continue;
            }
            for (int i = keys.size() - 1; i >= 0; i--) {
                final var key = object(keys.get(i));
                final Instant from = FollowState.expiry(key.get("valid_from")),
                        until = FollowState.expiry(key.get("valid_until"));
                if (login.equals(key.get("login"))
                        && "admin".equals(key.get("role"))
                        && FollowState.keyOf(record).equals(key.get("key"))
                        && key.get("revoked_since") == null
                        && (from == null || !from.isAfter(c.clock.instant()))
                        && (until == null || c.clock.instant().isBefore(until))) {
                    return List.of(login, string(key.get("key")));
                }
            }
        }
        throw new HostRefusal(65, "the project has no unlocked admin with a current signing key");
    }

    private void forgeProject(
            Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> item,
            boolean forge,
            Set<String> physical,
            byte[] token)
            throws IOException {
        final String name = string(project.get("project"));
        if (!forge) {
            item.remove("migrate_refused");
            item.put("state", "carried");
            return;
        }
        if (!physical.contains(name)) {
            final var admin = projectAdmin(project);
            projectCommand(
                    "add", List.of(name, admin.getFirst(), "--signed-by", admin.get(1)), null, c);
            for (String field :
                    List.of(
                            "migrated",
                            "migrate_refused",
                            "taken",
                            "repositories_why",
                            "advanced",
                            "advance_refused",
                            "named",
                            "named_refused")) {
                item.remove(field);
            }
        }
        if (Boolean.TRUE.equals(item.get("migrated"))) {
            item.put("state", "carried");
            return;
        }
        item.put("state", "waiting-for-token");
        if (token.length == 0) {
            return;
        }
        try {
            if (project.get("address") == null) {
                throw new HostRefusal(65, "the project names no usable central address");
            }
            projectCommand(
                    "migrate",
                    List.of(name),
                    fields(
                            "address",
                            project.get("address"),
                            "token",
                            new String(token, StandardCharsets.US_ASCII)),
                    c);
        } catch (HostRefusal ex) {
            item.put("migrate_refused", ex.getMessage());
            throw ex;
        } catch (ProjectRefusal ex) {
            item.put("migrate_refused", ex.shown());
            throw ex;
        }
        item.remove("migrate_refused");
        item.put("migrated", true);
        item.put("state", "carried");
    }

    void reconcile(
            Map<String, @Nullable Object> applied,
            @Nullable Map<String, @Nullable Object> previous,
            byte[] token)
            throws IOException {
        final var entries = progress.get();
        final Map<String, Map<String, @Nullable Object>> desired = new TreeMap<>();
        for (Object value : array(applied.get("projects"))) {
            final var project = object(value);
            desired.put(string(project.get("project")), project);
        }
        final boolean forge = c.runtime.configuration(HostFollow.admin(applied)) != null;
        final Set<String> physical = new TreeSet<>();
        final Path directory = c.path("/etc/porelo-host/projects");
        if (forge && c.files.exists(directory)) {
            c.files.directory(directory, false, false);
            for (Path path : c.files.list(directory)) {
                if (path.getFileName().toString().endsWith(".conf")) {
                    c.host.check(path, 69, true, false, 0600);
                    final String name = path.getFileName().toString().replaceFirst("\\.conf$", "");
                    if (!name.matches(HostRecords.PROJECT)) {
                        throw new HostRefusal(65, "the project records name an invalid project");
                    }
                    physical.add(name);
                }
            }
        }
        final List<Object> all = new ArrayList<>();
        if (previous != null) {
            all.addAll(array(previous.get("projects")));
        }
        all.addAll(array(applied.get("projects")));
        for (Object value : all) {
            final var project = object(value);
            entries.putIfAbsent(
                    string(project.get("project")),
                    fields(
                            "state",
                            "pending",
                            "since",
                            project.get("since"),
                            "gate",
                            FollowRooms.installed(c)));
        }
        for (String name : physical) {
            entries.putIfAbsent(name, fields("state", "pending", "gate", FollowRooms.installed(c)));
        }
        save(entries);
        final Set<String> names = new TreeSet<>(entries.keySet());
        names.addAll(desired.keySet());
        for (String name : names) {
            if (object(applied.get("project_refusals")).containsKey(name)) {
                continue;
            }
            final var project = desired.get(name);
            final var item = object(entries.get(name));
            try {
                if (project != null) {
                    RuntimeException forgeError = null;
                    try {
                        forgeProject(project, item, forge, physical, token);
                    } catch (HostRefusal | ProjectRefusal ex) {
                        forgeError = ex;
                    }
                    item.put("why", why(item, project));
                    item.put("gate", FollowRooms.installed(c));
                    if (Boolean.TRUE.equals(item.get("gate"))
                            && !Boolean.TRUE.equals(item.get("room_refused"))) {
                        keepRoom(applied, project, item);
                        item.put("why", why(item, project));
                    }
                    if (forgeError != null) {
                        throw forgeError;
                    }
                } else {
                    item.put("state", "pending");
                    if (physical.contains(name)) {
                        if (Boolean.TRUE.equals(item.get("gate"))
                                && FollowRooms.installed(c)
                                && !Boolean.TRUE.equals(item.get("room_refused"))) {
                            FollowRooms.room(c, "keep-room", name, List.of());
                        }
                        projectCommand("remove", List.of(name), null, c);
                    }
                    if (Boolean.TRUE.equals(item.get("gate"))
                            && FollowRooms.installed(c)
                            && !Boolean.TRUE.equals(item.get("room_refused"))) {
                        FollowRooms.room(c, "drop-room", name, List.of());
                    }
                    if (!Boolean.TRUE.equals(item.get("room_refused"))) {
                        entries.remove(name);
                    }
                }
            } catch (HostRefusal ex) {
                if (project == null && ex.exitCode() == 65) {
                    item.put("state", "held");
                } else if (ex.exitCode() == 78) {
                    item.put("room_refused", true);
                }
                item.put("why", ex.getMessage());
            } catch (ProjectRefusal ex) {
                item.put("why", ex.shown());
            }
            if (Boolean.TRUE.equals(item.get("room_refused"))) {
                item.put("why", "the gate refused the room (exit 78); left alone");
            }
            if (entries.containsKey(name)) {
                entries.put(name, item);
            }
            save(entries);
        }
    }

    private void keepRoom(
            Map<String, @Nullable Object> applied,
            Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> item)
            throws IOException {
        final String name = string(project.get("project"));
        final var persons = new FollowPeople(c).readerLogins(project).stream().sorted().toList();
        final var names = FollowRooms.names(applied, persons);
        final String fingerprint;
        try {
            fingerprint =
                    java.util.HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    ProjectRecords.pythonJson(names.values(), false)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
        final List<String> refusals = new ArrayList<>();
        if (!names.refused().isEmpty()) {
            refusals.add(
                    "room names refused for "
                            + String.join(", ", names.refused())
                            + ": each must be text of 1 to 64 characters without control or format characters, '@' or ':'");
        }
        final boolean refused = fingerprint.equals(item.get("room_names_refused"));
        if (!refused) {
            item.remove("room_names_refused");
        }
        try {
            item.putAll(
                    FollowRooms.room(
                            c, "keep-room", name, persons, refused ? Map.of() : names.values()));
        } catch (HostRefusal ex) {
            if (ex.exitCode() != 64) {
                throw ex;
            }
            // A display refusal must never spare a member whose access was removed.
            item.put("room_names_refused", fingerprint);
            item.putAll(FollowRooms.room(c, "keep-room", name, persons));
        } finally {
            if (item.containsKey("room_names_refused")) {
                refusals.add(
                        "the transport refused the room names (exit 64); membership is retried without names until they change");
            }
            final String why = refusals.isEmpty() ? null : String.join("; ", refusals);
            item.put("room_names_why", why);
            if (why != null) {
                c.err.println("porelo-host: " + name + ": " + why);
            }
        }
    }

    static @Nullable String why(
            Map<String, @Nullable Object> item, Map<String, @Nullable Object> project) {
        final Map<String, List<String>> grouped = new LinkedHashMap<>();
        new TreeMap<>(optionalObject(item.get("repositories_why")))
                .forEach(
                        (name, value) -> {
                            if (!NO_CENTRAL.equals(value)) {
                                grouped.computeIfAbsent(string(value), key -> new ArrayList<>())
                                        .add(name);
                            }
                        });
        final List<String> words = new ArrayList<>();
        if ("waiting-for-token".equals(item.get("state"))
                && !Boolean.TRUE.equals(item.get("migrated"))) {
            words.add(
                    "The host needs the person's forge token once per new repository to add its read key and copy it; the host keeps no token.");
        }
        for (Object value :
                java.util.Arrays.asList(
                        item.get("migrate_refused"),
                        project.get("why"),
                        item.get("file_refused"),
                        item.get("advance_refused"),
                        item.get("named_refused"),
                        item.get("room_names_why"))) {
            if (value instanceof String text && !text.isEmpty()) {
                words.add(text);
            }
        }
        grouped.forEach((reason, names) -> words.add(String.join(", ", names) + ": " + reason));
        return words.isEmpty() ? null : String.join("; ", words);
    }

    static String fileSigners(Map<String, @Nullable Object> project) {
        final StringBuilder lines = new StringBuilder();
        for (Object value : array(project.get("signing_keys"))) {
            final var key = object(value);
            if (!"admin".equals(key.get("role")) || key.get("revoked_since") != null) {
                continue;
            }
            final List<String> options = new ArrayList<>();
            for (String field : List.of("valid_from", "valid_until")) {
                if (key.get(field) instanceof String text && !text.isEmpty()) {
                    options.add(
                            (field.equals("valid_from") ? "valid-after" : "valid-before")
                                    + "=\""
                                    + text.replace("-", "").substring(0, 8)
                                    + "\"");
                }
            }
            lines.append(key.get("login")).append(' ');
            if (!options.isEmpty()) {
                lines.append(String.join(",", options)).append(' ');
            }
            lines.append(key.get("key")).append('\n');
        }
        return lines.toString();
    }

    static Map<String, @Nullable Object> values(Map<String, @Nullable Object> answer, String name) {
        if (!name.equals(answer.get("project"))) {
            throw new HostRefusal(65, "porelo project verify answered another project");
        }
        final Object review = answer.getOrDefault("project_review", "required");
        final Object repositories = answer.getOrDefault("repositories", fields());
        if (!java.util.Arrays.asList("required", "optional").contains(review)
                || !(repositories instanceof Map<?, ?> rows)) {
            throw new HostRefusal(65, "porelo project verify answered a malformed review");
        }
        final Map<String, @Nullable Object> kept = fields();
        for (var entry : rows.entrySet()) {
            if (!(entry.getKey() instanceof String repository)
                    || !repository.matches("[a-z0-9._-]{1,100}")
                    || !(entry.getValue() instanceof Map<?, ?> row)
                    || !java.util.Arrays.asList("required", "optional").contains(row.get("review"))
                    || row.get("central") != null && !address(row.get("central"))) {
                throw new HostRefusal(
                        65,
                        "porelo project verify answered a malformed repository '"
                                + FollowContext.printable(String.valueOf(entry.getKey()))
                                        .substring(
                                                0,
                                                Math.min(
                                                        String.valueOf(entry.getKey()).length(),
                                                        100))
                                + "'");
            }
            kept.put(
                    repository, fields("review", row.get("review"), "central", row.get("central")));
        }
        return fields("review", review, "repositories", kept);
    }

    static boolean noTaskInOwn(
            Map<String, @Nullable Object> project, Map<String, @Nullable Object> file) {
        final String own = repository(string(project.get("address"))).toLowerCase(Locale.ROOT);
        final var rows = optionalObject(file.get("repositories"));
        return !rows.isEmpty()
                && rows.entrySet().stream()
                        .noneMatch(
                                entry -> {
                                    final var row = object(entry.getValue());
                                    return row.get("central") != null
                                            && takenAs(entry.getKey(), row).equalsIgnoreCase(own);
                                });
    }

    private void readFile(
            Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> item,
            String signers,
            String account,
            Instant deadline)
            throws IOException {
        if (!java.util.Objects.equals(
                optionalObject(item.get("file")).get("address"), project.get("address"))) {
            item.remove("file");
        }
        if (!c.clock.instant().isBefore(deadline)) {
            item.put("file_refused", "the project file: this follow had no time left to read it");
            return;
        }
        final String signed;
        try {
            signed =
                    java.util.HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(signers.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
        final var known = optionalObject(item.get("file"));
        Path removable = null;
        final FollowContext bounded = c.until(deadline);
        try {
            final var copied =
                    new ProjectCopies(c.files, bounded.runtime)
                            .copy(
                                    string(project.get("project")),
                                    string(project.get("address")),
                                    signed.equals(known.get("signers"))
                                                    && java.util.Objects.equals(
                                                            known.get("commit"),
                                                            item.get("advanced"))
                                            ? string(known.get("commit"))
                                            : "-");
            final String commit = string(copied.get("commit"));
            if (!commit.matches(HostRecords.COMMIT)) {
                throw new HostRefusal(
                        69, "the project's main could not be read from the central forge");
            }
            if (copied.get("copy") != null) {
                final String path = string(copied.get("copy"));
                if (!path.matches(
                        Pattern.quote(new ProjectCopies(c.files, c.runtime).directory().toString())
                                + "/[0-9a-f]{32}/central\\.git")) {
                    throw new HostRefusal(
                            69, "the project helper named no valid copy of the project");
                }
                removable = ProjectFiles.parent(Path.of(path));
                final Path directory;
                try {
                    directory = c.scratch("porelo-host-file");
                } catch (IOException ex) {
                    throw ProjectFiles.placingRefusal("making the follow's scratch directory", ex);
                }
                try {
                    final Path staged = directory.resolve("signers");
                    ProjectFiles.placing(
                            "staging the project's signers", () -> c.write(staged, signers, 0600));
                    final var verified =
                            bounded.isolated(
                                    "porelo-host-project-file",
                                    Map.of("signers", staged),
                                    List.of(
                                            "project",
                                            "verify",
                                            "--repository",
                                            path,
                                            "--signers",
                                            "/run/credentials/porelo-host-project-file.service/signers",
                                            "--json"),
                                    "porelo project verify",
                                    "porelo project verify refused the project file",
                                    new byte[0],
                                    false,
                                    false,
                                    account);
                    final var file =
                            fields(
                                    "commit",
                                    commit,
                                    "signers",
                                    signed,
                                    "address",
                                    project.get("address"));
                    file.putAll(values(verified, string(project.get("project"))));
                    item.put("file", file);
                    advance(project, item, path, bounded);
                } finally {
                    c.files.removeTree(directory);
                }
            }
            item.remove("file_refused");
        } catch (HostRefusal ex) {
            final String words = "the project file: " + ex.getMessage();
            item.put("file_refused", words.substring(0, Math.min(words.length(), 512)));
        } catch (IOException | IllegalArgumentException ex) {
            // The host's own placing refuses with its own words above; what is left is the read.
            item.put(
                    "file_refused",
                    "the project file: its main could not be read from the central forge");
        } finally {
            if (removable != null) {
                c.files.removeTree(removable);
            }
        }
    }

    private void advance(
            Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> item,
            String copied,
            FollowContext bounded)
            throws IOException {
        final String name = string(project.get("project")),
                repository = repository(string(project.get("address"))),
                commit = string(object(item.get("file")).get("commit"));
        final String older =
                "a task here reads an older project file than " + commit.substring(0, 12) + ": ";
        item.remove("advance_refused");
        item.remove("advanced");
        String state = "";
        try {
            final var config =
                    java.util.Objects.requireNonNull(
                            c.runtime.configuration("porelo-admin"),
                            "An advanced project requires a forge account");
            state =
                    string(
                            new ProjectAdvance(
                                            c.files,
                                            bounded.runtime,
                                            config.account(),
                                            name,
                                            repository)
                                    .advance(
                                            repository,
                                            commit,
                                            Path.of(copied),
                                            noTaskInOwn(project, object(item.get("file")))
                                                    ? "tip"
                                                    : "signed")
                                    .get("state"));
        } catch (HostRefusal | IOException ex) {
            state = "";
        }
        if (java.util.Arrays.asList("moved", "up-to-date", "ahead", "moved-unseen")
                .contains(state)) {
            item.put("advanced", commit);
            if (state.equals("moved-unseen")) {
                c.err.println(
                        "porelo-host: "
                                + name
                                + ": main moved, and the forge's pages may show it late");
            }
        } else {
            item.put(
                    "advance_refused",
                    older
                            + (state.equals("diverged")
                                    ? "main on this host's forge holds commits the central forge lacks"
                                    : state.equals("other-changes")
                                            ? "central's main changes other files as well, which come by a person's pull request from central/main"
                                            : "the host's forge could not be brought up to it"));
        }
    }

    private void takeNamed(
            Map<String, @Nullable Object> project, Map<String, @Nullable Object> item, byte[] token)
            throws IOException {
        final Set<String> taken = new TreeSet<>();
        for (Object v : array(item.getOrDefault("taken", List.of()))) {
            taken.add(string(v));
        }
        final var waiting = fields();
        final var file = optionalObject(item.get("file"));
        final var rows = optionalObject(file.get("repositories"));
        final Object raw = project.get("repositories");
        final List<?> named = raw instanceof List<?> list && !list.isEmpty() ? list : null;
        item.put("named", named);
        final Set<String> unknown = new TreeSet<>();
        if (named != null) {
            for (Object n : named) {
                if (!rows.containsKey(string(n))) {
                    unknown.add(string(n));
                }
            }
        }
        item.put(
                "named_refused",
                unknown.isEmpty() || file.isEmpty()
                        ? null
                        : "the host's card names repositories the project file does not list: "
                                + String.join(", ", unknown));
        final String own = repository(string(project.get("address"))).toLowerCase(Locale.ROOT);
        final Set<String> kept = new HashSet<>(Set.of(own));
        rows.forEach(
                (name, value) -> {
                    final var row = object(value);
                    if ((named == null || named.contains(name)) && row.get("central") != null) {
                        kept.add(takenAs(name, row).toLowerCase(Locale.ROOT));
                    }
                });
        for (var entry : rows.entrySet()) {
            final String name = entry.getKey();
            final var row = object(entry.getValue());
            final Object central = row.get("central");
            try {
                if (named != null && !named.contains(name)) {
                    if (central != null
                            && !kept.contains(takenAs(name, row).toLowerCase(Locale.ROOT))
                            && taken.contains(central)) {
                        try {
                            projectCommand(
                                    "take-off",
                                    List.of(string(project.get("project"))),
                                    fields(
                                            "repository",
                                            takenAs(name, row),
                                            "token",
                                            token.length == 0
                                                    ? null
                                                    : new String(token, StandardCharsets.US_ASCII)),
                                    c);
                            taken.remove(central);
                            takenOff = true;
                        } catch (HostRefusal ex) {
                            waiting.put(name, "held on this host: " + ex.getMessage());
                        } catch (ProjectRefusal ex) {
                            waiting.put(name, "held on this host: " + ex.shown());
                        }
                    }
                    continue;
                }
                if (central == null) {
                    waiting.put(name, NO_CENTRAL);
                    continue;
                }
                if (central.equals(project.get("address")) || taken.contains(central)) {
                    continue;
                }
                if (token.length == 0) {
                    waiting.put(name, "waits for a central token");
                    continue;
                }
                projectCommand(
                        "migrate",
                        List.of(string(project.get("project"))),
                        fields(
                                "address",
                                central,
                                "token",
                                new String(token, StandardCharsets.US_ASCII)),
                        c);
                taken.add(string(central));
            } catch (HostRefusal ex) {
                waiting.put(name, ex.getMessage());
            } catch (ProjectRefusal ex) {
                waiting.put(name, ex.shown());
            }
        }
        item.put("taken", new ArrayList<>(taken));
        item.put("repositories_why", waiting);
        if (waiting.containsValue("waits for a central token")) {
            item.put("state", "waiting-for-token");
        }
    }

    void repositories(Map<String, @Nullable Object> applied, byte[] token) throws IOException {
        final var configuration = c.runtime.configuration(HostFollow.admin(applied));
        if (configuration == null) {
            return;
        }
        final var entries = progress.get();
        final Instant deadline = c.clock.instant().plusSeconds(60);
        final List<String> errors = new ArrayList<>();
        for (Object value : array(applied.get("projects"))) {
            final var project = object(value);
            final String name = string(project.get("project"));
            if (!entries.containsKey(name)) {
                continue;
            }
            final var item = object(entries.get(name));
            if (!Boolean.TRUE.equals(item.get("migrated")) || project.get("address") == null) {
                continue;
            }
            final String signers = fileSigners(project);
            if (!signers.isEmpty()) {
                readFile(project, item, signers, configuration.account(), deadline);
            } else {
                item.put(
                        "file_refused",
                        "the project file: no project admin's key stands to have signed it");
            }
            takeNamed(project, item, token);
            item.put("why", why(item, project));
            entries.put(name, item);
            save(entries);
            try {
                protect(project, item, HostFollow.admin(applied));
            } catch (HttpForgeAdmin.Refusal | HostRefusal ex) {
                errors.add(String.valueOf(ex.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            throw new HostRefusal(69, String.join("; ", errors));
        }
    }

    void measure(Map<String, @Nullable Object> applied) throws IOException {
        final var entries = progress.get();
        final List<String> names = new ArrayList<>();
        final Set<String> appliedNames = new HashSet<>();
        for (Object value : array(applied.get("projects"))) {
            final String name = string(object(value).get("project"));
            appliedNames.add(name);
            if (Boolean.TRUE.equals(optionalObject(entries.get(name)).get("migrated"))) {
                names.add(name);
            }
        }
        final ProjectMeasurements measurements =
                new ProjectMeasurements(c.files, c.runtime, c.clock);
        final var configuration = c.runtime.configuration(HostFollow.admin(applied));
        measurements.measure(
                configuration == null ? null : configuration.account(),
                names,
                new HostProjects(c.host)
                        .status(applied).stream()
                                .filter(row -> appliedNames.contains(row.get("project")))
                                .toList());
    }

    void members(
            Map<String, @Nullable Object> applied,
            @Nullable Map<String, @Nullable Object> previous,
            FollowPeople people)
            throws IOException {
        if (c.runtime.configuration(HostFollow.admin(applied)) == null) {
            return;
        }
        final Map<String, Map<String, @Nullable Object>> projects = new TreeMap<>();
        for (Object value : array(applied.get("projects"))) {
            final var p = object(value);
            projects.put(string(p.get("project")), p);
        }
        final Set<String> names = new TreeSet<>(projects.keySet());
        names.addAll(progress.get().keySet());
        if (previous != null) {
            for (Object value : array(previous.get("projects"))) {
                names.add(string(object(value).get("project")));
            }
        }
        final List<String> errors = new ArrayList<>();
        for (String name : names) {
            try {
                forgeMembers(name, projects.get(name), applied, previous, people);
            } catch (HttpForgeAdmin.Refusal | HostRefusal ex) {
                errors.add(name + ": " + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new HostRefusal(69, String.join("; ", errors));
        }
    }

    private void forgeMembers(
            String name,
            @Nullable Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> applied,
            @Nullable Map<String, @Nullable Object> previous,
            FollowPeople people)
            throws IOException {
        final String admin = HostFollow.admin(applied);
        final Map<String, Map<String, @Nullable Object>> desired = new TreeMap<>();
        if (project != null) {
            for (String login : people.readerLogins(project)) {
                for (Object v : array(project.get("members"))) {
                    final var m = object(v);
                    if (login.equals(m.get("login"))) {
                        desired.put(login, m);
                    }
                }
            }
        }
        final Set<String> gone = new TreeSet<>();
        if (previous != null) {
            for (Object value : array(previous.get("projects"))) {
                final var old = object(value);
                if (name.equals(old.get("project"))) {
                    for (Object v : array(old.get("members"))) {
                        final String login = string(object(v).get("login"));
                        if (!desired.containsKey(login)) {
                            gone.add(login);
                        }
                    }
                }
            }
        }
        try (var session = c.runtime.session(admin, "read:organization")) {
            try {
                session.forge().request("GET", "/orgs/" + name, null, 200);
            } catch (HttpForgeAdmin.Refusal ex) {
                if (ex.exitCode() == 65) {
                    // The in-process removal may already have deleted the organization.
                    if (project == null) {
                        pushConfigs(name, admin, List.of(), gone, false, people.accounts());
                    }
                    return;
                }
                throw ex;
            }
        }
        final var config =
                java.util.Objects.requireNonNull(
                        c.runtime.configuration(admin),
                        "Forge membership needs an installed forge");
        final Set<String> users = people.forgeUsers(config.account());
        try (var session = c.runtime.session(admin, "write:organization,read:user")) {
            final var forge = session.forge();
            final var teams = pages(forge, "/orgs/" + name + "/teams", "id");
            final Set<String> teamNames = new HashSet<>();
            teams.forEach(team -> teamNames.add(string(team.get("name"))));
            if (!teamNames.containsAll(List.of("Owners", "developers"))) {
                throw new HostRefusal(
                        65, "Forgejo organization " + name + " needs Owners and developers teams");
            }
            final Set<String> known = new HashSet<>(people.records().keySet());
            known.addAll(new HostRecords(c.host).purged());
            final Set<String> identities = new HashSet<>();
            for (String login : known) {
                final var person =
                        java.util.Objects.requireNonNull(
                                c.runtime.configuration(login),
                                "Forge membership needs identity definitions");
                identities.addAll(person.identities());
            }
            for (var team : teams) {
                final String id = String.valueOf(team.get("id")),
                        teamName = string(team.get("name"));
                final var current = pages(forge, "/teams/" + id + "/members", "login");
                final Set<String> want = new TreeSet<>();
                for (var entry : desired.entrySet()) {
                    final String login = entry.getKey();
                    if (teamName.equals(
                            "admin".equals(entry.getValue().get("role"))
                                    ? "Owners"
                                    : "developers")) {
                        want.add(login);
                    }
                    if (teamName.equals("developers")) {
                        final var person =
                                java.util.Objects.requireNonNull(
                                        c.runtime.configuration(login),
                                        "A project member has forge identities");
                        for (String identity :
                                person.identities().subList(1, person.identities().size())) {
                            if (users.contains(identity)) {
                                want.add(identity);
                            }
                        }
                    }
                }
                final Set<String> held = new HashSet<>();
                current.forEach(row -> held.add(string(row.get("login"))));
                for (String login : want) {
                    if (!held.contains(login)) {
                        forge.request("PUT", "/teams/" + id + "/members/" + login, null, 204);
                    }
                }
                for (var row : current) {
                    final String login = string(row.get("login"));
                    if (identities.contains(login) && !want.contains(login)) {
                        forge.request("DELETE", "/teams/" + id + "/members/" + login, null, 204);
                    }
                }
            }
        }
        if (project != null) {
            new FollowPushes(c).arm(name, config.account());
        }
        pushConfigs(
                name,
                admin,
                new ArrayList<>(desired.keySet()),
                gone,
                project != null,
                people.accounts());
    }

    static List<Map<String, @Nullable Object>> pages(
            HttpForgeAdmin forge, String route, String field) {
        final List<Map<String, @Nullable Object>> result = new ArrayList<>();
        final Set<Object> seen = new HashSet<>();
        for (Map<?, ?> row : forge.listing(route)) {
            final Object value = row.get(field);
            final boolean valid =
                    field.equals("id")
                            ? value instanceof Number n
                                    && integral(value)
                                    && (n instanceof java.math.BigInteger big
                                            ? big.signum() > 0
                                            : n.longValue() > 0)
                            : value instanceof String text
                                    && text.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}");
            if (!valid
                    || !seen.add(
                            java.util.Objects.requireNonNull(
                                    value, "A validated page entry has its identity"))) {
                throw new HostRefusal(69, "Forgejo answered an invalid or repeated page entry");
            }
            result.add(object(row));
        }
        return result;
    }

    private void pushConfigs(
            String project,
            String admin,
            List<String> members,
            Set<String> gone,
            boolean carried,
            Map<String, Long> accounts)
            throws IOException {
        String text = null;
        if (carried && !members.isEmpty()) {
            final List<String> lines = new ArrayList<>();
            final var file =
                    optionalObject(optionalObject(progress.get().get(project)).get("file"));
            final var declared = optionalObject(file.get("repositories"));
            try (var session = c.runtime.session(admin, "read:organization,read:repository")) {
                final var repos = pages(session.forge(), "/orgs/" + project + "/repos", "name");
                repos.sort(java.util.Comparator.comparing(row -> string(row.get("name"))));
                for (var row : repos) {
                    if (!(row.get("name") instanceof String name)
                            || !name.matches(ProjectRecords.REPOSITORY)
                            || !(row.get("original_url") instanceof String address)
                            || !address.matches(
                                    "https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[^\\s?#@]+")) {
                        throw new HostRefusal(
                                65,
                                project + ": a repository has no valid central address to push to");
                    }
                    final var keys =
                            declared.entrySet().stream()
                                    .filter(
                                            entry ->
                                                    object(entry.getValue()).get("central")
                                                            instanceof String)
                                    .filter(
                                            entry ->
                                                    takenAs(
                                                                    entry.getKey(),
                                                                    object(entry.getValue()))
                                                            .equalsIgnoreCase(name))
                                    .map(Map.Entry::getKey)
                                    .toList();
                    if (keys.isEmpty()) lines.add("repository " + name + " " + address + "\n");
                    for (String key : keys) {
                        final String expected = string(object(declared.get(key)).get("central"));
                        if (!ProjectAddress.parse(expected)
                                .https()
                                .equals(ProjectAddress.parse(address).https()))
                            throw new HostRefusal(
                                    65,
                                    project
                                            + ": project key '"
                                            + key
                                            + "' has an ambiguous central destination for forge repository '"
                                            + name
                                            + "'");
                        lines.add("repository " + name + " " + key + " " + address + "\n");
                    }
                }
            }
            final String port = c.setting("/etc/porelo-host/forge.conf", "git_port"),
                    ca = c.host.read("/etc/porelo-host/forge/central-ca.conf").strip();
            if (!port.matches("[0-9]{1,5}") || !ca.matches("/\\S+")) {
                throw new HostRefusal(69, "the forge's git port or central CA is not valid");
            }
            text =
                    "# Written by porelo-host org follow; read by porelo host push.\nlocal http://127.0.0.1:"
                            + port
                            + "\nowner "
                            + project
                            + "\ncentral-ca "
                            + ca
                            + "\n"
                            + String.join("", lines);
        }
        for (String login : text == null ? List.<String>of() : members) {
            if (accounts.containsKey(login)) {
                final String script =
                        "umask 077; d=\"$HOME/.config/porelo-host/push\"; mkdir -p \"$d\" && chmod 700 \"$d\" && cat > \"$d/$1.conf.new\" && mv -f \"$d/$1.conf.new\" \"$d/$1.conf\"";
                if (c.as(
                                        login,
                                        List.of("sh", "-c", script, "sh", project),
                                        java.util.Objects.requireNonNull(
                                                        text,
                                                        "Members are only written with a push configuration")
                                                .getBytes(StandardCharsets.UTF_8))
                                .exitCode()
                        != 0) {
                    throw new HostRefusal(
                            69, "the person's push configuration could not be written");
                }
            }
        }
        final List<String> errors = new ArrayList<>();
        for (String login : gone) {
            HostFollow.attempt(
                    errors,
                    () -> {
                        if (accounts.containsKey(login)
                                && c.as(
                                                        login,
                                                        List.of(
                                                                "sh",
                                                                "-c",
                                                                "rm -f \"$HOME/.config/porelo-host/push/$1.conf\"",
                                                                "sh",
                                                                project),
                                                        new byte[0])
                                                .exitCode()
                                        != 0) {
                            throw new HostRefusal(
                                    69, "the person's push configuration could not be removed");
                        }
                    });
        }
        if (!errors.isEmpty()) {
            throw new HostRefusal(69, String.join("; ", errors));
        }
    }

    static Map<String, @Nullable Object> protection() {
        return fields(
                "enable_push",
                false,
                "enable_approvals_whitelist",
                true,
                "approvals_whitelist_teams",
                List.of("Owners", "developers"),
                "enable_merge_whitelist",
                true,
                "merge_whitelist_teams",
                List.of("Owners", "developers"),
                "enable_status_check",
                false,
                "apply_to_admins",
                true,
                "dismiss_stale_approvals",
                true,
                "push_whitelist_usernames",
                List.of(),
                "merge_whitelist_usernames",
                List.of(),
                "approvals_whitelist_username",
                List.of());
    }

    private static boolean integral(@Nullable Object value) {
        return value instanceof java.math.BigInteger
                || value instanceof Long
                || value instanceof Integer
                || value instanceof Short
                || value instanceof Byte;
    }

    static boolean same(@Nullable Object found, @Nullable Object wanted) {
        if (wanted instanceof List<?> list) {
            final Object value = found == null ? List.of() : found;
            return value instanceof List<?> rows
                    && rows.stream()
                            .map(v -> String.valueOf(v).toLowerCase(Locale.ROOT))
                            .sorted()
                            .toList()
                            .equals(
                                    list.stream()
                                            .map(v -> String.valueOf(v).toLowerCase(Locale.ROOT))
                                            .sorted()
                                            .toList());
        }
        return java.util.Objects.equals(found, wanted)
                || found instanceof Number a
                        && wanted instanceof Number b
                        && new java.math.BigDecimal(a.toString())
                                        .compareTo(new java.math.BigDecimal(b.toString()))
                                == 0;
    }

    private void protect(
            Map<String, @Nullable Object> project,
            Map<String, @Nullable Object> item,
            String admin) {
        final String name = string(project.get("project")),
                own = repository(string(project.get("address"))).toLowerCase(Locale.ROOT);
        final var file = optionalObject(item.get("file"));
        final Map<String, @Nullable Object> reviews = new TreeMap<>();
        reviews.put(own, file.get("review"));
        optionalObject(file.get("repositories"))
                .forEach(
                        (repository, value) -> {
                            final var row = object(value);
                            reviews.put(
                                    takenAs(repository, row).toLowerCase(Locale.ROOT),
                                    row.get("review"));
                        });
        final String closed = noTaskInOwn(project, file) ? own : "";
        final List<String> failed = new ArrayList<>();
        try (var session = c.runtime.session(admin, "write:repository,read:organization")) {
            final var forge = session.forge();
            final var repositories = pages(forge, "/orgs/" + name + "/repos", "name");
            repositories.sort(java.util.Comparator.comparing(row -> string(row.get("name"))));
            for (var repository : repositories) {
                final String repo = string(repository.get("name")),
                        review =
                                string(
                                        reviews.get(repo.toLowerCase(Locale.ROOT)) == null
                                                ? "required"
                                                : reviews.get(repo.toLowerCase(Locale.ROOT)));
                final var wanted = protection();
                wanted.put("required_approvals", review.equals("required") ? 1 : 0);
                if (repo.equalsIgnoreCase(closed)) {
                    wanted.put("merge_whitelist_teams", List.of());
                }
                final String route = "/repos/" + name + "/" + repo + "/branch_protections";
                try {
                    final Object value = forge.request("GET", route, null, 200);
                    if (!(value instanceof List<?> rules)
                            || rules.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) {
                        throw new HostRefusal(69, "Forgejo answered no valid list");
                    }
                    final List<Map<String, @Nullable Object>> onMain = new ArrayList<>();
                    for (Object row : rules) {
                        final var rule = object(row);
                        if ("main".equalsIgnoreCase(String.valueOf(rule.get("rule_name")))) {
                            onMain.add(rule);
                        }
                    }
                    onMain.sort(
                            java.util.Comparator.comparing(
                                    row -> String.valueOf(row.get("created_at"))));
                    boolean changed = false;
                    if (onMain.isEmpty()) {
                        final var made = fields("rule_name", "main");
                        made.putAll(wanted);
                        forge.request("POST", route, made, 201);
                        changed = true;
                    } else if (wanted.entrySet().stream()
                            .anyMatch(
                                    entry ->
                                            !same(
                                                    onMain.getFirst().get(entry.getKey()),
                                                    entry.getValue()))) {
                        forge.request(
                                "PATCH",
                                route + "/" + string(onMain.getFirst().get("rule_name")),
                                wanted,
                                200);
                        changed = true;
                    }
                    for (var extra : onMain.subList(Math.min(1, onMain.size()), onMain.size())) {
                        forge.request(
                                "DELETE", route + "/" + string(extra.get("rule_name")), null, 204);
                        changed = true;
                    }
                    if (changed) {
                        c.err.println(
                                "porelo-host: "
                                        + name
                                        + "/"
                                        + repo
                                        + ": main's review is "
                                        + review);
                    }
                } catch (HttpForgeAdmin.Refusal | HostRefusal ex) {
                    failed.add(
                            name
                                    + "/"
                                    + repo
                                    + ": main's protection could not be set: "
                                    + ex.getMessage());
                }
            }
        }
        if (!failed.isEmpty()) {
            throw new HostRefusal(69, String.join("; ", failed));
        }
    }
}
