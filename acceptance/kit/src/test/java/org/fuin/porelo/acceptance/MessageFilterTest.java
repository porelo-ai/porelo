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
 * Tests for {@link MessageFilter}: the pin it reads and the install it runs, the latter in a local
 * shell whose {@code curl} and {@code dpkg-deb} are stand-ins and whose PATH holds nothing else.
 */
class MessageFilterTest {

    private static final String FILTER = "porelo-message-sluice-filter";

    private static final String URL = "https://packages.invalid/pool/filter_1.0_amd64.deb";

    private static final String RPM_URL = "https://packages.invalid/rpm/filter-1.0-1.x86_64.rpm";

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

    /** A pin whose .deb is the one given and whose .rpm is a valid one. */
    private static Properties pin(String url, String sha256) {
        final Properties pin = new Properties();
        pin.setProperty("deb.url", url);
        pin.setProperty("deb.sha256", sha256);
        pin.setProperty("rpm.url", RPM_URL);
        pin.setProperty("rpm.sha256", "d".repeat(64));
        return pin;
    }

    /** The pinned packages, the .deb with the digest given and the .rpm with that of "the rpm". */
    private static MessageFilter.Pinned pinned(String debSha256) {
        return new MessageFilter.Pinned(
                new MessageFilter.Package(URL, debSha256),
                new MessageFilter.Package(RPM_URL, sha256Unchecked("the rpm")));
    }

    @Test
    void aRunWithNeitherPinNorOverrideFailsNamingTheFilter() {
        assertThatThrownBy(() -> MessageFilter.source(new Properties(), Map.of()))
                .as(
                        "no filter to install stops the run at its start, naming the filter and the pin")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("message-filter.properties");
    }

    @Test
    void anOverrideWithoutItsDigestIsRefused() {
        assertThatThrownBy(
                        () ->
                                MessageFilter.source(
                                        pin(URL, sha256Unchecked("x")),
                                        Map.of("PORELO_ACCEPTANCE_FILTER", "/opt/filter")))
                .as(
                        "a path without its digest is no override; nothing would check what it installs")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("PORELO_ACCEPTANCE_FILTER_SHA256");
    }

    @Test
    void aPinThatIsNotHttpsOrHasNoDigestIsRefused() {
        assertThatThrownBy(
                        () ->
                                MessageFilter.source(
                                        pin("http://x.invalid/f.deb", "a".repeat(64)), Map.of()))
                .as("a download in the clear is refused")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER);
        assertThatThrownBy(() -> MessageFilter.source(pin(URL, "not-a-digest"), Map.of()))
                .as("a digest that is no sha256 is refused")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER);
    }

    @Test
    void theOverrideTakesThePlaceOfThePin() {
        assertThat(
                        MessageFilter.source(
                                pin(URL, "b".repeat(64)),
                                Map.of(
                                        "PORELO_ACCEPTANCE_FILTER",
                                        "/opt/filter",
                                        "PORELO_ACCEPTANCE_FILTER_SHA256",
                                        "a".repeat(64))))
                .as("a local file named with its digest is installed instead of the download")
                .isEqualTo(new MessageFilter.Override("/opt/filter", "a".repeat(64)));
    }

    @Test
    void theShippedPinIsAnHttpsAddressWithItsDigest() {
        assertThat(MessageFilter.source(MessageFilter.pin(), Map.of()))
                .as("the kit carries the pin in its one place")
                .isInstanceOfSatisfying(
                        MessageFilter.Pinned.class,
                        pinned -> {
                            for (final MessageFilter.Package each :
                                    List.of(pinned.deb(), pinned.rpm())) {
                                assertThat(each.url()).as("the address").startsWith("https://");
                                assertThat(each.sha256()).as("the digest").matches("[0-9a-f]{64}");
                            }
                            assertThat(pinned.deb().url())
                                    .as("the .deb's address")
                                    .endsWith(".deb");
                            assertThat(pinned.rpm().url())
                                    .as("the .rpm's address")
                                    .endsWith(".rpm");
                        });
    }

    @Test
    void aPinWithoutItsRpmIsRefused() {
        final Properties debOnly = pin(URL, "a".repeat(64));
        debOnly.remove("rpm.url");

        assertThatThrownBy(() -> MessageFilter.source(debOnly, Map.of()))
                .as("a pin is both formats of one build, so a machine of either kind is served")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(FILTER)
                .hasMessageContaining("rpm.url");
    }

    @Test
    void aMachineWithRpm2cpioAndCpioInstallsFromTheRpm() throws Exception {
        final Ran ran = install(pinned("e".repeat(64)), Unpacker.RPM);

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
                        new MessageFilter.Pinned(
                                new MessageFilter.Package(URL, "e".repeat(64)),
                                new MessageFilter.Package(RPM_URL, "f".repeat(64))),
                        Unpacker.RPM);

        assertThat(ran.status()).as("a digest that differs fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter")
                .contains(FILTER)
                .contains("sha256");
        assertThat(home.resolve("rpm2cpio-ran"))
                .as("an .rpm that is not the pinned one is never unpacked")
                .doesNotExist();
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aMatchingPackageIsUnpackedAndItsFilterInstalledForTheAccountAlone() throws Exception {
        final Ran ran = install(pinned(sha256("the package")), Unpacker.DEB);

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

        final Ran ran = install(pinned(sha256("another package")), Unpacker.DEB);

        assertThat(ran.status()).as("a digest that differs fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter")
                .contains(FILTER)
                .contains("sha256");
        assertThat(home.resolve("dpkg-deb-ran"))
                .as("a package that is not the pinned one is never unpacked")
                .doesNotExist();
        assertThat(executables())
                .as("a digest mismatch leaves no file executable, not even an older filter")
                .isEmpty();
    }

    @Test
    void aFailedDownloadFailsNamingTheFilter() throws Exception {
        Files.writeString(home.resolve("curl-fails"), "");

        final Ran ran = install(pinned(sha256("the package")), Unpacker.DEB);

        assertThat(ran.status()).as("a download that fails fails the install").isNotZero();
        assertThat(ran.text())
                .as("the failure names the filter and the address")
                .contains(FILTER)
                .contains(URL);
        assertThat(executables()).as("nothing left executable").isEmpty();
    }

    @Test
    void aMachineWithNeitherUnpackerSaysSo() throws Exception {
        final Ran ran = install(pinned(sha256("the package")), Unpacker.NONE);

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

        final Ran ran = install(pinned(sha256("the package")), Unpacker.DEB);

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
    void aFailureNamesThePinAndSaysToUpdateIt() {
        final String failure =
                MessageFilter.failure(pinned("c".repeat(64)), "acceptance", "curl: (22) 404");

        assertThat(failure)
                .as("a snapshot that left the pool is told apart from a broken machine")
                .contains(FILTER)
                .contains(URL)
                .contains("c".repeat(64))
                .contains(RPM_URL)
                .contains("message-filter.properties")
                .contains("update")
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
