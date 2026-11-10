package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.app.PoreloCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The judge verb over process pipes, with the real installed verifier library and temporary git.
 */
class HostPushIntegrationTest {
    @TempDir Path directory;
    private static final String REPOSITORY = "/var/lib/gitea/git/repositories/alpha/app.git";
    private static final String QUARANTINE = REPOSITORY + "/objects/incoming-test";

    /** Replaces only root/activation and systemd, keeping the actual verb and signature worker. */
    public static final class Child {
        /** Serves one pipe request against the synthetic installed tree. */
        public static void main(String[] args) throws Exception {
            final Path root = Path.of(args[0]);
            final long uid = ((Number) Files.getAttribute(root, "unix:uid")).longValue();
            final var files =
                    new HostFiles(
                            root,
                            path -> {
                                final var attributes =
                                        new HashMap<>(
                                                Files.readAttributes(
                                                        path,
                                                        "unix:uid,mode",
                                                        path.toString().startsWith("/proc/self/fd/")
                                                                ? new LinkOption[0]
                                                                : new LinkOption[] {
                                                                    LinkOption.NOFOLLOW_LINKS
                                                                }));
                                attributes.put("uid", 0);
                                if (!path.startsWith(root)
                                        && !path.toString().startsWith("/proc/self/fd/"))
                                    attributes.put("mode", 040755);
                                return attributes;
                            });
            final var runtime =
                    new ProjectRuntime(
                            files,
                            (argv, input, environment, timeout) -> {
                                if (argv.equals(List.of("/usr/bin/getent", "passwd", "alice")))
                                    return new HostPrograms.Result(
                                            0, "alice:x:1001:1001::/home/alice:/bin/sh\n");
                                if (argv.getFirst().equals("/usr/bin/setfacl")) {
                                    assertThat(argv.subList(0, 3))
                                            .as("only the applied member gets a read ACL")
                                            .containsExactly(
                                                    "/usr/bin/setfacl",
                                                    "--modify",
                                                    "user:1001:r--");
                                    Files.setAttribute(Path.of(argv.getLast()), "unix:mode", 0640);
                                    return new HostPrograms.Result(0, "");
                                }
                                if (argv.equals(List.of("/usr/bin/getent", "passwd", "forge")))
                                    return new HostPrograms.Result(
                                            0,
                                            "forge:x:"
                                                    + uid
                                                    + ":"
                                                    + uid
                                                    + "::/home/forge:/bin/sh\n");
                                if (!argv.getFirst().equals("/usr/bin/systemd-run"))
                                    throw new AssertionError(
                                            "Only the confined verifier can be launched");
                                final Map<String, String> credentials = new HashMap<>();
                                final Map<String, String> env =
                                        new HashMap<>(HostPrograms.ENVIRONMENT);
                                env.put("HOME", root.resolve("scratch").toString());
                                env.put("TMPDIR", root.resolve("scratch").toString());
                                for (String item : argv) {
                                    if (item.startsWith("LoadCredential=")) {
                                        final String[] parts = item.substring(15).split(":", 2);
                                        credentials.put(parts[0], parts[1]);
                                    }
                                    if (item.startsWith("Environment=GIT_")) {
                                        final String[] parts = item.substring(12).split("=", 2);
                                        env.put(parts[0], parts[1]);
                                    }
                                }
                                final int porelo = argv.indexOf("/usr/bin/porelo");
                                final var command =
                                        new ArrayList<>(
                                                List.of(
                                                        Path.of(
                                                                        System.getProperty(
                                                                                "java.home"),
                                                                        "bin",
                                                                        "java")
                                                                .toString(),
                                                        "-XX:-UsePerfData",
                                                        "-Djava.io.tmpdir="
                                                                + root.resolve("scratch"),
                                                        "-Duser.home=" + root.resolve("no-home"),
                                                        "--enable-native-access=ALL-UNNAMED",
                                                        "-cp",
                                                        System.getProperty("java.class.path"),
                                                        PoreloCli.class.getName()));
                                for (String argument : argv.subList(porelo + 1, argv.size()))
                                    command.add(
                                            argument.startsWith("/run/credentials/")
                                                    ? credentials.get(
                                                            Path.of(argument)
                                                                    .getFileName()
                                                                    .toString())
                                                    : argument);
                                return new HostPrograms(timeout, 1024 * 1024, Path::of)
                                        .run(command, input, env);
                            },
                            name ->
                                    Path.of(name).isAbsolute()
                                            ? Path.of(name)
                                            : Path.of("/usr/bin", name),
                            org.fuin.porelo.organization.gittree.HttpForgeAdmin::new);
            final int code =
                    PoreloHost.run(
                            new String[] {"judge-push"},
                            Map.of(),
                            () -> 0,
                            new PrintWriter(System.out, true),
                            new PrintWriter(System.err, true),
                            files,
                            environment -> {
                                throw new AssertionError("A socket hook acts for no sudo person");
                            },
                            new HostSweep(files),
                            runtime,
                            System.in,
                            Clock.systemUTC(),
                            judge -> judge.serve(System.in, System.out, uid));
            System.exit(code);
        }
    }

