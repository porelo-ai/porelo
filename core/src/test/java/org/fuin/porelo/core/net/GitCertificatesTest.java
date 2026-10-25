package org.fuin.porelo.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against losing system roots, adding unrequested trust, and publishing partial bundles. */
@Timeout(60)
class GitCertificatesTest {

    @TempDir static Path authorities;

    @BeforeAll
    static void createTwoIndependentAuthorities() throws Exception {
        for (String name : List.of("system", "account")) {
            keytool(
                    "-genkeypair",
                    "-alias",
                    name,
                    "-keyalg",
                    "EC",
                    "-dname",
                    "CN=" + name,
                    "-ext",
                    "bc:c",
                    "-keystore",
                    name + ".p12");
            keytool(
                    "-exportcert",
                    "-rfc",
                    "-alias",
                    name,
                    "-keystore",
                    name + ".p12",
                    "-file",
                    name + ".pem");
        }
    }

    @Test
    void theBundleContainsExactlyEverySystemAndAccountCertificate(@TempDir Path dir)
            throws Exception {
        final XdgPaths paths = account(dir);
        final Path first = dir.resolve("system.pem");
        final String systemPem = Files.readString(authorities.resolve("system.pem"));
        Files.writeString(first, systemPem + Files.readString(authorities.resolve("account.pem")));
        final GitCertificates trust =
                new GitCertificates(
                        paths,
                        List.of(
                                dir.resolve("missing"),
                                dir,
                                first,
                                authorities.resolve("account.pem")));
        final Path bundle =
                Path.of(trust.environment("https://forge.example/r.git").get("GIT_SSL_CAINFO"));
        final List<Certificate> expected = new ArrayList<>(read(first));
        expected.addAll(read(TrustedCertificates.file(paths)));

        assertThat(read(first))
                .as("the fixture system store holds two distinct certificates")
                .hasSize(2)
                .doesNotHaveDuplicates();
        assertThat(read(TrustedCertificates.file(paths)))
                .as("the fixture account requests one certificate")
                .hasSize(1);
        assertThat(read(bundle))
                .as("git must keep every root and gain exactly the requested certificates")
                .containsExactlyElementsOf(expected);
        assertThat(Files.getPosixFilePermissions(bundle))
                .as("the generated bundle is owner-only")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(bundle)
                .as("the generated file belongs to the account's state")
                .isEqualTo(paths.gitCaCertificates());
    }

