package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards against a private forge on its own port being answered as unreachable because the token
 * the vault holds for it never reached it.
 *
 * <p>Nothing rewrites the address here: real git fetches over TLS from the address the lease was
 * opened for, with the argument Porelo built, and real {@code porelo vault credential} answers it.
 */
@Timeout(120)
class TokenFetchOnItsOwnPortTest {

    private static final String TOKEN = "lent-test-value";

    private static final String PASSWORD = "test-password";

    @TempDir static Path certificates;

    @BeforeAll
    static void createTheForgesCertificate() throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "forge",
                                "-keyalg",
                                "EC",
                                "-dname",
                                "CN=127.0.0.1",
                                "-ext",
                                "bc:c",
                                "-ext",
                                "san=ip:127.0.0.1",
                                "-keystore",
                                "forge.p12",
                                "-storepass:env",
                                "PORELO_TEST_STORE_PASSWORD")
                        .directory(certificates.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PORELO_TEST_STORE_PASSWORD", PASSWORD);
        final Process keytool = builder.start();
        final String said =
                new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as("keytool must make the certificate: %s", said).isZero();
        final KeyStore keys = store();
        final String pem =
                "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(keys.getCertificate("forge").getEncoded())
                        + "\n-----END CERTIFICATE-----\n";
        Files.writeString(certificates.resolve("forge.pem"), pem, StandardCharsets.US_ASCII);
    }

    @AfterEach
    void forgetTheBinary() {
        System.clearProperty(PoreloBinary.NAMED);
    }

    @Test
    void aFetchFromAForgeOnItsOwnPortReachesItWithTheToken(@TempDir Path dir) throws Exception {
        final Path served = dir.resolve("served");
        final String commit = repositoryServedAt(served, dir);
        final PoreloContext context = contextTrustingTheForge(dir);
        System.setProperty(PoreloBinary.NAMED, ownPorelo(dir).toString());
        final Path token = Files.writeString(dir.resolve("token"), TOKEN + "\n");
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                token.toString(),
                                Credential.Kind.TOKEN,
                                "https://127.0.0.1",
                                null,
                                "git",
                                Credential.Source.FILE));
        final List<String> seen = new CopyOnWriteArrayList<>();
        final HttpsServer forge = forge(served, seen);
        try {
            final String url = "https://127.0.0.1:" + forge.getAddress().getPort() + "/r.git";
            final Path clone = Files.createDirectory(dir.resolve("clone"));
            git(dir, Map.of(), List.of(), "init", "-q", clone.toString());

            final GitResult fetched;
            try (FollowCredential lent = FollowCredential.open(context, url, "test")) {
                fetched =
                        git(
                                clone,
                                lent.environment(),
                                lent.arguments(),
                                "fetch",
                                "--quiet",
                                "--end-of-options",
                                url,
                                "HEAD");
            }

            assertThat(seen)
                    .as("the forge saw the token: %s", fetched.said())
                    .contains("x-access-token:" + TOKEN);
            assertThat(fetched.exit()).as(fetched.said()).isZero();
            assertThat(fetched.said()).as("nothing git says holds the token").doesNotContain(TOKEN);
            assertThat(git(clone, Map.of(), List.of(), "rev-parse", "FETCH_HEAD").said().strip())
                    .as("the repository's commit arrived")
                    .isEqualTo(commit);
        } finally {
            forge.stop(0);
        }
    }

    /**
     * A program reads a private repository passing only its address: git runs {@code porelo vault
     * credential --for <url>} as its helper, and the token reaches the forge and nothing else.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"declared-file", "implied-vault"})
    void aProgramReadsAPrivateRepositoryByItsAddressAlone(final String held, @TempDir Path dir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                held.equals("declared-file") || KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final Path served = dir.resolve("served");
        final String commit = repositoryServedAt(served, dir);
        final PoreloContext context = contextTrustingTheForge(dir);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        if (held.equals("declared-file")) {
            final Path token = Files.writeString(dir.resolve("token"), TOKEN + "\n");
            new CredentialDeclarations(context)
                    .declare(
                            new Credential(
                                    token.toString(),
                                    Credential.Kind.TOKEN,
                                    "https://127.0.0.1",
                                    null,
                                    "git",
                                    Credential.Source.FILE));
        } else {
            Files.createDirectories(context.vault().path().getParent());
            context.vault()
                    .write(
                            Map.of("git.token.127.0.0.1", VaultEntry.of(TOKEN)),
                            "test-passphrase".toCharArray());
            keyring.store("test-passphrase".toCharArray());
        }
        final List<String> seen = new CopyOnWriteArrayList<>();
        final HttpsServer forge = forge(served, seen);
        try {
            final String origin = "https://127.0.0.1:" + forge.getAddress().getPort();
            final String url = origin + "/r.git";
            final Path clone = Files.createDirectory(dir.resolve("clone"));
            git(dir, Map.of(), List.of(), "init", "-q", clone.toString());
            final Map<String, String> trust =
                    Map.of("GIT_SSL_CAINFO", context.paths().caCertificates().toString());

            final GitResult beside =
                    git(
                            clone,
                            trust,
                            helperFor(context, dir, origin, origin + "/other.git"),
                            "fetch",
                            "--quiet",
                            "--end-of-options",
                            url,
                            "HEAD");
            assertThat(seen)
                    .as("a helper for another path lends nothing: %s", beside.said())
                    .doesNotContain("x-access-token:" + TOKEN);
            assertThat(beside.exit()).as(beside.said()).isNotZero();
            assertThat(beside.said()).doesNotContain(TOKEN);

            final List<String> arguments = helperFor(context, dir, origin, url);
            final GitResult fetched =
                    git(
                            clone,
                            trust,
                            arguments,
                            "fetch",
                            "--quiet",
                            "--end-of-options",
                            url,
                            "HEAD");

            assertThat(seen)
                    .as("the forge saw the token: %s", fetched.said())
                    .contains("x-access-token:" + TOKEN);
            assertThat(fetched.exit()).as(fetched.said()).isZero();
            assertThat(fetched.said()).as("nothing git says holds the token").doesNotContain(TOKEN);
            assertThat(String.join(" ", arguments))
                    .as("the caller's arguments name the address alone")
                    .doesNotContain(TOKEN)
                    .doesNotContain("git.token");
            assertThat(git(clone, Map.of(), List.of(), "rev-parse", "FETCH_HEAD").said().strip())
                    .as("the repository's commit arrived")
                    .isEqualTo(commit);
        } finally {
            forge.stop(0);
            if (held.equals("implied-vault")) {
                keyring.forget();
            }
        }
    }

    /**
     * What a program passes git: the helper, run with the account's directories, told the address
     * and nothing else, and git told to send the path the helper compares.
     */
    private static List<String> helperFor(
            final PoreloContext context, final Path dir, final String origin, final String address)
            throws IOException {
        final XdgPaths xdg = context.paths().xdg();
        return List.of(
                "-c",
                "credential.useHttpPath=true",
                "-c",
                "credential."
                        + origin
                        + ".helper=!HOME='"
                        + xdg.home()
                        + "' XDG_CONFIG_HOME='"
                        + xdg.config().getParent()
                        + "' XDG_DATA_HOME='"
                        + xdg.data().getParent()
                        + "' XDG_STATE_HOME='"
                        + xdg.state().getParent()
                        + "' "
                        + PoreloPaths.VAULT_VARIABLE
                        + "='"
                        + context.vault().path().toAbsolutePath()
                        + "' '"
                        + ownPorelo(dir)
                        + "' vault credential --for '"
                        + address
                        + "'");
    }

    /** A bare repository with one commit, laid out for git's plain file transport over http. */
    private static String repositoryServedAt(final Path served, final Path dir) throws Exception {
        final Path work = Files.createDirectory(dir.resolve("work"));
        final Path bare = served.resolve("r.git");
        Files.createDirectories(served);
        git(work, Map.of(), List.of(), "init", "-q", "-b", "main");
        git(
                work,
                Map.of(),
                List.of(),
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "One");
        git(dir, Map.of(), List.of(), "clone", "-q", "--bare", work.toString(), bare.toString());
        git(bare, Map.of(), List.of(), "update-server-info");
        return git(bare, Map.of(), List.of(), "rev-parse", "HEAD").said().strip();
    }

    private static PoreloContext contextTrustingTheForge(final Path dir) throws IOException {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0,
                        name -> null);
        Files.createDirectories(context.paths().caCertificates().getParent());
        Files.copy(certificates.resolve("forge.pem"), context.paths().caCertificates());
        return context;
    }

    /** This build's own command line, which is what git starts as the helper. */
    private static Path ownPorelo(final Path dir) throws IOException {
        final Path launcher = dir.resolve("own").resolve("porelo");
        Files.createDirectories(launcher.getParent());
        Files.writeString(
                launcher,
                "#!/bin/sh\nexec '"
                        + ProcessHandle.current().info().command().orElse("java")
                        + "' --enable-native-access=ALL-UNNAMED '-Dporelo.keyring.prefix="
                        + System.getProperty("porelo.keyring.prefix", "porelo")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        return launcher;
    }

    /** A forge on loopback serving files only to the token, noting each credential it was sent. */
    private static HttpsServer forge(final Path root, final List<String> seen) throws Exception {
        final KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store(), PASSWORD.toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", answering(root, seen));
        server.start();
        return server;
    }

    /** Serves files only to the token, noting each credential it was sent. */
    private static HttpHandler answering(final Path root, final List<String> seen) {
        return exchange -> {
            try (exchange) {
                final String sent = exchange.getRequestHeaders().getFirst("Authorization");
                final String credential =
                        sent == null || !sent.startsWith("Basic ")
                                ? "none"
                                : new String(
                                        Base64.getDecoder().decode(sent.substring(6)),
                                        StandardCharsets.UTF_8);
                seen.add(credential);
                if (!credential.equals("x-access-token:" + TOKEN)) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"forge\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                final Path file =
                        root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                final byte[] body = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        };
    }

    private static KeyStore store() throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(certificates.resolve("forge.p12"))) {
            keys.load(in, PASSWORD.toCharArray());
        }
        return keys;
    }

    /** Runs git as a fetch would, apart from this machine's configuration and account. */
    private static GitResult git(
            final Path where,
            final Map<String, String> environment,
            final List<String> before,
            final String... arguments)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(before);
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        final String path = System.getenv("PATH");
        builder.environment().clear();
        builder.environment()
                .putAll(
                        Map.of(
                                "PATH",
                                path == null ? "" : path,
                                "HOME",
                                where.toString(),
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_TERMINAL_PROMPT",
                                "0",
                                "LC_ALL",
                                "C"));
        builder.environment().putAll(environment);
        final Process git = builder.start();
        git.getOutputStream().close();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitResult(git.waitFor(), said);
    }

    private record GitResult(int exit, String said) {}
}
