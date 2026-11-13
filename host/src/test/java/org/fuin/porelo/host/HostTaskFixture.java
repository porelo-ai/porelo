package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.organization.gittree.TestForgeAdmin;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.TestPrograms;

/** Synthetic host and in-memory Forgejo; only certificate inspection runs a real child. */
final class HostTaskFixture {
    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final HostReadFixture tree;
    final Path keys;
    final Map<String, Map<Integer, Token>> tokens = new HashMap<>();
    final List<List<String>> calls = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    final Set<String> missing = new HashSet<>();
    final List<byte[]> inputBuffers = new ArrayList<>();
    final List<char[]> adminBuffers = new ArrayList<>();
    final List<String> secrets = new ArrayList<>();
    String alice;
    String bob;
    String judgement = "";
    String judgementError = "";
    String timeoutProgram = "";
    final List<Duration> deadlines = new ArrayList<>();
    final List<Map<String, String>> environments = new ArrayList<>();
    final Map<String, String> accounts = new HashMap<>();

    /** What getent answers for "<database> <key>", where a test says; else the stand-in line. */
    final Map<String, String> getent = new HashMap<>();

    int judgementCode;
    String failIdentity = "";
    String generated = "";
    Runnable inspect = () -> {};
    String checkedKeys = "";
    String checkedKrl = "";
    String checkedInput = "";
    int opened;
    int closed;
    int serial;

    record Token(String name, String scopes, String secret) {}

    HostTaskFixture(Path directory) throws Exception {
        accounts.put("alice", "alice:x:1000:1000::/nonexistent:/bin/sh\n");
        accounts.put("bob", "bob:x:1001:1001::/nonexistent:/bin/sh\n");
        tree = new HostReadFixture(directory.resolve("root"));
        keys = Files.createDirectory(directory.resolve("keys"));
        alice = make("alice");
        bob = make("bob");
        make("task");
        make("other-task");
        tree.put("/etc/porelo-host/forge.conf", "account=forge\nhost=workshop\n");
        tree.person("alice", "member=alpha developer\n", "");
        tree.person("bob", "member=alpha developer\n", "");
        final Path bus = tree.put("/run/user/900/bus", "");
        tree.overrides.put(bus, Map.of("mode", 0140600));
        tree.put("/etc/ssh/porelo-revoked.krl", "fixture KRL");
        organization(alice, false, false);
    }

