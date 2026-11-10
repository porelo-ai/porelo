package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.ProjectFixture.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.fuin.porelo.organization.gittree.TestForgeAdmin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostProjectCentralCheckTest {
    @TempDir Path root;
    ProjectFixture host;
    Path localPath;
    Path centralPath;
    String original = ADDRESS;
    String token = ADMIN_TOKEN;
    int tokenStatus;
    Path adminFile;
    InputStream centralInput =
            new ByteArrayInputStream((CENTRAL_TOKEN + "\n").getBytes(StandardCharsets.UTF_8));
    boolean unendingPages;
    boolean invalidPage;
    int apiCalls;
    final List<List<String>> commands = new ArrayList<>();
    final List<Map<String, String>> environments = new ArrayList<>();
    final List<byte[]> inputs = new ArrayList<>();
    String first;
    final List<Duration> timeouts = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
        host.write("/etc/porelo-host/forge.conf", "account=forge\ngit_port=3012\n");
        host.directories("/etc/ssl/certs");
        host.write("/etc/ssl/certs/ca-certificates.crt", "fixture-ca");
        centralPath = root.resolve("central.git");
        localPath = root.resolve("local.git");
        Files.createDirectory(centralPath);
        ProjectTestGit.git(centralPath, "init", "--bare", "-q", "-b", "main");
        first = commit(centralPath, "refs/heads/main", null);
        ProjectTestGit.git(
                root, "clone", "-q", "--bare", centralPath.toString(), localPath.toString());
    }

    static String commit(Path path, String ref, String parent) throws Exception {
        // The empty tree is the same in every fixture repository.
        ProjectTestGit.git(path, "read-tree", "--empty");
        final String tree = ProjectTestGit.git(path, "write-tree");
        final List<String> args = new ArrayList<>(List.of("commit-tree", tree, "-m", ref));
        if (parent != null) {
            args.addAll(List.of("-p", parent));
        }
        final String made = ProjectTestGit.git(path, args.toArray(String[]::new));
        ProjectTestGit.git(path, "update-ref", ref, made);
        return made;
    }

    HostPrograms.Result run(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        commands.add(List.copyOf(argv));
        environments.add(Map.copyOf(environment));
        inputs.add(input.clone());
        timeouts.add(timeout);
        assertThat(timeout.toSeconds()).isLessThanOrEqualTo(120);
        if (argv.getFirst().equals("/usr/bin/getent")) {
            return new HostPrograms.Result(0, "forge:x:1234:1234::/home/forge:/bin/false\n");
        }
        if (argv.contains("/usr/bin/cat")) {
            assertThat(argv)
                    .containsSubsequence(
                            "/usr/sbin/runuser",
                            "-u",
                            "forge",
                            "--",
                            "/usr/bin/env",
                            "-C",
                            "/",
                            "HOME=/home/forge",
                            "/usr/bin/cat",
                            "--");
            if (adminFile != null) {
                return new HostPrograms(timeout, 1024 * 1024, Path::of)
                        .run(
                                List.of("/usr/bin/cat", "--", adminFile.toString()),
                                input,
                                environment);
            }
            return new HostPrograms.Result(tokenStatus, token);
        }
        final var mapped = new ArrayList<String>();
        for (String arg : argv) {
            mapped.add(
                    arg.equals(ADDRESS)
                            ? centralPath.toString()
                            : arg.equals("http://127.0.0.1:3012/alpha/code.git")
                                    ? localPath.toString()
                                    : arg);
        }
        return new HostPrograms(timeout, 1024 * 1024, Path::of).run(mapped, input, environment);
    }

    Answer check(Map<String, String> environment, String... arguments) {
        if (centralInput instanceof ByteArrayInputStream) {
            try {
                centralInput.reset();
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
        }
        final var out = new StringWriter();
        final var err = new StringWriter();
        final var runtime =
                new ProjectRuntime(
                        host.files,
                        this::run,
                        Path::of,
                        path -> true,
                        (socket, credential) -> {
                            assertThat(socket)
                                    .isEqualTo(host.files.at("/run/porelo-forge/git.sock"));
                            assertThat(new String(credential)).isEqualTo(ADMIN_TOKEN);
                            return TestForgeAdmin.create(
                                    credential,
                                    false,
                                    (method, path, auth, body) -> {
                                        apiCalls++;
                                        assertThat(method).isEqualTo("GET");
                                        assertThat(path)
                                                .startsWith("/orgs/alpha/repos?limit=50&page=");
                                        assertThat(auth)
                                                .isEqualTo(
                                                        ("token " + ADMIN_TOKEN)
                                                                .getBytes(
                                                                        StandardCharsets.US_ASCII));
                                        return new TestForgeAdmin.Response(
                                                200,
                                                (invalidPage
                                                                ? "{}"
                                                                : path.endsWith("page=1")
                                                                                || unendingPages
                                                                        ? "[{\"name\":\"code\",\"original_url\":\""
                                                                                + original
                                                                                + "\"}]"
                                                                        : "[]")
                                                        .getBytes(StandardCharsets.UTF_8));
                                    });
                        });
        int code = 0;
        try {
            new ProjectCentralCheck(host.files, runtime)
                    .check(List.of(arguments), environment, centralInput, new PrintWriter(err));
        } catch (HostRefusal ex) {
            code = ex.exitCode();
            err.write(ex.getMessage());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        for (String secret :
                List.of(
                        ADMIN_TOKEN,
                        CENTRAL_TOKEN,
                        Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + ADMIN_TOKEN)
                                                .getBytes(StandardCharsets.UTF_8)),
                        Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + CENTRAL_TOKEN)
                                                .getBytes(StandardCharsets.UTF_8)))) {
            assertThat(out + err.toString()).doesNotContain(secret);
            assertThat(commands.toString()).doesNotContain(secret);
        }
        assertThat(inputs).allSatisfy(bytes -> assertThat(bytes).isEmpty());
        try (var paths = Files.list(host.files.at("/tmp"))) {
            assertThat(paths.toList()).isEmpty();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        return new Answer(code, out.toString(), err.toString());
    }

    @Test
    void allRefsMustBeAtTheMigratedAddressAndUseCentralsAuthority() throws Exception {
        final String pending = commit(localPath, "refs/heads/task/pending", first);
        final String before = ProjectTestGit.git(localPath, "show-ref");
        final var refused = check(Map.of(), "alpha", "code");
        assertThat(refused.code()).as(refused.err()).isEqualTo(65);
        assertThat(refused.err()).contains("code", "unpushed");
        assertThat(ProjectTestGit.git(localPath, "show-ref")).isEqualTo(before);
        ProjectTestGit.git(
                centralPath,
                "fetch",
                "-q",
                localPath.toString(),
                "refs/heads/task/pending:refs/tags/kept");
        assertThat(ProjectTestGit.git(centralPath, "rev-parse", "refs/tags/kept"))
                .isEqualTo(pending);
        final String centralBefore = ProjectTestGit.git(centralPath, "show-ref");
        final var okay = check(Map.of(), "alpha", "code");
        assertThat(ProjectTestGit.git(centralPath, "show-ref")).isEqualTo(centralBefore);
        assertThat(ProjectTestGit.git(localPath, "show-ref")).isEqualTo(before);
        assertThat(okay.code()).as(okay.err()).isZero();
        final String systemCa = host.files.at("/etc/ssl/certs/ca-certificates.crt").toString();
        assertThat(environments.stream().filter(env -> env.containsKey("GIT_SSL_CAINFO")))
                .allSatisfy(env -> assertThat(env.get("GIT_SSL_CAINFO")).isEqualTo(systemCa));
        host.write("/etc/porelo-host/forge/central-ca.pem", "custom fixture-ca");
        final String customCa = host.files.etc.resolve("forge/central-ca.pem").toString();
        host.write("/etc/porelo-host/forge/central-ca.conf", customCa + "\n");
        environments.clear();
        assertThat(check(Map.of(), "alpha", "code").code()).isZero();
        assertThat(environments.stream().filter(env -> env.containsKey("GIT_SSL_CAINFO")))
                .allSatisfy(env -> assertThat(env.get("GIT_SSL_CAINFO")).isEqualTo(customCa));
        original = "";
        final var missing = check(Map.of(), "alpha", "code");
        assertThat(missing.code()).isEqualTo(69);
        assertThat(missing.err()).contains("code", "original_url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdin-symlink", "unreadable"})
    void aFailedTokenReadNeverGetsTheCentralCredentialOrStartsGit(String defect) throws Exception {
        centralInput =
                new InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError("central stdin must stay unread");
                    }
                };
        if (defect.equals("stdin-symlink")) {
            adminFile = root.resolve("api.token");
            Files.createSymbolicLink(adminFile, Path.of("/dev/stdin"));
        }
        tokenStatus = defect.equals("unreadable") ? 1 : 0;
        token = "";
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).isEqualTo(66);
        assertThat(answer.err()).contains("api.token").doesNotContain("Token for");
        assertThat(apiCalls).isZero();
        assertThat(commands).noneMatch(argv -> argv.getFirst().equals("/usr/bin/git"));
    }

    @Test
    void theApiUsesTheGateSocketAndGitTheLiteralLoopbackGate() {
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(apiCalls).isEqualTo(2);
        assertThat(commands)
                .anySatisfy(
                        argv ->
                                assertThat(argv)
                                        .contains(
                                                "clone",
                                                "--mirror",
                                                "http://127.0.0.1:3012/alpha/code.git"));
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains("clone")) {
                assertThat(environments.get(i))
                        .containsEntry("GIT_CONFIG_VALUE_1", "false")
                        .doesNotContainKey("GIT_SSL_CAINFO");
            }
            if (commands.get(i).contains("fetch")) {
                assertThat(environments.get(i))
                        .containsEntry("GIT_CONFIG_VALUE_1", "false")
                        .containsKey("GIT_SSL_CAINFO");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUDO_UID", "SUDO_USER"})
    void sudoNeverBypassesMembershipToReadPrivateRepositories(String key) {
        assertThat(
                        check(Map.of(key, key.equals("SUDO_USER") ? "alice" : "0"), "alpha", "code")
                                .code())
                .isEqualTo(77);
        assertThat(commands).isEmpty();
        assertThat(apiCalls).isZero();
    }

    @Test
    void repeatedOrMalformedPagesNeverStartGit() {
        unendingPages = true;
        assertThat(check(Map.of(), "alpha", "code").code()).isEqualTo(69);
        assertThat(commands).noneMatch(argv -> argv.getFirst().equals("/usr/bin/git"));
        unendingPages = false;
        invalidPage = true;
        assertThat(check(Map.of(), "alpha", "code").code()).isEqualTo(69);
    }

    @Test
    void anUnsafeCentralAuthorityConfigurationIsAFileRefusalBeforeCredentials() throws Exception {
        host.write(
                "/etc/porelo-host/forge/central-ca.conf",
                host.files.at("/etc/ssl/certs/ca-certificates.crt") + "\n");
        ProjectFixture.mode(host.files.etc.resolve("forge/central-ca.conf"), 0666);
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).isEqualTo(66);
        assertThat(answer.err()).contains("central-ca.conf").doesNotContain("Token");
        assertThat(commands).isEmpty();
    }

    @Test
    void unknownOrDuplicateRepositoriesAreRefused() {
        assertThat(check(Map.of(), "alpha", "code", "code").code()).isEqualTo(64);
        assertThat(commands).isEmpty();
        assertThat(check(Map.of(), "alpha", "absent").code()).isEqualTo(69);
        assertThat(commands).noneMatch(argv -> argv.getFirst().equals("/usr/bin/git"));
    }

    @Test
    void aRootOwnedAuthorityCanBeReachedThroughASystemSymlink() throws Exception {
        final Path ca = host.files.at("/etc/ssl/certs/ca-certificates.crt");
        final Path actual = ca.resolveSibling("actual.pem");
        Files.move(ca, actual);
        Files.createSymbolicLink(ca, Path.of("actual.pem"));
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).as(answer.err()).isZero();
    }

    @Test
    void anAuthorityPathCanContainParentComponents() throws Exception {
        host.write(
                "/etc/porelo-host/forge/central-ca.conf",
                host.files.at("/etc/ssl") + "/../ssl/certs/ca-certificates.crt\n");
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).as(answer.err()).isZero();
    }

    @Test
    void slowCentralTransfersHaveNoDeadlineOfTheirOwn() {
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).as(answer.err()).isZero();
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains("clone") || commands.get(i).contains("fetch")) {
                assertThat(timeouts.get(i))
                        .as("transfer has the caller's lifetime")
                        .isEqualTo(Duration.ZERO);
            }
        }
    }

    @Test
    void manyUnpushedCommitsRemainAWorkRefusal() throws Exception {
        final var history = new StringBuilder();
        for (int value = 0; value < 27000; value++) {
            final String message = "pending " + value;
            history.append(
                            "commit refs/heads/task/pending\ncommitter Test <test@example.org> 1700000000 +0000\ndata ")
                    .append(message.length())
                    .append('\n')
                    .append(message)
                    .append('\n');
            if (value == 0) {
                history.append("from ").append(first).append('\n');
            }
            history.append('\n');
        }
        final var imported =
                new HostPrograms(Duration.ofSeconds(60), 1024 * 1024, Path::of)
                        .run(
                                List.of(
                                        "/usr/bin/git",
                                        "-C",
                                        localPath.toString(),
                                        "fast-import",
                                        "--quiet"),
                                history.toString().getBytes(StandardCharsets.US_ASCII),
                                Map.of(
                                        "PATH",
                                        "/usr/bin:/bin",
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1"));
        assertThat(imported.exitCode()).isZero();
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("unpushed commits remain");
    }

    @Test
    void checkCentralKeepsThePushDiagnosticPrefix() {
        final var answer = host.run("check-central", "alpha");
        assertThat(answer.code()).isEqualTo(64);
        assertThat(answer.err()).startsWith("porelo-host-push: ");
    }

    @Test
    void anEmptyTokenLineStillPrintsExactlyOneNewline() {
        centralInput = new ByteArrayInputStream(new byte[] {'\n'});
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).isEqualTo(75);
        assertThat(answer.err())
                .startsWith("Token for the central forge (not shown): \nno token was given");
    }

    @Test
    void anAuthorityBelowAnUntrustedDirectoryIsRefusedBeforeReadingCredentials() throws Exception {
        final Path ca = host.files.at("/etc/ssl/certs/ca-certificates.crt");
        final Path unsafe = host.files.at("/etc/unsafe");
        Files.createDirectory(unsafe);
        final Path target = unsafe.resolve("ca.pem");
        Files.move(ca, target);
        Files.createSymbolicLink(ca, target);
        ProjectFixture.mode(unsafe, 0777);
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).isEqualTo(66);
        assertThat(answer.err()).doesNotContain("Token for");
        assertThat(commands).isEmpty();
    }

    @Test
    void authorityDirectoryLinksAndLinkCyclesHaveDefinedAnswers() throws Exception {
        final Path directory = host.files.at("/etc/ssl/certs");
        final Path actual = directory.resolveSibling("actual");
        Files.move(directory, actual);
        Files.createSymbolicLink(directory, actual.getFileName());
        assertThat(check(Map.of(), "alpha", "code").code()).isZero();
        final Path ca = actual.resolve("ca-certificates.crt");
        Files.delete(ca);
        Files.createSymbolicLink(ca, ca.getFileName());
        final var answer = check(Map.of(), "alpha", "code");
        assertThat(answer.code()).isEqualTo(66);
        assertThat(answer.err()).doesNotContain("Token for");
    }

    @Test
    void thePasswdLookupHasNoDeadlineInThePushScript() {
        assertThat(check(Map.of(), "alpha", "code").code()).isZero();
        assertThat(timeouts.getFirst()).isEqualTo(Duration.ZERO);
    }
}