    @Test
    void theVerbAllowsASignedPushAndRefusesAnUnsignedPushWithTheHooksExactAnswers()
            throws Exception {
        // The host's actual signature judgement must reach the reader without invented provenance.
        assertThat(directory.toAbsolutePath())
                .as("the fixture must use the JVM's configured scratch directory")
                .startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());
        final Path root = directory.resolve("root");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("scratch"));
        final Path repository =
                root.resolve(
                        "home/forge/.local/share/containers/storage/volumes/forgejo-data/_data/git/repositories/alpha/app.git");
        Files.createDirectories(repository.getParent());
        process(
                List.of("/usr/bin/git", "init", "--bare", "-q", repository.toString()),
                "",
                Map.of());
        Files.createDirectory(repository.resolve("objects/incoming-test"));
        final Path key = directory.resolve("signing");
        process(
                List.of(
                        "/usr/bin/ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-f",
                        key.toString()),
                "",
                Map.of());
        final String[] publicParts = Files.readString(Path.of(key + ".pub")).strip().split(" ");
        final String publicKey = publicParts[0] + " " + publicParts[1];
        put(root, "/etc/porelo-host/forge.conf", "account=forge\n");
        put(
                root,
                "/etc/porelo-host/projects/alpha.conf",
                "admin=alice\nsigned_by=" + publicKey + "\npublisher=porelo-publisher-alpha\n");
        put(root, "/etc/subuid", "forge:100000:65536\n");
        put(
                root,
                "/etc/porelo-host/signers/alpha.allowed",
                "alice namespaces=\"git\" " + publicKey + "\n");
        put(root, "/etc/porelo-host/signers/alpha.certifiers", "");
        put(root, "/etc/ssh/porelo-revoked.krl", "");
        put(
                root,
                "/etc/porelo-host/organization/applied.json",
                "{\"revoked\":[],\"persons\":[{\"login\":\"alice\",\"revoked\":false,\"expires\":null},{\"login\":\"bob\",\"revoked\":false,\"expires\":null}],\"projects\":[{\"project\":\"alpha\",\"members\":[{\"login\":\"alice\",\"locked\":false}]}]}");
        put(
                root,
                "/etc/porelo-host/people/alice.conf",
                "member=alpha developer\nlocked=false\nssh=" + publicKey + "\nexpires=\n");
        Files.delete(root.resolve("etc/ssh/porelo-revoked.krl"));
        process(
                List.of(
                        "/usr/bin/ssh-keygen",
                        "-k",
                        "-f",
                        root.resolve("etc/ssh/porelo-revoked.krl").toString()),
                "",
                Map.of());
        final String tree =
                process(
                                List.of(
                                        "/usr/bin/git",
                                        "--git-dir=" + repository,
                                        "hash-object",
                                        "-t",
                                        "tree",
                                        "-w",
                                        "--stdin"),
                                "",
                                Map.of())
                        .strip();
        final var gitEnvironment =
                Map.of(
                        "GIT_AUTHOR_NAME",
                        "Alice",
                        "GIT_AUTHOR_EMAIL",
                        "alice@example.org",
                        "GIT_COMMITTER_NAME",
                        "Alice",
                        "GIT_COMMITTER_EMAIL",
                        "alice@example.org");
        final String signed =
                process(
                                List.of(
                                        "/usr/bin/git",
                                        "--git-dir=" + repository,
                                        "-c",
                                        "gpg.format=ssh",
                                        "-c",
                                        "user.signingkey=" + key,
                                        "commit-tree",
                                        "-S",
                                        tree,
                                        "-m",
                                        "signed"),
                                "",
                                gitEnvironment)
                        .strip();
        final String unsigned =
                process(
                                List.of(
                                        "/usr/bin/git",
                                        "--git-dir=" + repository,
                                        "commit-tree",
                                        tree,
                                        "-p",
                                        signed,
                                        "-m",
                                        "unsigned"),
                                "",
                                gitEnvironment)
                        .strip();
        assertThat(serve(root, signed))
                .as("the hook must allow and record the real SSH signature")
                .isEqualTo(http(200, "porelo: 1 new commit(s) judged and recorded\n"));
        assertThat(serve(root, unsigned))
                .as("the same verb must refuse real unsigned commit bytes")
                .isEqualTo(http(403, "porelo: " + unsigned + " carries no signature\n"));
        final var ledger =
                Files.readAllLines(root.resolve("var/lib/porelo-host/arrivals/alpha/app.jsonl"));
        assertThat(ledger)
                .as("the refused push leaves no arrival, even for its signed ancestor")
                .hasSize(1);
        assertThat(HostJson.object(HostJson.parse(ledger.getFirst())))
                .as("the accepted push records the actual commit")
                .containsEntry("commit", signed);
        final org.fuin.porelo.wire.RootOwnedFile.Attributes attributes =
                path -> {
                    final var values =
                            new HashMap<>(
                                    Files.readAttributes(
                                            path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
                    values.put("uid", 0);
                    if (!path.startsWith(root)) values.put("mode", 0040755);
                    return values;
                };
        assertThat(
                        org.fuin.porelo.app.HostArrivalLedger.read(
                                root.resolve("etc/porelo-host/organization/arrivals"),
                                attributes,
                                "alpha",
                                "app"))
                .as("the checked reader returns exactly what the real verifier judged")
                .isEqualTo(
                        Map.of(
                                "state",
                                "read",
                                "entries",
                                List.of(HostJson.parse(ledger.getFirst()))));
        assertThat(root.resolve("no-home"))
                .as("the real verifier needs no person's home")
                .doesNotExist();
    }

    private String serve(Path root, String commit) throws Exception {
        final String body =
                "repository "
                        + REPOSITORY
                        + "\nquarantine "
                        + QUARANTINE
                        + "\nobjects "
                        + QUARANTINE
                        + "\nalternates "
                        + REPOSITORY
                        + "/objects\npusher alice\n\n"
                        + "0".repeat(40)
                        + " "
                        + commit
                        + " refs/heads/main\n";
        return process(
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-XX:-UsePerfData",
                        "-Djava.io.tmpdir=" + directory,
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Child.class.getName(),
                        root.toString()),
                "POST /verify HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: "
                        + body.getBytes(StandardCharsets.UTF_8).length
                        + "\r\n\r\n"
                        + body,
                Map.of());
    }

    private String process(List<String> arguments, String input, Map<String, String> extra)
            throws Exception {
        final var builder = new ProcessBuilder(arguments).directory(directory.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(
                        Map.of(
                                "PATH",
                                "/usr/bin:/bin",
                                "LANG",
                                "C.UTF-8",
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "TMPDIR",
                                directory.toString()));
        builder.environment().putAll(extra);
        final Process child = builder.start();
        try {
            try (var pipe = child.getOutputStream()) {
                pipe.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(child.waitFor(25, TimeUnit.SECONDS))
                    .as("the pipe request must complete within its deadline")
                    .isTrue();
            final String error =
                    new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).as(error).isZero();
            return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String http(int status, String body) {
        return "HTTP/1.1 "
                + status
                + (status == 200 ? " OK" : " Forbidden")
                + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n"
                + body;
    }

    private static void put(Path root, String name, String value) throws Exception {
        final Path path = root.resolve(name.substring(1));
        Files.createDirectories(path.getParent());
        for (Path parent = path.getParent();
                parent != null && parent.startsWith(root);
                parent = parent.getParent())
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(path, value);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