    String fingerprint(String name) throws Exception {
        final String encoded = Files.readString(keys.resolve(name + ".pub")).strip().split(" ")[1];
        return "SHA256:"
                + java.util.Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(java.util.Base64.getDecoder().decode(encoded)));
    }

    String make(String name) throws Exception {
        process(
                List.of(
                        "/usr/bin/ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-C",
                        "",
                        "-f",
                        keys.resolve(name).toString()));
        return Files.readString(keys.resolve(name + ".pub")).strip();
    }

    void organization(String key, boolean revoked, boolean absent) throws Exception {
        tree.put(
                "/etc/porelo-host/organization/applied.json",
                Json.write(
                        Map.of(
                                "commit",
                                "a".repeat(40),
                                "projects",
                                List.of(),
                                "persons",
                                absent
                                        ? List.of()
                                        : List.of(
                                                Map.of(
                                                        "login", "alice", "ssh", key, "revoked",
                                                        revoked)),
                                "revoked",
                                revoked ? List.of("alice") : List.of())));
    }

    String certificate() throws Exception {
        return certificate(
                "alice",
                "task",
                List.of("task:alpha:t1"),
                "20261030000000Z:20261031000000Z",
                false,
                "porelo task t1 by alice");
    }

    String certificate(
            String signer,
            String key,
            List<String> principals,
            String valid,
            boolean host,
            String id)
            throws Exception {
        final List<String> argv =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/ssh-keygen",
                                "-q",
                                "-s",
                                keys.resolve(signer).toString(),
                                "-I",
                                id,
                                "-n",
                                String.join(",", principals),
                                "-V",
                                valid));
        if (host) argv.add("-h");
        argv.add(keys.resolve(key + ".pub").toString());
        process(argv);
        return Files.readString(keys.resolve(key + "-cert.pub"));
    }

    private static void process(List<String> argv) throws Exception {
        final Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("bounded fixture key generation")
                    .isTrue();
            assertThat(process.exitValue()).as("fixture ssh-keygen succeeds").isZero();
        } finally {
            process.destroyForcibly();
            process.getInputStream().close();
            process.getOutputStream().close();
        }
    }

    HostReadFixture.Answer run(String input, String... args) {
        return run(HostReadFixture.SUDO, input, args);
    }

    HostReadFixture.Answer run(Map<String, String> environment, String input, String... args) {
        final HostFiles files = tree.files();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                        files,
                        this::command,
                        name -> {
                            final String file = Path.of(name).getFileName().toString();
                            if (missing.contains(file))
                                throw new java.io.UncheckedIOException(
                                        new org.fuin.porelo.wire.SystemProgram.Missing(
                                                file, List.of()));
                            return Path.of(name);
                        },
                        (socket, token) -> {
                            opened++;
                            adminBuffers.add(token);
                            return TestForgeAdmin.create(
                                    token,
                                    false,
                                    (method, path, authorization, body) -> response(method, path),
                                    () -> closed++);
                        });
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        args,
                        environment,
                        () -> 0,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files,
                        env -> HostPerson.from(env, login -> accounts.getOrDefault(login, "")),
                        new HostSweep(files),
                        runtime,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)),
                        CLOCK);
        return new HostReadFixture.Answer(code, out.toString(), err.toString());
    }

    private HostPrograms.Result command(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        inspect.run();
        calls.add(List.copyOf(argv));
        deadlines.add(timeout);
        environments.add(Map.copyOf(environment));
        if (Path.of(argv.getFirst()).getFileName().toString().equals(timeoutProgram))
            throw new IOException(
                    "private timeout details", new java.util.concurrent.TimeoutException());
        assertThat(environment.keySet())
                .as("no invoking account environment reaches a child")
                .isSubsetOf("PATH", "LANG", "LC_ALL");
        final String name = Path.of(argv.getFirst()).getFileName().toString();
        if (name.equals("getent")
                && getent.containsKey(String.join(" ", argv.subList(1, argv.size()))))
            return new HostPrograms.Result(
                    0, getent.get(String.join(" ", argv.subList(1, argv.size()))));
        if (name.equals("getent"))
            return new HostPrograms.Result(
                    0, argv.getLast() + ":x:900:900::/nonexistent:/bin/sh\n");
        if (name.equals("env")) {
            return new HostPrograms(timeout, 65536, TestPrograms::find)
                    .run(argv, input, environment);
        }
        if (name.equals("systemd-run")) {
            inputBuffers.add(input);
            final Map<String, String> staged = new HashMap<>();
            for (String word : argv)
                if (word.startsWith("LoadCredential=")) {
                    final String[] fields = word.substring(15).split(":", 2);
                    staged.put(fields[0], fields[1]);
                }
            checkedKeys = Files.readString(Path.of(staged.get("keys")));
            checkedKrl = Files.readString(Path.of(staged.get("revoked.krl")));
            checkedInput = new String(input, StandardCharsets.US_ASCII);
            if (!judgementError.isEmpty()) {
                final byte[] diagnostic = judgementError.getBytes(StandardCharsets.UTF_8);
                try {
                    return new HostPrograms(Duration.ofSeconds(10), 65536, TestPrograms::find)
                            .run(
                                    List.of("sh", "-c", "cat >&2; exit 65"),
                                    diagnostic,
                                    Map.of("PATH", "/usr/bin:/bin"));
                } finally {
                    java.util.Arrays.fill(diagnostic, (byte) 0);
                }
            }
            if (!judgement.isEmpty() || judgementCode != 0)
                return new HostPrograms.Result(judgementCode, judgement);
            final Path cert = Files.createTempFile(keys, "inspect-", ".pub");
            try {
                Files.write(cert, input);
                final HostPrograms.Result shown =
                        new HostPrograms(Duration.ofSeconds(10), 65536, TestPrograms::find)
                                .run(
                                        List.of(
                                                "/usr/bin/env",
                                                "TZ=UTC",
                                                "/usr/bin/ssh-keygen",
                                                "-L",
                                                "-f",
                                                cert.toString()));
                final HostCertificate fields = HostCertificate.read(shown.output());
                // The real isolated checker is covered in app; here its refusal and response are
                // controlled.
                if (!fields.keyId()
                        .equals(
                                "porelo task "
                                        + argv.get(argv.indexOf("--task") + 1)
                                        + " by alice")) return new HostPrograms.Result(65, "");
                return new HostPrograms.Result(
                        0,
                        Json.write(
                                Map.of(
                                        "fingerprint",
                                        fields.fingerprint(),
                                        "signed_by",
                                        fields.signedBy(),
                                        "project",
                                        fields.principals().getFirst().split(":")[1])));
            } finally {
                Files.delete(cert);
            }
        }
        if (argv.contains("generate-access-token")) {
            final String user = argv.get(argv.indexOf("--username") + 1);
            final String tokenName = argv.get(argv.indexOf("--token-name") + 1);
            final String secret =
                    generated.isEmpty() ? String.format("%040x", ++serial) : generated;
            add(user, tokenName, argv.get(argv.indexOf("--scopes") + 1), secret);
            return new HostPrograms.Result(0, secret + "\n");
        }
        throw new AssertionError("Unexpected host command " + name);
    }

    void add(String user, String name) {
        add(user, name, "fixture", String.format("%040x", ++serial));
    }

    private void add(String user, String name, String scopes, String secret) {
        secrets.add(secret);
        tokens.computeIfAbsent(user, ignored -> new LinkedHashMap<>())
                .put(++serial, new Token(name, scopes, secret));
    }

    List<String> names(String user) {
        return tokens.getOrDefault(user, Map.of()).values().stream()
                .map(Token::name)
                .sorted()
                .toList();
    }

    Path record(String task) {
        return tree.path("/var/lib/porelo-host/tasks/alice/" + task + ".json");
    }

    private TestForgeAdmin.Response response(String method, String path) {
        requests.add(method + " " + path);
        final String route = path.substring("/admin/users/".length());
        final String login = route.split("/")[0];
        if (login.equals(failIdentity)) return new TestForgeAdmin.Response(500, new byte[0]);
        final var named = tokens.computeIfAbsent(login, ignored -> new LinkedHashMap<>());
        if (method.equals("DELETE")) {
            named.remove(Integer.parseInt(route.substring(route.lastIndexOf('/') + 1)));
            return new TestForgeAdmin.Response(204, new byte[0]);
        }
        final int page = Integer.parseInt(route.substring(route.indexOf("&page=") + 6));
        return new TestForgeAdmin.Response(
                200,
                Json.write(
                                named.entrySet().stream()
                                        .skip((long) (page - 1) * 50)
                                        .limit(50)
                                        .map(
                                                entry ->
                                                        Map.of(
                                                                "id",
                                                                entry.getKey(),
                                                                "name",
                                                                entry.getValue().name()))
                                        .toList())
                        .getBytes(StandardCharsets.UTF_8));
    }

    Map<Path, String> snapshot() throws IOException {
        final Map<Path, String> result = new HashMap<>();
        try (var paths = Files.walk(tree.root)) {
            for (Path path : paths.toList())
                result.put(path, Files.isRegularFile(path) ? Files.readString(path) : "directory");
        }
        return result;
    }

    void noSecrets(HostReadFixture.Answer answer) throws Exception {
        for (String secret : secrets) {
            assertThat(answer.err()).as("token is never diagnostic").doesNotContain(secret);
            assertThat(calls.toString()).as("token is never argv").doesNotContain(secret);
            for (String content : snapshot().values())
                assertThat(content)
                        .as("tokens stay off the host filesystem")
                        .doesNotContain(secret);
        }
        assertThat(adminBuffers).allSatisfy(bytes -> assertThat(bytes).containsOnly('\0'));
        assertThat(opened).as("each temporary administrator client closes").isEqualTo(closed);
        assertThat(names("porelo-admin"))
                .as("temporary admin tokens are deleted before answering")
                .isEmpty();
    }
}
