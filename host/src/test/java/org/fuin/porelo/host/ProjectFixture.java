package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.fuin.porelo.organization.gittree.TestForgeAdmin;
import org.fuin.porelo.wire.Json;

/**
 * A request-level forge and checked command stand-ins; no account or external service is reached.
 */
final class ProjectFixture implements AutoCloseable {
    static final String KEY = key();
    static final String CENTRAL_TOKEN = "central-test-token";
    static final String ADMIN_TOKEN = "0123456789abcdef0123456789abcdef01234567";
    static final String PUBLISHER_TOKEN = "1".repeat(40);
    static final String READ_TOKEN = "2".repeat(40);
    static final String ADDRESS = "https://forge.example.org/acme/code.git";
    final Path root;
    ProjectFiles files;
    ProjectRuntime runtime;
    final ProjectRecords records;
    final List<List<String>> commands = new ArrayList<>();
    final List<Map<String, String>> environments = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Set<String> organizations = ConcurrentHashMap.newKeySet();
    final Set<String> users = ConcurrentHashMap.newKeySet();
    final Map<Long, Map<String, Object>> teams = new ConcurrentHashMap<>();
    final Map<String, Object> secrets = new ConcurrentHashMap<>();
    final Map<String, Object> variables = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> repositories = new ConcurrentHashMap<>();
    final Map<String, List<Object>> packages = new ConcurrentHashMap<>();
    final List<Map<String, Object>> centralKeys = new CopyOnWriteArrayList<>();
    final List<String> centralCalls = new CopyOnWriteArrayList<>();
    final Map<String, Reply> replies = new ConcurrentHashMap<>();
    final Map<String, List<List<Object>>> pages = new ConcurrentHashMap<>();
    final Map<String, Object> protectedBranches = new ConcurrentHashMap<>();
    final Map<String, Object> mergeStyle = new ConcurrentHashMap<>();
    boolean unavailable;
    boolean realKeygen;
    boolean realSocket;
    Path socketOverride;
    boolean rejectInterruptedRequests;
    int umask = -1;
    HostProject.Locking locking = ProjectLock::acquire;
    Consumer<Call> beforeCall = call -> {};
    Function<List<String>, HostPrograms.Result> program = argv -> null;
    String home = "/home/forge";
    String uid = "1234";
    String localCommits = "a".repeat(40) + "\n";
    String centralCommits = localCommits;
    String centralMain = "a".repeat(40);
    String localMain = centralMain;
    int ancestor = 0;
    String ahead = "3\n";
    int gitFailure;
    int pushFailure;
    String pushInput;
    long teamId = 1;
    boolean holdDeployKey;
    String gitError = "";

    record Call(String method, String path, Object body) {}

    record Reply(int status, Object body) {}

    record Answer(int code, String out, String err) {}

