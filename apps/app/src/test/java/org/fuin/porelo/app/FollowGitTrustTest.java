package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.net.GitCertificates;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitCredentials;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against a forge refusing approved work because host git cannot trust its authority. */
@Timeout(60)
class FollowGitTrustTest {

    @TempDir static Path certificates;

    @BeforeAll
    static void createAuthoritiesAndServerCertificate() throws Exception {
        keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-dname",
                "CN=Test CA",
                "-ext",
                "bc:c",
                "-keystore",
                "ca.p12");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.pem");
        keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-dname",
                "CN=127.0.0.1",
                "-keystore",
                "server.p12");
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-file", "server.csr");
        keytool(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-infile",
                "server.csr",
                "-outfile",
                "server.pem",
                "-rfc",
                "-ext",
                "san=ip:127.0.0.1");
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                "ca",
                "-keystore",
                "server.p12",
                "-file",
                "ca.pem");
        keytool(
                "-importcert",
                "-alias",
                "server",
                "-keystore",
                "server.p12",
                "-file",
                "server.pem");
    }

    @Test
    void hostGitReachesTheHttpAnswerWhenTheAccountTrustsTheAuthority(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        Files.createDirectories(context.paths().caCertificates().getParent());
        Files.copy(certificates.resolve("ca.pem"), context.paths().caCertificates());
        final HttpsServer server = server();
        try {
            final String url = url(server);
            try (FollowCredential credential = FollowCredential.open(context, url, "test")) {
                final GitAnswer answer = git(dir, url, credential.environment());
                assertThat(answer.error())
                        .as("git must get past TLS to the forge's HTTP answer")
                        .doesNotContain("SSL certificate", "certificate verification failed")
                        .containsIgnoringCase("not found");
                assertThat(answer.exit()).as("the fixture has no repository").isNotZero();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void evenACredentialFreeLeaseCarriesTheDestinationsTrust(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        Files.createDirectories(context.paths().caCertificates().getParent());
        Files.copy(certificates.resolve("ca.pem"), context.paths().caCertificates());
        try (FollowCredential credential =
                FollowCredential.open(context, "https://forge.example/r.git", "test")) {
            assertThat(credential.environment())
                    .as("public destinations still need the account's TLS trust")
                    .containsKey("GIT_SSL_CAINFO");
            assertThat(credential.lent()).as("trust policy lends no credential").isFalse();
        }
    }

    @Test
    void aTokenHelperAndTheGatesApprovedPushCarryTheSameTrust(@TempDir Path dir) throws Exception {
        final PoreloContext context = trustedContext(dir);
        final String url = "https://forge.example/r.git";
        final Path token = Files.writeString(dir.resolve("token"), "test-token");
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                token.toString(),
                                Credential.Kind.TOKEN,
                                "https://forge.example",
                                null,
                                "git",
                                Credential.Source.FILE));
        try (FollowCredential credential = FollowCredential.open(context, url, "test")) {
            assertThat(credential.lent()).as("this path must exercise the token helper").isTrue();
            assertThat(credential.arguments())
                    .as("the helper names the token's source")
                    .anySatisfy(
                            argument ->
                                    assertThat(argument)
                                            .as("git's helper reads the file")
                                            .contains("--file"));
            assertThat(credential.environment())
                    .as("a lent helper keeps destination trust")
                    .containsKey("GIT_SSL_CAINFO");
            assertThat(credential.environmentFor(context, url))
                    .as("following uses the same trust")
                    .containsEntry(
                            "GIT_SSL_CAINFO", credential.environment().get("GIT_SSL_CAINFO"));
        }
        final FakeCommandRunner runner = (FakeCommandRunner) context.runner();
        runner.answering("for-each-ref", GitGate.INCOMING + "approved\n")
                .answering("rev-parse", "a".repeat(40));
        new GitGate(runner, dir.resolve("mirror"), GateMode.GATEKEEPING, url)
                .using(new VaultGitCredentials(context, "test"))
                .approve("approved", "main");
        final var push = runner.only(" push ");
        assertThat(push.environment())
                .as("approved work reaches git with its destination's trust")
                .containsEntry(
                        "GIT_SSL_CAINFO", context.paths().xdg().gitCaCertificates().toString());
        assertThat(push.arguments())
                .as("the bundle path is never a shell argument")
                .doesNotContain(context.paths().xdg().gitCaCertificates().toString());
        assertThat(push.arguments()).as("the push uses the lent token helper").contains("-c");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file-missing",
                "environment-missing",
                "environment",
                "vault-missing",
                "vault-closed",
                "vault-token"
            })
    void everyCredentialSourceKeepsTheHttpsDestinationsTrust(String source, @TempDir Path dir)
            throws Exception {
        PoreloContext context = trustedContext(dir);
        final String id =
                switch (source) {
                    case "environment" -> "PATH";
                    case "environment-missing" -> "PORELO_TEST_ABSENT_TOKEN";
                    case "file-missing" -> dir.resolve("missing-token").toString();
                    default -> "git.token.forge.example";
                };
        final Credential.Source origin =
                source.startsWith("environment")
                        ? Credential.Source.ENVIRONMENT
                        : source.startsWith("file")
                                ? Credential.Source.FILE
                                : Credential.Source.VAULT;
        try {
            if (source.equals("vault-token") || source.equals("vault-closed")) {
                context =
                        context.asking(
                                prompt ->
                                        source.equals("vault-token")
                                                ? "test-passphrase".toCharArray()
                                                : null);
                context.vault()
                        .write(
                                Map.of(id, org.fuin.porelo.vault.VaultEntry.of("test-token")),
                                "test-passphrase".toCharArray());
            }
            final Credential offered =
                    new Credential(
                            id,
                            Credential.Kind.TOKEN,
                            "https://forge.example",
                            null,
                            "git",
                            origin);
            try (FollowCredential credential =
                    FollowCredential.open(
                            context, "https://forge.example/r.git", "test", "git", offered)) {
                assertThat(credential.environment())
                        .as("%s must carry trust even when it lends nothing", source)
                        .containsKey("GIT_SSL_CAINFO");
                assertThat(credential.lent())
                        .as("the fixture must exercise the expected lending path")
                        .isEqualTo(source.equals("environment") || source.equals("vault-token"));
                assertThat(credential.vaultShut())
                        .as("the closed vault is distinct from an absent credential")
                        .isEqualTo(source.equals("vault-closed"));
            }
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@forge.example/r.git",
                "git@forge.example:r.git",
                "/srv/git/r.git"
            })
    void sshAndLocalDestinationsNeverReceiveTheHttpsBundle(String url, @TempDir Path dir)
            throws Exception {
        final PoreloContext context = trustedContext(dir);
        try (FollowCredential credential = FollowCredential.open(context, url, "test")) {
            assertThat(credential.environment())
                    .as("the destination chooses trust")
                    .doesNotContainKey("GIT_SSL_CAINFO");
            assertThat(credential.environmentFor(context, url))
                    .as("explicit destination policy also excludes HTTPS trust")
                    .doesNotContainKey("GIT_SSL_CAINFO");
        }
    }

    @Test
    void lendingNothingThroughTheGateStillCarriesTrust(@TempDir Path dir) throws Exception {
        final PoreloContext context = trustedContext(dir);
        final GitCredentials credentials =
                GitCredentials.withoutCredentials(
                        new GitCertificates(
                                context.paths().xdg(), List.of(certificates.resolve("ca.pem"))));
        try (GitCredentials.Lease lease = credentials.forUrl("https://forge.example/r.git")) {
            assertThat(lease.environment())
                    .as("the no-vault implementation still gives git its trust policy")
                    .containsKey("GIT_SSL_CAINFO");
            assertThat(lease.arguments()).as("no credential is lent").isEmpty();
        }
        try (GitCredentials.Lease lease =
                new VaultGitCredentials(context, "test").forUrl("https://forge.example/r.git")) {
            assertThat(lease.environment())
                    .as("the vault implementation also works when it lends nothing")
                    .containsKey("GIT_SSL_CAINFO");
            assertThat(lease.arguments()).as("a public remote needs no helper").isEmpty();
        }
    }

    @Test
    void withoutAnAccountFileGitStillRefusesThePrivateAuthority(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final HttpsServer server = server();
        try {
            final String url = url(server);
            try (FollowCredential credential = FollowCredential.open(context, url, "test")) {
                assertThat(credential.environment())
                        .as("no additional trust was requested")
                        .doesNotContainKey("GIT_SSL_CAINFO");
                final GitAnswer answer = git(dir, url, credential.environment());
                assertThat(answer.exit()).as("the untrusted server must be refused").isNotZero();
                assertThat(answer.error())
                        // OpenSSL builds of git say "problem", GnuTLS builds "verification failed".
                        .as("the refusal must be TLS, before any HTTP answer")
                        .containsAnyOf("SSL certificate", "certificate verification failed")
                        .doesNotContainIgnoringCase("not found");
            }
        } finally {
            server.stop(0);
        }
    }

    private static PoreloContext trustedContext(Path dir) throws Exception {
        final PoreloContext context = context(dir);
        Files.createDirectories(context.paths().caCertificates().getParent());
        Files.copy(certificates.resolve("ca.pem"), context.paths().caCertificates());
        return context;
    }

    private static PoreloContext context(Path dir) {
        final XdgPaths xdg =
                new XdgPaths(
                        dir.resolve("config"),
                        dir.resolve("data"),
                        dir.resolve("state with ' quotes"),
                        dir.resolve("run"),
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static String url(HttpsServer server) {
        return "https://127.0.0.1:" + server.getAddress().getPort() + "/r.git";
    }

    private static HttpsServer server() throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(certificates.resolve("server.p12"))) {
            keys.load(in, "test-password".toCharArray());
        }
        final KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-password".toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private static GitAnswer git(Path dir, String url, Map<String, String> environment)
            throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("git", "ls-remote", url).directory(dir.toFile());
        builder.environment()
                .keySet()
                .removeIf(
                        name ->
                                name.startsWith("GIT_")
                                        || name.toLowerCase(java.util.Locale.ROOT)
                                                .endsWith("_proxy"));
        builder.environment()
                .putAll(
                        Map.of(
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_TERMINAL_PROMPT",
                                "0",
                                "LC_ALL",
                                "C"));
        builder.environment().putAll(environment);
        final Path error = dir.resolve("git-error");
        builder.redirectError(error.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS))
                    .as("local git must finish without a network timeout")
                    .isTrue();
            return new GitAnswer(process.exitValue(), Files.readString(error));
        } finally {
            process.destroyForcibly();
        }
    }

    private static void keytool(String... arguments) throws Exception {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString()));
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storepass:env", "PORELO_TEST_STORE_PASSWORD"));
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(certificates.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PORELO_TEST_STORE_PASSWORD", "test-password");
        final Process process = builder.start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .as("keytool must produce the test certificates: %s", said)
                .isZero();
    }

    private record GitAnswer(int exit, String error) {}
}
