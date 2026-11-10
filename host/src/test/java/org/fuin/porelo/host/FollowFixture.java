package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.fuin.porelo.organization.gittree.TestForgeAdmin;

/** Rootless host, account, ACL and systemd boundaries; Git project helpers remain in-process. */
final class FollowFixture {
    static final String ORG = FollowContext.ORG;
    final ProjectFixture project;
    final HostFiles host;
    final ProjectRuntime runtime;
    final List<List<String>> commands = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    final List<String> inputs = new ArrayList<>();
    final Map<Object, List<String>> acls = new HashMap<>();
    final Map<String, Long> accounts = new TreeMap<>();
    final Set<String> peopleGroup = new TreeSet<>();
    final Map<String, Map<String, Object>> rules = new TreeMap<>();
    final StringWriter journal = new StringWriter();
    final Clock clock;
    Instant now = Instant.parse("2026-11-04T00:00:00Z");
    Map<String, Object> answer;
    Map<String, Object> verified =
            new LinkedHashMap<>(
                    Map.of(
                            "project",
                            "alpha",
                            "project_review",
                            "optional",
                            "repositories",
                            new LinkedHashMap<String, Object>()));
    int fetchCode, readKeyCode, verifyCode, roomCode;
    String fetchErrors = "", advanceState = "up-to-date";
    boolean noReadKeyAddedAnswer, noSigners, noForge;
    int advanceCount, verifyCount, copyCount;
    Function<List<String>, HostPrograms.Result> override = argv -> null;
    Consumer<List<String>> before = argv -> {};
    Consumer<ProjectFixture.Call> forgeBefore = call -> {};
    final Set<String> linger = new HashSet<>();
    final Set<String> missing = new HashSet<>();
    final Set<Path> sessionBuses = new HashSet<>();

