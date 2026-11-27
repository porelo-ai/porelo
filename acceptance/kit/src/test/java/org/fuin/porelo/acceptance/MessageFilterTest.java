package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MessageFilter}: the snapshot it finds and the install it runs, the latter in a
 * local shell whose {@code curl} and {@code dpkg-deb} are stand-ins and whose PATH holds nothing
 * else.
 */
class MessageFilterTest {

    private static final String FILTER = "porelo-message-sluice-filter";

    private static final String BASE = "https://packages.invalid/artifactory";

    private static final String URL = BASE + "/deb/pool/filter_1.0~snapshot.10_amd64.deb";

    private static final String RPM_URL =
            BASE + "/rpm/snapshots/filter-1.0~snapshot.10-1.x86_64.rpm";

    /** The tools the install may use besides the two stand-ins. */
    private static final List<String> TOOLS =
            List.of("sh", "mkdir", "mktemp", "rm", "cp", "chmod", "mv", "sha256sum", "cut", "cat");

    /**
     * Writes what follows {@code -o} as the downloaded package, "the rpm" for an .rpm address;
     * fails when told to.
     */
    private static final String CURL =
            "out=; url=; while [ $# -gt 0 ]; do [ \"$1\" = -o ] && out=$2; url=$1; shift; done;"
                    + " [ -f \"$HOME/curl-fails\" ] && exit 22;"
                    + " case \"$url\" in *.rpm) printf 'the rpm' > \"$out\";;"
                    + " *) printf 'the package' > \"$out\";; esac";

    /** Writes the package as its archive, as the real one does, for the cpio stand-in. */
    private static final String RPM2CPIO = ": > \"$HOME/rpm2cpio-ran\"; cat \"$1\"";

    /** Unpacks the archive on its input into the working directory, as {@code cpio -idm} does. */
    private static final String CPIO =
            "[ \"$1\" = -idm ] || exit 2; [ \"$(cat)\" = 'the rpm' ] || exit 3;"
                    + " mkdir -p usr/libexec/porelo && printf 'the rpm filter' > usr/libexec/porelo/"
                    + FILTER
                    + " && chmod 0755 usr/libexec/porelo/"
                    + FILTER;

    /** Which unpacker the machine has. */
    private enum Unpacker {
        DEB,
        RPM,
        NONE
    }

    /** Unpacks the package's one file where the real one has it, executable as packaged. */
    private static final String DPKG_DEB =
            "touch_=\"$HOME/dpkg-deb-ran\"; : > \"$touch_\"; [ \"$1\" = -x ] || exit 2;"
                    + " [ -f \"$HOME/package-empty\" ] && { mkdir -p \"$3/usr/share\"; exit 0; };"
                    + " mkdir -p \"$3/usr/libexec/porelo\" && printf 'the filter' > \"$3/usr/libexec/porelo/"
                    + FILTER
                    + "\" && chmod 0755 \"$3/usr/libexec/porelo/"
                    + FILTER
                    + "\"";

    @TempDir Path home;

    private record Ran(int status, String text) {}

    private Ran install(MessageFilter.Source source, Unpacker unpacker) throws Exception {
        final Path bin = Files.createDirectories(home.resolve("bin"));
        for (final String tool : TOOLS) {
            final Path real =
                    Stream.of("/usr/bin/" + tool, "/bin/" + tool)
                            .map(Path::of)
                            .filter(Files::isExecutable)
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(tool + " not found"));
            Files.deleteIfExists(bin.resolve(tool));
            Files.createSymbolicLink(bin.resolve(tool), real);
        }
        standIn(bin, "curl", CURL);
        if (unpacker == Unpacker.DEB) {
            standIn(bin, "dpkg-deb", DPKG_DEB);
        }
        if (unpacker == Unpacker.RPM) {
            standIn(bin, "rpm2cpio", RPM2CPIO);
            standIn(bin, "cpio", CPIO);
        }
        final ProcessBuilder builder =
                new ProcessBuilder(
                                bin.resolve("sh").toString(), "-c", MessageFilter.command(source))
                        .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PATH", bin.toString());
        final Process process = builder.start();
        final String text;
        try (var output = process.getInputStream()) {
            text = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Ran(process.waitFor(), text);
    }