    ProjectFixture(Path root) throws Exception {
        this.root = root;
        files = new ProjectFiles(root, (int) Files.getAttribute(root, "unix:uid"));
        records = new ProjectRecords(files);
        directories(
                "/etc/porelo-host/people",
                "/etc/porelo-host/organization/read",
                "/etc/porelo-host/forge",
                "/run/user/1234/porelo-forge",
                "/tmp",
                "/usr/sbin");
        write("/etc/porelo-host/forge.conf", "account=forge\nhost=build-host\n");
        write("/etc/porelo-host/people/alice.conf", person("member=host-admin\n", false, false));
        write("/etc/porelo-host/people/bob.conf", person("member=host-admin\n", false, false));
        write(
                "/etc/porelo-host/organization/applied.json",
                "{\"projects\":[{\"project\":\"alpha\",\"members\":[{\"login\":\"alice\",\"role\":\"admin\"}]},{\"project\":\"beta\",\"members\":[{\"login\":\"bob\",\"role\":\"admin\"}]}],\"revoked\":[]}");
        write(
                "/etc/porelo-host/organization/organization.yml",
                "adapter: git\nsettings:\n  repository: git@forge.example.org:acme/organization.git\n");
        write("/etc/porelo-host/organization/known_hosts", "fixture-host-key\n");
        write(
                "/etc/porelo-host/forge/organization.json",
                "{\"variables\":{\"PROJECT\":\"{project}\"},\"placeholder_secrets\":[\"JF_ACCESS_TOKEN\"]}");
        write("/usr/sbin/porelo-host", "fixture only\n");
        users.addAll(List.of("alice", "bob"));
        runtime =
                new ProjectRuntime(
                        files,
                        this::command,
                        Path::of,
                        path -> true,
                        new ProjectRuntime.ForgeFactory() {
                            @Override
                            public org.fuin.porelo.organization.gittree.HttpForgeAdmin open(
                                    Path path, char[] token) {
                                assertThat(path)
                                        .as(
                                                "the local admin transport is bound to the forge's Unix socket")
                                        .isEqualTo(
                                                files.at("/run/user/1234/porelo-forge/http.sock"));
                                return realSocket
                                        // A short test socket fits the 108-byte limit; the runtime
                                        // path is checked above.
                                        ? new org.fuin.porelo.organization.gittree.HttpForgeAdmin(
                                                socketOverride == null ? path : socketOverride,
                                                token)
                                        : TestForgeAdmin.create(
                                                token, false, ProjectFixture.this::localExchange);
                            }

                            @Override
                            public org.fuin.porelo.organization.gittree.HttpForgeAdmin
                                    unauthenticated(Path path) {
                                return TestForgeAdmin.create(
                                        ADMIN_TOKEN.toCharArray(),
                                        false,
                                        ProjectFixture.this::localExchange);
                            }
                        },
                        (argv, input, output, environment, timeout) -> {
                            final var result =
                                    command(argv, input.readAllBytes(), environment, timeout);
                            output.write(result.output().getBytes(StandardCharsets.UTF_8));
                            return new HostPrograms.Result(result.exitCode(), "");
                        });
    }

    TestForgeAdmin.Response localExchange(
            String method, String path, byte[] authorization, byte[] body) throws IOException {
        if (rejectInterruptedRequests && Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted transport");
        }
        if (unavailable) {
            throw new IOException("fixture socket unavailable");
        }
        if (path.equals("/version")) {
            assertThat(authorization).as("a presence probe needs no admin credential").isEmpty();
            calls.add(new Call(method, "/api/v1" + path, null));
            return responseBytes(
                    replies.getOrDefault(
                            "GET /api/v1/version", new Reply(200, Map.of("version", "fixture"))));
        }
        assertThat(new String(authorization, StandardCharsets.US_ASCII))
                .as("local administration uses only its admin token")
                .isEqualTo("token " + ADMIN_TOKEN);
        final Call call = new Call(method, "/api/v1" + path, parseBody(body));
        calls.add(call);
        beforeCall.accept(call);
        return responseBytes(response(call));
    }

    TestForgeAdmin.Response centralExchange(
            String method, String raw, byte[] authorization, byte[] body) {
        final URI uri = URI.create("http://fixture" + raw);
        final String path = "/api/v1" + uri.getPath();
        centralCalls.add(method + " " + path);
        assertThat(new String(authorization, StandardCharsets.US_ASCII))
                .as("central alone receives its token")
                .isEqualTo("token " + CENTRAL_TOKEN);
        final Object request = parseBody(body);
        final Reply reply;
        if (method.equals("GET") && path.matches(".*/keys/[0-9]+")) {
            final long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            final Object found =
                    centralKeys.stream()
                            .filter(item -> ((Number) item.get("id")).longValue() == id)
                            .findFirst()
                            .orElse(null);
            reply = new Reply(found == null ? 404 : 200, found);
        } else if (method.equals("GET")) {
            reply =
                    new Reply(
                            200,
                            uri.getQuery().startsWith("page=1&")
                                    ? List.copyOf(centralKeys)
                                    : List.of());
        } else if (method.equals("POST")) {
            final Map<String, Object> made = copy((Map<?, ?>) request);
            made.put("id", 100L + centralKeys.size());
            centralKeys.add(made);
            reply = new Reply(201, made);
        } else {
            if (!holdDeployKey) {
                final long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                centralKeys.removeIf(item -> ((Number) item.get("id")).longValue() == id);
            }
            reply = new Reply(204, null);
        }
        return responseBytes(reply);
    }