    FollowFixture(Path root) throws Exception {
        project = new ProjectFixture(root);
        final Map<Path, Integer> owners = new HashMap<>();
        project.files =
                new ProjectFiles(
                        root,
                        project.files.owner,
                        Files::deleteIfExists,
                        path -> {
                            final var attrs =
                                    new HashMap<>(
                                            Files.readAttributes(
                                                    path,
                                                    "unix:uid,mode",
                                                    LinkOption.NOFOLLOW_LINKS));
                            if (owners.containsKey(path)) attrs.put("uid", owners.get(path));
                            return attrs;
                        },
                        (path, uid, gid) -> owners.put(path, uid));
        host =
                new HostFiles(
                        root,
                        path -> {
                            if (sessionBuses.contains(path)) {
                                return Map.of("uid", 0, "mode", 0140600);
                            }
                            final boolean descriptor = path.toString().startsWith("/proc/self/fd/");
                            final var attrs =
                                    new HashMap<>(
                                            Files.readAttributes(
                                                    path,
                                                    "unix:uid,mode",
                                                    descriptor
                                                            ? new LinkOption[0]
                                                            : new LinkOption[] {
                                                                LinkOption.NOFOLLOW_LINKS
                                                            }));
                            attrs.put("uid", 0);
                            if (path.getParent() != null
                                    && path.getParent()
                                            .equals(root.resolve("run/porelo-host/people"))) {
                                attrs.put("uid", owners.getOrDefault(path, project.files.owner));
                            }
                            if (!descriptor && !path.startsWith(root)) {
                                attrs.put("mode", 040755);
                            }
                            return attrs;
                        });
        clock =
                new Clock() {
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return now;
                    }
                };
        final long uid = ((Number) Files.getAttribute(root, "unix:uid")).longValue();
        for (String login : List.of("alice", "bob", "charlie", "forge")) {
            accounts.put(login, login.equals("forge") ? uid : uid + accounts.size() + 1);
        }
        write("/etc/login.defs", "UID_MIN 1000\n");
        write("/etc/subuid", "");
        write("/etc/subgid", "");
        write("/etc/porelo-host/forge.conf", "host=build-host\naccount=\n");
        Files.delete(project.files.at(ORG + "applied.json"));
        write(ORG + "pin", "alice " + key(1) + "\n");
        write(ORG + "read/organization.added", "added\n");
        write(ORG + "read/organization", "fixture-read-material");
        write(ORG + "read/organization.pub", key(0) + "\n");
        write("/etc/porelo-host/people/alice.conf", person("host-admin", 1));
        write("/etc/porelo-host/people/bob.conf", person("alpha project-admin", 2));
        write("/etc/porelo-host/people/charlie.conf", person("alpha developer", 3));
        peopleGroup.addAll(List.of("alice", "bob", "charlie"));
        answer =
                new LinkedHashMap<>(
                        fields(
                                "revision",
                                "a".repeat(40),
                                "host_card",
                                fields(
                                        "host",
                                        "build-host",
                                        "services",
                                        fields("forge", fields("git_port", 3000))),
                                "persons",
                                new ArrayList<>(
                                        List.of(
                                                card("alice", 1),
                                                card("bob", 2),
                                                card("charlie", 3))),
                                "admins",
                                new ArrayList<>(List.of(admin("alice", 1))),
                                "projects",
                                new ArrayList<>(List.of(project("alpha")))));
        runtime =
                new ProjectRuntime(
                        project.files,
                        this::command,
                        name -> {
                            if (missing.contains(Path.of(name).getFileName().toString())) {
                                throw new UncheckedIOException(
                                        new org.fuin.porelo.wire.SystemProgram.Missing(
                                                name, List.of()));
                            }
                            return Path.of(name);
                        },
                        sessionBuses::contains,
                        (path, token) ->
                                TestForgeAdmin.create(
                                        token,
                                        false,
                                        (method, route, auth, body) -> {
                                            final String raw = route.split("\\?", 2)[0];
                                            final Object data = ProjectFixture.parseBody(body);
                                            final var call =
                                                    new ProjectFixture.Call(
                                                            method, "/api/v1" + route, data);
                                            forgeBefore.accept(call);
                                            if (raw.matches("/admin/users/[^/]+/tokens")
                                                    && method.equals("GET")) {
                                                project.calls.add(call);
                                                return response(200, List.of());
                                            }

                                            if (raw.matches("/admin/users/[^/]+")
                                                    && method.equals("PATCH")) {
                                                project.calls.add(call);
                                                return response(200, Map.of());
                                            }
                                            if (raw.matches("/teams/[0-9]+/members")
                                                    && method.equals("GET")) {
                                                project.calls.add(call);
                                                final long id = Long.parseLong(raw.split("/")[2]);
                                                final var team = project.teams.get(id);
                                                final var members =
                                                        team == null
                                                                ? List.of()
                                                                : (List<?>) team.get("members");
                                                return response(
                                                        200,
                                                        route.contains("page=1")
                                                                ? members.stream()
                                                                        .map(
                                                                                login ->
                                                                                        Map.of(
                                                                                                "login",
                                                                                                login))
                                                                        .toList()
                                                                : List.of());
                                            }
                                            if (raw.matches("/teams/[0-9]+/members/.*")
                                                    && method.equals("DELETE")) {
                                                project.calls.add(call);
                                                final long id = Long.parseLong(raw.split("/")[2]);
                                                ((List<String>)
                                                                project.teams
                                                                        .get(id)
                                                                        .get("members"))
                                                        .remove(
                                                                raw.substring(
                                                                        raw.lastIndexOf('/') + 1));
                                                return response(204, null);
                                            }
                                            if (raw.contains("branch_protections")) {
                                                project.calls.add(call);
                                                if (method.equals("GET")) {
                                                    return response(
                                                            200, new ArrayList<>(rules.values()));
                                                }
                                                if (method.equals("POST")) {
                                                    rules.put(
                                                            String.valueOf(
                                                                    ((Map<?, ?>) data)
                                                                            .get("rule_name")),
                                                            ProjectFixture.copy((Map<?, ?>) data));
                                                    return response(201, data);
                                                }
                                                if (method.equals("PATCH")) {
                                                    final String rule =
                                                            raw.substring(raw.lastIndexOf('/') + 1);
                                                    final var updated =
                                                            new LinkedHashMap<>(
                                                                    rules.getOrDefault(
                                                                            rule,
                                                                            Map.of(
                                                                                    "rule_name",
                                                                                    rule)));
                                                    updated.putAll(
                                                            ProjectFixture.copy((Map<?, ?>) data));
                                                    rules.put(rule, updated);
                                                    return response(200, updated);
                                                }
                                                rules.remove(
                                                        raw.substring(raw.lastIndexOf('/') + 1));
                                                return response(204, null);
                                            }
                                            return project.localExchange(method, route, auth, body);
                                        }),
                        (argv, input, output, env, timeout) -> {
                            if (argv.contains("rev-list")) {
                                final var result =
                                        command(argv, input.readAllBytes(), env, timeout);
                                output.write(result.output().getBytes(StandardCharsets.UTF_8));
                                return new HostPrograms.Result(result.exitCode(), "");
                            }
                            commands.add(List.copyOf(argv));
                            timeouts.add(timeout);
                            inputs.add("<binary>");
                            output.write("fixture bundle".getBytes(StandardCharsets.UTF_8));
                            return new HostPrograms.Result(
                                    argv.contains("post-receive")
                                                    && advanceState.equals("moved-unseen")
                                            ? 1
                                            : 0,
                                    "");
                        },
                        host);
    }

    private static TestForgeAdmin.Response response(int code, Object data) {
        return new TestForgeAdmin.Response(
                code,
                data == null ? new byte[0] : HostJson.write(data).getBytes(StandardCharsets.UTF_8));
    }

    static String key(int number) {
        final byte[] wire = Base64.getDecoder().decode(ProjectFixture.KEY.substring(12));
        Arrays.fill(wire, 19, 51, (byte) number);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(wire);
    }

    static Map<String, Object> card(String login, int key) {
        return new LinkedHashMap<>(
                fields("login", login, "key", key(key), "expires", "2028-11-02", "revoked", false));
    }

    static Map<String, Object> admin(String login, int number) {
        return new LinkedHashMap<>(
                fields(
                        "login",
                        login,
                        "key",
                        key(number),
                        "fingerprint",
                        HostRecords.fingerprint(key(number)),
                        "backup",
                        false));
    }

    static Map<String, Object> project(String name) {
        return new LinkedHashMap<>(
                fields(
                        "project",
                        name,
                        "address",
                        "https://forge.example.org/acme/code.git",
                        "members",
                        new ArrayList<>(
                                List.of(
                                        fields("login", "bob", "role", "admin", "locked", false),
                                        fields(
                                                "login",
                                                "charlie",
                                                "role",
                                                "developer",
                                                "locked",
                                                false))),
                        "signing_keys",
                        new ArrayList<>(
                                List.of(
                                        fields(
                                                "login",
                                                "bob",
                                                "role",
                                                "admin",
                                                "key",
                                                key(2),
                                                "valid_from",
                                                "2026-11-02",
                                                "valid_until",
                                                "2028-11-02",
                                                "revoked_since",
                                                null)))));
    }

    static String person(String member, int number) {
        return "member=" + member + "\nlocked=false\nssh=" + key(number) + "\nexpires=2028-11-02\n";
    }

    void write(String name, String text) throws IOException {
        project.directories(
                ProjectFiles.parent(project.files.at(name))
                        .toString()
                        .substring(project.root.toString().length()));
        project.write(name, text);
    }

    Path path(String name) {
        return project.files.at(name);
    }

    Map<String, Object> person(int index) {
        return (Map<String, Object>) ((List<?>) answer.get("persons")).get(index);
    }

    Map<String, Object> project() {
        return (Map<String, Object>) ((List<?>) answer.get("projects")).getFirst();
    }

    Map<String, Object> progress() {
        return (Map<String, Object>) HostJson.parse(read(ORG + "host-projects.json"));
    }

    Map<String, Object> item() {
        return (Map<String, Object>) progress().get("alpha");
    }

    String read(String name) {
        try {
            return Files.readString(path(name));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    Map<String, Object> json(String name) {
        return (Map<String, Object>) HostJson.parse(read(name));
    }

    Map<String, Object> applied() {
        return json(ORG + "applied.json");
    }

    HostFollow follow() {
        return new HostFollow(
                host,
                project.files,
                runtime,
                clock,
                new PrintWriter(journal, true),
                (origin, token) -> TestForgeAdmin.create(token, true, project::centralExchange));
    }

    Map<String, Object> run() throws IOException {
        return new LinkedHashMap<>(
                follow().run("follow", Map.of(), new ByteArrayInputStream(new byte[0])));
    }

    Map<String, Object> refresh(String token) throws IOException {
        return new LinkedHashMap<>(
                follow().run(
                                "refresh",
                                Map.of(),
                                new ByteArrayInputStream(token.getBytes(StandardCharsets.UTF_8))));
    }

    void next(String revision) {
        answer.put("revision", revision.repeat(40));
    }

    void forge() throws IOException {
        sessionBuses.add(path("/run/user/" + accounts.get("forge") + "/bus"));
        write(
                "/etc/porelo-host/forge.conf",
                "host=build-host\naccount=forge\nname=forge.example.org\ngit_port=3000\n");
        write("/etc/porelo-host/forge/central-ca.conf", "/etc/ssl/certs/ca-certificates.crt\n");
    }

    void migrated() throws Exception {
        forge();
        project.organizations.add("alpha");
        project.users.addAll(
                List.of(
                        "alice",
                        "bob",
                        "charlie",
                        "porelo-bob",
                        "porelo-charlie",
                        "porelo-publisher-alpha"));
        project.teams.put(
                1L,
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                1L,
                                "name",
                                "Owners",
                                "org",
                                "alpha",
                                "members",
                                new ArrayList<>())));
        project.teams.put(
                2L,
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                2L,
                                "name",
                                "developers",
                                "org",
                                "alpha",
                                "members",
                                new ArrayList<>())));
        project.directories("/etc/porelo-host/projects");
        write(
                "/etc/porelo-host/projects/alpha.conf",
                "admin=bob\nsigned_by=" + key(2) + "\npublisher=porelo-publisher-alpha\n");
        project.readKeys();
        write(
                ORG + "host-projects.json",
                HostJson.write(Map.of("alpha", Map.of("state", "carried", "migrated", true))));
    }

    FollowContext context() {
        return new FollowContext(
                        host, project.files, runtime, clock, new PrintWriter(journal, true))
                .until(now.plusSeconds(300));
    }

    // A file system reuses a deleted file's inode: a new file must not inherit its ACL.
    private static List<Object> identity(Path file) throws IOException {
        return List.of(
                Files.getAttribute(file, "unix:ino"), Files.getAttribute(file, "creationTime"));
    }

    List<String> readers(String name) throws IOException {
        return acls.getOrDefault(identity(path(name)), List.of());
    }

    private HostPrograms.Result command(
            List<String> argv, byte[] input, Map<String, String> env, Duration timeout)
            throws IOException {
        commands.add(List.copyOf(argv));
        timeouts.add(timeout);
        inputs.add(new String(input, StandardCharsets.UTF_8));
        assertThat(env).doesNotContainKey("SUDO_USER");
        assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(300));
        before.accept(argv);
        final var custom = override.apply(argv);
        if (custom != null) {
            return custom;
        }
        final String name = Path.of(argv.getFirst()).getFileName().toString();
        if (name.equals("getent")) {
            if (argv.get(1).equals("group")) {
                return new HostPrograms.Result(
                        0, "porelo-people:x:2000:" + String.join(",", peopleGroup) + "\n");
            }
            if (argv.size() > 2) {
                final String login = argv.getLast();
                return accounts.containsKey(login)
                        ? new HostPrograms.Result(
                                0,
                                login
                                        + ":x:"
                                        + accounts.get(login)
                                        + ":"
                                        + accounts.get(login)
                                        + ":"
                                        + ",,,,umask=0022"
                                        + ":/home/"
                                        + login
                                        + ":/bin/bash\n")
                        : new HostPrograms.Result(2, "");
            }
            final StringBuilder result = new StringBuilder();
            accounts.forEach(
                    (login, uid) ->
                            result.append(
                                    login
                                            + ":x:"
                                            + uid
                                            + ":"
                                            + uid
                                            + "::/home/"
                                            + login
                                            + ":/bin/bash\n"));
            return new HostPrograms.Result(0, result.toString());
        }
        if (name.equals("id")) {
            return new HostPrograms.Result(0, "" + accounts.get("forge"));
        }
        if (name.equals("systemd-run")) {
            if (argv.contains("fetch")) {
                return new HostPrograms.Result(fetchCode, HostJson.write(answer), fetchErrors);
            }
            if (argv.contains("add-read-key")) {
                return new HostPrograms.Result(
                        readKeyCode,
                        noReadKeyAddedAnswer ? "{}" : "{\"added\":true}",
                        "read key refused");
            }
            if (argv.contains("signers")) {
                final String project = argv.get(argv.indexOf("--project") + 1);
                return new HostPrograms.Result(
                        0,
                        HostJson.write(
                                fields(
                                        "project",
                                        project,
                                        "revision",
                                        answer.get("revision"),
                                        "signers",
                                        noSigners ? "" : "bob namespaces=\"git\" " + key(2) + "\n",
                                        "certifiers",
                                        "task:"
                                                + project
                                                + ":* cert-authority,namespaces=\"git\" "
                                                + key(2)
                                                + "\n")));
            }
            if (argv.contains("verify")) {
                verifyCount++;
                return new HostPrograms.Result(
                        verifyCode, HostJson.write(verified), "file refused");
            }
        }
        if (name.equals("setfacl")) {
            acls.put(
                    identity(Path.of(argv.getLast())),
                    List.of(argv.get(argv.indexOf("--modify") + 1).split(",")));
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("ssh-keygen")) {
            return new HostPrograms(timeout, 1024 * 1024, Path::of).run(argv, input, env);
        }
        if (name.equals("usermod")) {
            if (argv.contains("--groups")) {
                peopleGroup.add(argv.getLast());
            }
            if (argv.contains("--add-subuids") || argv.contains("--add-subgids")) {
                final String kind = argv.contains("--add-subuids") ? "subuid" : "subgid";
                final String[] span = argv.get(2).split("-");
                write(
                        "/etc/" + kind,
                        read("/etc/" + kind)
                                + argv.getLast()
                                + ":"
                                + span[0]
                                + ":"
                                + (Long.parseLong(span[1]) - Long.parseLong(span[0]) + 1)
                                + "\n");
            }
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("gpasswd")) {
            peopleGroup.remove(argv.get(2));
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("loginctl")) {
            if (argv.contains("enable-linger")) {
                linger.add(argv.getLast());
            }
            if (argv.contains("disable-linger")) {
                linger.remove(argv.getLast());
            }
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("useradd")) {
            accounts.put(
                    argv.getLast(),
                    accounts.values().stream().mapToLong(Long::longValue).max().orElse(1000) + 1);
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("userdel")) {
            accounts.remove(argv.getLast());
            return new HostPrograms.Result(0, "");
        }
        if (name.equals("systemctl") || name.equals("groupadd")) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("keep-room")) {
            return new HostPrograms.Result(roomCode, "{\"room\":\"!alpha:host\",\"removed\":[]}");
        }
        if (argv.contains("drop-room")) {
            return new HostPrograms.Result(roomCode, "");
        }
        if (argv.contains("cat") && argv.getLast().endsWith("admin-token")) {
            return new HostPrograms.Result(0, "matrix-private-token\n");
        }
        if (argv.contains("sh")
                && argv.contains("-c")
                && argv.stream().anyMatch(a -> a.contains("custom_hooks_dir"))) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("--property=MainPID")) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("setup")
                || argv.contains("--user")
                || argv.contains("--all") && argv.contains("porelo")
                || argv.contains("expel")
                || argv.contains("-c")
                        && argv.stream().anyMatch(a -> a.contains("porelo-host/push"))) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("vault") && argv.contains("clear")) {
            return new HostPrograms.Result(0, "{\"refused\":false,\"items\":[]}");
        }
        if (argv.contains("generate-access-token")) {
            return new HostPrograms.Result(0, ProjectFixture.ADMIN_TOKEN);
        }
        if (argv.contains("forgejo") && argv.contains("admin") && argv.contains("list")) {
            return new HostPrograms.Result(
                    0,
                    "ID Username Email\n1 alice alice@host\n2 bob bob@host\n3 charlie charlie@host\n4 porelo-bob x@host\n5 porelo-charlie x@host\n");
        }
        if (argv.contains("git") && argv.contains("log")) {
            return new HostPrograms.Result(
                    0, argv.contains("--format=%H") ? project.centralMain + "\n" : "");
        }
        if (argv.contains("git") && argv.contains("rev-parse")) {
            final String ref = argv.getLast();
            return new HostPrograms.Result(
                    0,
                    ref.contains(":project.yml")
                            ? "c".repeat(40)
                            : ref.startsWith("refs/porelo/central")
                                    ? project.centralMain
                                    : project.localMain);
        }
        if (argv.contains("git") && argv.contains("merge-base")) {
            final boolean localFirst = argv.get(argv.size() - 2).equals(project.localMain);
            return new HostPrograms.Result(
                    advanceState.equals("diverged")
                            ? 1
                            : advanceState.equals("ahead") ? (localFirst ? 1 : 0) : 0,
                    "");
        }
        if (argv.contains("git") && argv.contains("diff")) {
            return new HostPrograms.Result(
                    0, advanceState.equals("other-changes") ? "source.java\n" : "project.yml\n");
        }
        if (argv.contains("git") && argv.contains("show")) {
            return new HostPrograms.Result(0, "project: alpha\n");
        }
        if (argv.contains("git") && argv.contains("ls-tree")) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("git") && (argv.contains("fetch") || argv.contains("update-ref"))) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("forgejo") && (argv.contains("install") || argv.contains("rm"))) {
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("clone")) {
            copyCount++;
        }
        if (argv.contains("update-ref") && argv.contains("refs/heads/main")) {
            advanceCount++;
        }
        return project.runtime.run(argv, input, env, timeout);
    }
}