    @Test
    void aMissingSystemBundleRefusesToReplaceSystemTrustWithOnlyTheAccounts(@TempDir Path dir)
            throws Exception {
        final XdgPaths paths = account(dir);
        final List<Path> candidates = List.of(dir.resolve("debian.crt"), dir.resolve("fedora.crt"));
        assertThatThrownBy(
                        () ->
                                new GitCertificates(paths, candidates)
                                        .environment("https://forge.example/r.git"))
                .as("an account-only bundle would strand public forges")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no system CA bundle")
                .hasMessageContaining(candidates.get(0).toString())
                .hasMessageContaining(candidates.get(1).toString());
        assertThat(paths.gitCaCertificates())
                .as("no reduced-trust bundle may be published")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not a certificate",
                "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----"
            })
    void anEmptyOrMalformedAccountFileRefusesRatherThanSilentlyDroppingTrust(
            String content, @TempDir Path dir) throws Exception {
        final XdgPaths paths = account(dir);
        Files.writeString(TrustedCertificates.file(paths), content);
        assertThatThrownBy(() -> trust(paths).environment("https://forge.example/r.git"))
                .as("an authority meant to be trusted must never be silently ignored")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TrustedCertificates.file(paths).toString());
        assertThat(paths.gitCaCertificates()).as("invalid trust never reaches git").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@forge.example/r.git",
                "git@forge.example:r.git",
                "/srv/git/r.git",
                "file:///srv/git/r.git",
                "http://forge.example/r.git"
            })
    void otherTransportsGetNoAdditionalTlsTrust(String url, @TempDir Path dir) throws Exception {
        final XdgPaths paths = account(dir);
        assertThat(trust(paths).environment(url))
                .as("only HTTPS uses the account's authorities")
                .doesNotContainKey("GIT_SSL_CAINFO");
        assertThat(paths.gitCaCertificates())
                .as("other transports do not need a bundle")
                .doesNotExist();
    }

    @Test
    void noAccountFileLeavesGitUsingItsExistingTrustEvenWithNoSystemCandidate(@TempDir Path dir) {
        final XdgPaths paths = paths(dir);
        assertThat(new GitCertificates(paths, List.of()).environment("https://forge.example/r.git"))
                .as("without requested trust git must keep its normal environment")
                .isEmpty();
        assertThat(paths.gitCaCertificates()).as("no trust was requested").doesNotExist();
    }

    @Test
    void changedAuthoritiesReplaceTheBundleAndUnchangedContentLeavesItAlone(@TempDir Path dir)
            throws Exception {
        final XdgPaths paths = account(dir);
        final Path system =
                Files.copy(authorities.resolve("system.pem"), dir.resolve("system.pem"));
        final GitCertificates trust = new GitCertificates(paths, List.of(system));
        trust.environment("https://forge.example/r.git");
        final Path bundle = paths.gitCaCertificates();
        final FileTime marker = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(bundle, marker);
        trust.environment("https://forge.example/r.git");
        assertThat(Files.getLastModifiedTime(bundle))
                .as("identical trust does not rewrite the file")
                .isEqualTo(marker);
        Files.writeString(
                system,
                Files.readString(system) + Files.readString(authorities.resolve("account.pem")));
        trust.environment("https://forge.example/r.git");
        final List<Certificate> expected = new ArrayList<>(read(system));
        expected.addAll(read(TrustedCertificates.file(paths)));
        assertThat(read(bundle))
                .as("a changed system store reaches the next git command")
                .containsExactlyElementsOf(expected);
        Files.copy(
                authorities.resolve("system.pem"),
                TrustedCertificates.file(paths),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        trust.environment("https://forge.example/r.git");
        expected.clear();
        expected.addAll(read(system));
        expected.addAll(read(TrustedCertificates.file(paths)));
        assertThat(read(bundle))
                .as("account rotation must remove the old account trust too")
                .containsExactlyElementsOf(expected);
        Files.delete(TrustedCertificates.file(paths));
        assertThat(trust.environment("https://forge.example/r.git"))
                .as("a stale generated file does not keep removed trust active")
                .isEmpty();
    }

    @Test
    void concurrentWritersOnlyPublishCompleteTrustSnapshots(@TempDir Path dir) throws Exception {
        final XdgPaths paths = account(dir);
        final GitCertificates first = trust(paths);
        final GitCertificates second =
                new GitCertificates(paths, List.of(authorities.resolve("account.pem")));
        first.environment("https://forge.example/r.git");
        final String firstPem = Files.readString(paths.gitCaCertificates());
        second.environment("https://forge.example/r.git");
        final String secondPem = Files.readString(paths.gitCaCertificates());
        try (var workers = Executors.newFixedThreadPool(4)) {
            final List<Future<?>> writes = new ArrayList<>();
            for (int n = 0; n < 32; n++) {
                final GitCertificates writer = n % 2 == 0 ? first : second;
                writes.add(workers.submit(() -> writer.environment("https://forge.example/r.git")));
                assertThat(Files.readString(paths.gitCaCertificates()))
                        .as("git can only see a complete old or new bundle")
                        .isIn(firstPem, secondPem);
            }
            for (Future<?> write : writes) {
                write.get();
            }
        }
        try (var files = Files.list(paths.state())) {
            assertThat(files.toList())
                    .as("temporary bundles are removed after publication")
                    .containsExactly(paths.gitCaCertificates());
        }
    }

    private static GitCertificates trust(XdgPaths paths) {
        return new GitCertificates(paths, List.of(authorities.resolve("system.pem")));
    }

    private static XdgPaths account(Path dir) throws Exception {
        final XdgPaths paths = paths(dir);
        Files.createDirectories(paths.config());
        Files.copy(authorities.resolve("account.pem"), TrustedCertificates.file(paths));
        return paths;
    }

    private static XdgPaths paths(Path dir) {
        return new XdgPaths(
                dir.resolve("config"),
                dir.resolve("data"),
                dir.resolve("state with ' quotes"),
                dir.resolve("run"),
                dir);
    }

    private static List<Certificate> read(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return List.copyOf(CertificateFactory.getInstance("X.509").generateCertificates(in));
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
                        .directory(authorities.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PORELO_TEST_STORE_PASSWORD", "test-password");
        final Process process = builder.start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .as("keytool must create independent test roots: %s", said)
                .isZero();
    }
}