    private static TestForgeAdmin.Response responseBytes(Reply reply) {
        return new TestForgeAdmin.Response(
                reply.status,
                reply.body == null
                        ? new byte[0]
                        : Json.write(reply.body).getBytes(StandardCharsets.UTF_8));
    }

    private HostPrograms.Result command(
            List<String> argv, byte[] input, Map<String, String> env, Duration timeout)
            throws IOException {
        commands.add(List.copyOf(argv));
        environments.add(Map.copyOf(env));
        timeouts.add(timeout);
        final HostPrograms.Result custom = program.apply(argv);
        if (custom != null) {
            return custom;
        }
        if (argv.getFirst().equals("/usr/bin/id")) {
            return new HostPrograms.Result(0, uid + "\n");
        }
        if (argv.getFirst().equals("/usr/bin/getent")) {
            return new HostPrograms.Result(0, "forge:x:1234:1234::" + home + ":/bin/false\n");
        }
        if (argv.contains("generate-access-token")) {
            final String scope = argv.get(argv.indexOf("--scopes") + 1);
            return new HostPrograms.Result(
                    0,
                    scope.equals("write:package")
                            ? PUBLISHER_TOKEN
                            : scope.equals("read:package") ? READ_TOKEN : ADMIN_TOKEN);
        }
        if (argv.contains("create") && argv.contains("--username")) {
            users.add(argv.get(argv.indexOf("--username") + 1));
            return new HostPrograms.Result(0, "discarded-random-password");
        }
        if (argv.getFirst().endsWith("/porelo-host") && argv.contains("check-central")) {
            pushInput = new String(input, StandardCharsets.UTF_8);
            return new HostPrograms.Result(pushFailure, "");
        }
        if (argv.getFirst().equals("/usr/bin/ssh-keygen")) {
            if (realKeygen) {
                return new HostPrograms(timeout, 1024 * 1024, Path::of).run(argv, input, env);
            }
            final Path path = Path.of(argv.getLast());
            if (argv.contains("-y")) {
                return new HostPrograms.Result(
                        gitFailure, gitFailure == 0 ? KEY : "private bytes must not escape");
            }
            Files.writeString(path, "fixture private material");
            mode(path, 0600);
            Files.writeString(Path.of(path + ".pub"), KEY + "\n");
            mode(Path.of(path + ".pub"), 0644);
            return new HostPrograms.Result(0, "");
        }
        if (argv.contains("ls-remote")) {
            return new HostPrograms.Result(
                    gitFailure, centralMain == null ? "" : centralMain + "\trefs/heads/main\n");
        }
        if (argv.contains("clone")) {
            if (gitFailure != 0) {
                return new HostPrograms.Result(gitFailure, gitError);
            }
            if (umask >= 0) {
                assertThat(
                                (int)
                                                Files.getAttribute(
                                                        Path.of(argv.getLast()).getParent(),
                                                        "unix:mode")
                                        & 0777)
                        .as(
                                "the mirror scratch directory is usable and private under the caller's umask")
                        .isEqualTo(0700);
                final HostPrograms programs = new HostPrograms(timeout, 65536, Path::of);
                final Path source = files.at("/tmp/source.git");
                final var initialized =
                        programs.run(
                                List.of(
                                        "/usr/bin/sh",
                                        "-c",
                                        "umask 077; exec \"$@\"",
                                        "fixture",
                                        "/usr/bin/git",
                                        "init",
                                        "--bare",
                                        source.toString()),
                                input,
                                env);
                assertThat(initialized.exitCode())
                        .as("the source is a local fixture, with no network")
                        .isZero();
                final List<String> clone = new ArrayList<>(argv);
                clone.set(clone.size() - 2, source.toString());
                return programs.run(clone, input, env);
            }
            Files.createDirectory(Path.of(argv.getLast()));
            mode(Path.of(argv.getLast()), 0700);
            return new HostPrograms.Result(0, "");
        }
        if (argv.getFirst().equals("/usr/bin/git") && argv.contains("rev-list")) {
            return new HostPrograms.Result(gitFailure, centralCommits);
        }
        if (argv.contains("rev-list") && argv.contains("--all")) {
            return new HostPrograms.Result(0, localCommits);
        }
        if (argv.contains("rev-parse")) {
            return new HostPrograms.Result(
                    localMain == null ? 1 : 0, localMain == null ? "" : localMain + "\n");
        }
        if (argv.contains("merge-base")) {
            return new HostPrograms.Result(ancestor, "");
        }
        if (argv.contains("--count")) {
            return new HostPrograms.Result(0, ahead);
        }
        if (argv.contains("test")) {
            return new HostPrograms.Result(repositories.isEmpty() ? 1 : 0, "");
        }
        if (argv.contains("ls")) {
            return new HostPrograms.Result(
                    0,
                    repositories.keySet().stream()
                            .map(name -> name.substring(name.indexOf('/') + 1) + ".git\n")
                            .sorted()
                            .collect(java.util.stream.Collectors.joining()));
        }
        throw new IOException("Unexpected fixture command");
    }