    private static void standIn(Path bin, String name, String body) throws IOException {
        final Path file = bin.resolve(name);
        Files.writeString(file, "#!" + bin.resolve("sh") + "\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private Path target() {
        return home.resolve(".local/share/porelo/filter").resolve(FILTER);
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private List<Path> executables() throws IOException {
        final Path data = home.resolve(".local/share/porelo");
        if (!Files.exists(data)) {
            return List.of();
        }
        try (Stream<Path> all = Files.walk(data)) {
            return all.filter(Files::isRegularFile).filter(Files::isExecutable).toList();
        }
    }

    /** Where the test's packages are published, both formats. */
    private static Properties origin() {
        final Properties origin = new Properties();
        origin.setProperty("artifactory", BASE);
        origin.setProperty("deb.folder", "deb/pool");
        origin.setProperty("deb.name", "filter_{version}_amd64.deb");
        origin.setProperty("rpm.folder", "rpm/snapshots");
        origin.setProperty("rpm.name", "filter-{version}-1.x86_64.rpm");
        return origin;
    }

    /**
     * An Artifactory whose folders hold snapshots 9 and 10 and another package's newer one, and
     * which names the digest given for the .deb's snapshot 10.
     */
    private static Artifactory.Lookup artifactory(String debSha256) {
        final Map<String, String> answers =
                Map.of(
                        BASE + "/api/storage/deb/pool",
                        listing(
                                "/filter_1.0~snapshot.9_amd64.deb",
                                "/filter_1.0~snapshot.10_amd64.deb",
                                "/filterx_2.0~snapshot.99_amd64.deb",
                                "/filter_1.0_amd64.deb"),
                        BASE + "/api/storage/deb/pool/filter_1.0~snapshot.10_amd64.deb",
                        "{\"checksums\":{\"sha256\":\"" + debSha256 + "\"}}",
                        BASE + "/api/storage/rpm/snapshots",
                        listing(
                                "/filter-0.9~snapshot.12-1.x86_64.rpm",
                                "/filter-1.0~snapshot.10-1.x86_64.rpm"),
                        BASE + "/api/storage/rpm/snapshots/filter-1.0~snapshot.10-1.x86_64.rpm",
                        "{\"checksums\":{\"sha256\":\"" + "d".repeat(64) + "\"}}");
        return url -> {
            final String answer = answers.get(url);
            if (answer == null) {
                throw new IOException(url + " answered 404: not found");
            }
            return answer;
        };
    }

    private static String listing(String... uris) {
        return Stream.of(uris)
                .map(uri -> "{\"uri\":\"" + uri + "\",\"folder\":false}")
                .collect(java.util.stream.Collectors.joining(",", "{\"children\":[", "]}"));
    }

    /**
     * The published packages, the .deb with the digest given and the .rpm with that of "the rpm".
     */
    private static MessageFilter.Published published(String debSha256) {
        return new MessageFilter.Published(
                new MessageFilter.Package(URL, debSha256),
                new MessageFilter.Package(RPM_URL, sha256Unchecked("the rpm")));
    }

    @Test
    void aRunWithNeitherOriginNorOverrideFailsNamingTheFilter() {
        assertThatThrownBy(
                        () ->
                                MessageFilter.source(
                                        new Properties(), Map.of(), artifactory("a".repeat(64))))
                .as(
                        "no filter to install stops the run at its start, naming the filter and its origin")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("message-filter.properties");
    }

    @Test
    void anOverrideWithoutItsDigestIsRefused() {
        assertThatThrownBy(
                        () ->
                                MessageFilter.source(
                                        origin(),
                                        Map.of("PORELO_ACCEPTANCE_FILTER", "/opt/filter"),
                                        artifactory("a".repeat(64))))
                .as(
                        "a path without its digest is no override; nothing would check what it installs")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("PORELO_ACCEPTANCE_FILTER_SHA256");
    }

    @Test
    void theNewestSnapshotIsTakenWithTheDigestArtifactoryNames() {
        assertThat(MessageFilter.source(origin(), Map.of(), artifactory("b".repeat(64))))
                .as(
                        "snapshot 10 is newer than 9 and than an older release's 12; another package"
                                + " and a release are no candidates")
                .isEqualTo(
                        new MessageFilter.Published(
                                new MessageFilter.Package(URL, "b".repeat(64)),
                                new MessageFilter.Package(RPM_URL, "d".repeat(64))));
    }

    @Test
    void anOriginThatIsNotHttpsOrADigestThatIsNoSha256IsRefused() {
        final Properties clear = origin();
        clear.setProperty("artifactory", "http://packages.invalid/artifactory");

        assertThatThrownBy(() -> MessageFilter.source(clear, Map.of(), artifactory("a".repeat(64))))
                .as("a download in the clear is refused")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER);
        assertThatThrownBy(
                        () -> MessageFilter.source(origin(), Map.of(), artifactory("not-a-digest")))
                .as("a digest that is no sha256 is refused")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("sha256");
    }

    @Test
    void anArtifactoryThatCannotBeReadFailsWithWhatItSaid() {
        assertThatThrownBy(
                        () ->
                                MessageFilter.source(
                                        origin(),
                                        Map.of(),
                                        url -> {
                                            throw new IOException(url + " answered 503: busy");
                                        }))
                .as("the run stops naming the filter and keeps Artifactory's own answer")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("503: busy");
    }

    @Test
    void theOverrideTakesThePlaceOfTheNewestSnapshot() {
        assertThat(
                        MessageFilter.source(
                                origin(),
                                Map.of(
                                        "PORELO_ACCEPTANCE_FILTER",
                                        "/opt/filter",
                                        "PORELO_ACCEPTANCE_FILTER_SHA256",
                                        "a".repeat(64)),
                                url -> {
                                    throw new IOException("Artifactory is not asked: " + url);
                                }))
                .as("a local file named with its digest is installed instead of the download")
                .isEqualTo(new MessageFilter.Override("/opt/filter", "a".repeat(64)));
    }

    @Test
    void theShippedOriginNamesHttpsArtifactoryAndBothFormats() {
        final Properties shipped = MessageFilter.origin();

        assertThat(shipped.getProperty("artifactory"))
                .as("the kit names Artifactory in its one place")
                .startsWith("https://");
        assertThat(shipped.getProperty("deb.name"))
                .as("the .deb's name")
                .startsWith(FILTER + "_{version}")
                .endsWith(".deb");
        assertThat(shipped.getProperty("rpm.name"))
                .as("the .rpm's name")
                .startsWith(FILTER + "-{version}")
                .endsWith(".rpm");
    }

    @Test
    void anOriginWithoutItsRpmIsRefused() {
        final Properties debOnly = origin();
        debOnly.remove("rpm.folder");

        assertThatThrownBy(
                        () -> MessageFilter.source(debOnly, Map.of(), artifactory("a".repeat(64))))
                .as("both formats are found, so a machine of either kind is served")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("rpm.folder");
    }

    @Test
    void aMachineWithRpm2cpioAndCpioInstallsFromTheRpm() throws Exception {
        final Ran ran = install(published("e".repeat(64)), Unpacker.RPM);

        assertThat(ran.status()).as("the install from the .rpm: %s", ran.text()).isZero();
        assertThat(Files.readString(target()))
                .as("the .rpm's filter, at the account's own place")
                .isEqualTo("the rpm filter");
        assertThat(Files.getPosixFilePermissions(target()))
                .as("executable by the account alone")
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(executables()).as("nothing else left executable").containsExactly(target());
    }

    @Test
    void anRpmWhoseDigestDiffersIsRefusedBeforeItIsUnpacked() throws Exception {
        final Ran ran =
                install(
                        new MessageFilter.Published(
                                new MessageFilter.Package(URL, "e".repeat(64)),
                                new MessageFilter.Package(RPM_URL, "f".repeat(64))),
                        Unpacker.RPM);

        assertThat(ran.status()).as("a digest that differs fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter")
                .contains(FILTER)
                .contains("sha256");
        assertThat(home.resolve("rpm2cpio-ran"))
                .as("an .rpm that is not the one Artifactory names is never unpacked")
                .doesNotExist();
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aMatchingPackageIsUnpackedAndItsFilterInstalledForTheAccountAlone() throws Exception {
        final Ran ran = install(published(sha256("the package")), Unpacker.DEB);

        assertThat(ran.status()).as("the install of a matching package: %s", ran.text()).isZero();
        assertThat(Files.readString(target()))
                .as("the package's filter, at the account's own place")
                .isEqualTo("the filter");
        assertThat(Files.getPosixFilePermissions(target()))
                .as("executable by the account alone")
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(executables()).as("nothing else left executable").containsExactly(target());
    }

    @Test
    void aPackageWhoseDigestDiffersIsRefusedBeforeItIsUnpacked() throws Exception {
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "an older filter");
        Files.setPosixFilePermissions(target(), PosixFilePermissions.fromString("rwx------"));

        final Ran ran = install(published(sha256("another package")), Unpacker.DEB);

        assertThat(ran.status()).as("a digest that differs fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter")
                .contains(FILTER)
                .contains("sha256");
        assertThat(home.resolve("dpkg-deb-ran"))
                .as("a package that is not the one Artifactory names is never unpacked")
                .doesNotExist();
        assertThat(executables())
                .as("a digest mismatch leaves no file executable, not even an older filter")
                .isEmpty();
    }

    @Test
    void aFailedDownloadFailsNamingTheFilter() throws Exception {
        Files.writeString(home.resolve("curl-fails"), "");

        final Ran ran = install(published(sha256("the package")), Unpacker.DEB);

        assertThat(ran.status()).as("a download that fails fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter and the address")
                .contains(FILTER)
                .contains(URL);
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aMachineWithNeitherUnpackerSaysSo() throws Exception {
        final Ran ran = install(published(sha256("the package")), Unpacker.NONE);

        assertThat(ran.status()).as("no unpacker fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the missing tools")
                .contains("dpkg-deb")
                .contains("rpm2cpio")
                .contains(FILTER);
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aPackageWithoutTheFilterIsRefused() throws Exception {
        Files.writeString(home.resolve("package-empty"), "");

        final Ran ran = install(published(sha256("the package")), Unpacker.DEB);

        assertThat(ran.status()).as("a package that holds no filter fails the install").isNotZero();
        assertThat(ran.text()).as("the failure names the filter").contains(FILTER);
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void anOverrideWithItsDigestIsCopiedIn() throws Exception {
        final Path local = Files.writeString(home.resolve("local-filter"), "a local filter");

        final Ran ran =
                install(
                        new MessageFilter.Override(local.toString(), sha256("a local filter")),
                        Unpacker.NONE);

        assertThat(ran.status()).as("the override's install: %s", ran.text()).isZero();
        assertThat(Files.readString(target()))
                .as("the local file, in place")
                .isEqualTo("a local filter");
        assertThat(Files.getPosixFilePermissions(target()))
                .as("executable by the account alone")
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void anOverrideWhoseDigestDiffersLeavesNothingExecutable() throws Exception {
        final Path local = Files.writeString(home.resolve("local-filter"), "a changed filter");

        final Ran ran =
                install(
                        new MessageFilter.Override(local.toString(), sha256("a local filter")),
                        Unpacker.NONE);

        assertThat(ran.status()).as("a digest that differs fails the install").isNotZero();
        assertThat(ran.text()).as("the failure names the filter").contains(FILTER);
        assertThat(executables()).as("a digest mismatch leaves no file executable").isEmpty();
    }

    @Test
    void anOverrideThatIsNotThereFailsNamingTheFilter() throws Exception {
        final Ran ran =
                install(
                        new MessageFilter.Override(
                                home.resolve("missing").toString(), "a".repeat(64)),
                        Unpacker.NONE);

        assertThat(ran.status()).as("no file to install fails the install").isNotZero();
        assertThat(ran.text()).as("the failure names the filter").contains(FILTER);
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aFailureNamesThePackagesItWasToComeFrom() {
        final String failure =
                MessageFilter.failure(published("c".repeat(64)), "acceptance", "curl: (22) 404");

        assertThat(failure)
                .as("a snapshot that left the pool is told apart from a broken machine")
                .contains(FILTER)
                .contains(URL)
                .contains("c".repeat(64))
                .contains(RPM_URL)
                .contains("message-filter.properties")
                .contains("curl: (22) 404");
    }

    private static String sha256Unchecked(String text) {
        try {
            return sha256(text);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