    Answer run(String action, String... args) {
        return runInput(action, "", args);
    }

    HostProject project() {
        return new HostProject(
                files,
                runtime,
                (origin, token) -> TestForgeAdmin.create(token, true, this::centralExchange),
                locking);
    }

    Answer runInput(String action, String input, String... args) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final HostProject host = project();
        final int code =
                host.run(
                        action,
                        List.of(args),
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        new PrintWriter(out),
                        new PrintWriter(err));
        for (String secret :
                List.of(
                        ADMIN_TOKEN,
                        PUBLISHER_TOKEN,
                        READ_TOKEN,
                        CENTRAL_TOKEN,
                        "discarded-random-password")) {
            assertThat(out + err.toString())
                    .as("diagnostics contain no credential")
                    .doesNotContain(secret);
            assertThat(commands.toString())
                    .as("argv contains no credential")
                    .doesNotContain(secret);
            assertThat(environments.toString())
                    .as("environment contains no credential")
                    .doesNotContain(secret);
        }
        return new Answer(code, out.toString(), err.toString());
    }

    void add() {
        assertThat(run("add", "alpha", "alice", "--signed-by", KEY))
                .as("fixture registration succeeds")
                .extracting(Answer::code, Answer::err)
                .containsExactly(0, "");
    }

    Answer off(String token) {
        return runInput(
                "take-off",
                Json.write(ProjectRecords.object("repository", "code", "token", token)),
                "alpha");
    }

    Path manifest() {
        return files.etc.resolve("organization/read/projects/alpha/repositories.json");
    }

    Path keyPath() {
        return manifest().getParent().resolve("code");
    }

    void readKeys() throws IOException {
        directories("/etc/porelo-host/organization/read/projects/alpha");
        write(
                "/etc/porelo-host/organization/read/projects/alpha/repositories.json",
                "[{\"name\":\"code\",\"address\":\""
                        + ADDRESS
                        + "\",\"key_id\":1,\"title\":\"porelo host build-host project alpha\"}]\n");
        write("/etc/porelo-host/organization/read/projects/alpha/code", "fixture private material");
        write("/etc/porelo-host/organization/read/projects/alpha/code.pub", KEY + "\n");
        repositories.put(
                "alpha/code", new LinkedHashMap<>(Map.of("name", "code", "original_url", ADDRESS)));
        centralKeys.add(
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                1L,
                                "title",
                                "porelo host build-host project alpha",
                                "key",
                                KEY,
                                "read_only",
                                true)));
    }

    void write(String absolute, String text) throws IOException {
        Files.writeString(files.at(absolute), text);
        mode(files.at(absolute), 0600);
    }

    void directories(String... paths) throws IOException {
        for (String path : paths) {
            Path next = root;
            for (String part : path.substring(1).split("/")) {
                next = next.resolve(part);
                if (!Files.exists(next)) {
                    Files.createDirectory(next);
                    mode(next, 0700);
                }
            }
        }
    }

    static void mode(Path path, int mode) throws IOException {
        Files.setAttribute(path, "unix:mode", mode);
    }

    static String person(String members, boolean locked, boolean pending) {
        return members
                + "locked="
                + locked
                + "\nssh="
                + KEY
                + "\nexpires=2027-01-01\n"
                + (pending ? "lock=pending\n" : "");
    }

    void noMutation() {
        assertThat(calls)
                .as("a refusal performs no forge mutation")
                .allMatch(call -> call.method.equals("GET"));
    }

    void noCommands() {
        assertThat(commands).as("local refusal precedes any account or token command").isEmpty();
    }

    void clear() {
        calls.clear();
        commands.clear();
        environments.clear();
        timeouts.clear();
    }

    private synchronized Reply response(Call call) {
        final String method = call.method;
        final String raw = call.path.substring("/api/v1".length());
        final String path = raw.split("\\?", 2)[0];
        final String override = method + " " + path;
        if (replies.containsKey(override)) {
            return replies.get(override);
        }
        if (method.equals("GET") && pages.containsKey(path)) {
            final int page = Integer.parseInt(raw.substring(raw.lastIndexOf("page=") + 5));
            final List<List<Object>> list = pages.get(path);
            return new Reply(200, list.get(Math.min(page - 1, list.size() - 1)));
        }
        if (method.equals("GET") && raw.contains("page=") && !raw.endsWith("page=1")) {
            return new Reply(200, List.of());
        }
        final String[] parts = path.substring(1).split("/");
        if (method.equals("POST") && path.equals("/admin/users/porelo-admin/orgs")) {
            final String project = (String) ((Map<?, ?>) call.body).get("username");
            organizations.add(project);
            teams.put(
                    teamId++,
                    new LinkedHashMap<>(
                            Map.of(
                                    "name",
                                    "Owners",
                                    "id",
                                    teamId - 1,
                                    "org",
                                    project,
                                    "members",
                                    new ArrayList<>(List.of("porelo-admin")))));
            return new Reply(201, Map.of("username", project));
        }
        if (parts[0].equals("teams") && method.equals("PUT")) {
            ((List<String>) teams.get(Long.parseLong(parts[1])).get("members")).add(parts[3]);
            return new Reply(204, null);
        }
        if (parts[0].equals("orgs") && parts.length >= 3) {
            final String project = parts[1];
            if (parts[2].equals("teams")) {
                if (method.equals("GET")) {
                    return new Reply(
                            200,
                            teams.values().stream()
                                    .filter(team -> project.equals(team.get("org")))
                                    .sorted(Comparator.comparing(team -> (Long) team.get("id")))
                                    .toList());
                }
                final Map<String, Object> team = copy((Map<?, ?>) call.body);
                team.put("org", project);
                team.put("id", teamId++);
                team.put("members", new ArrayList<String>());
                teams.put((Long) team.get("id"), team);
                return new Reply(201, team);
            }
            if (parts[2].equals("repos")) {
                return new Reply(
                        200,
                        repositories.entrySet().stream()
                                .filter(entry -> entry.getKey().startsWith(project + "/"))
                                .map(Map.Entry::getValue)
                                .toList());
            }
            if (parts[2].equals("actions")) {
                if (parts[3].equals("secrets")) {
                    if (method.equals("GET")) {
                        return new Reply(
                                200,
                                secrets.keySet().stream()
                                        .filter(key -> key.startsWith(project + "/"))
                                        .sorted()
                                        .map(
                                                key ->
                                                        Map.of(
                                                                "name",
                                                                key.substring(
                                                                        project.length() + 1)))
                                        .toList());
                    }
                    if (method.equals("DELETE")) {
                        secrets.remove(project + "/" + parts[4]);
                        return new Reply(204, null);
                    }
                    secrets.put(project + "/" + parts[4], ((Map<?, ?>) call.body).get("data"));
                    return new Reply(204, null);
                }
                variables.put(project + "/" + parts[4], ((Map<?, ?>) call.body).get("value"));
                return new Reply(201, null);
            }
        }
        if (parts[0].equals("orgs")) {
            if (method.equals("GET")) {
                return new Reply(
                        organizations.contains(parts[1]) ? 200 : 404,
                        organizations.contains(parts[1]) ? Map.of("username", parts[1]) : null);
            }
            organizations.remove(parts[1]);
            teams.values().removeIf(team -> parts[1].equals(team.get("org")));
            return new Reply(204, null);
        }
        if (parts[0].equals("users")) {
            return new Reply(
                    users.contains(parts[1]) ? 200 : 404,
                    users.contains(parts[1]) ? Map.of() : null);
        }
        if (parts[0].equals("admin") && method.equals("DELETE")) {
            users.remove(parts[2]);
            return new Reply(204, null);
        }
        if (parts[0].equals("packages")) {
            if (method.equals("GET")) {
                return new Reply(200, packages.getOrDefault(parts[1], List.of()));
            }
            packages.put(parts[1], new ArrayList<>());
            return new Reply(204, null);
        }
        if (path.equals("/repos/migrate")) {
            final Map<?, ?> body = (Map<?, ?>) call.body;
            repositories.put(
                    body.get("repo_owner") + "/" + body.get("repo_name"),
                    new LinkedHashMap<>(
                            Map.of(
                                    "name",
                                    body.get("repo_name"),
                                    "original_url",
                                    body.get("clone_addr"))));
            return new Reply(201, Map.of());
        }
        if (parts[0].equals("repos")) {
            final String name = parts[1] + "/" + parts[2];
            if (parts.length > 3) {
                if (method.equals("GET")) {
                    return new Reply(
                            protectedBranches.containsKey(name) ? 200 : 404,
                            protectedBranches.get(name));
                }
                protectedBranches.put(name, call.body);
                return new Reply(201, call.body);
            }
            if (method.equals("GET")) {
                return new Reply(
                        repositories.containsKey(name) ? 200 : 404, repositories.get(name));
            }
            if (method.equals("DELETE")) {
                repositories.remove(name);
                return new Reply(204, null);
            }
            mergeStyle.put(name, call.body);
            return new Reply(200, call.body);
        }
        throw new AssertionError("Unhandled fixture route " + method + " " + path);
    }

    static Object parseBody(byte[] bytes) {
        return bytes.length == 0
                ? null
                : Json.parseExact(new String(bytes, StandardCharsets.UTF_8));
    }

    static Map<String, Object> copy(Map<?, ?> map) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put((String) key, value));
        return copy;
    }

    private static String key() {
        final byte[] bytes = new byte[51];
        final byte[] header =
                new byte[] {
                    0, 0, 0, 11, 's', 's', 'h', '-', 'e', 'd', '2', '5', '5', '1', '9', 0, 0, 0, 32
                };
        System.arraycopy(header, 0, bytes, 0, 19);
        for (int i = 0; i < 32; i++) {
            bytes[i + 19] = (byte) i;
        }
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public void close() {}
}
