package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the lowest OpenSSH Porelo works with: below it Porelo says which it found and which it
 * needs, and a version it cannot read refuses nothing.
 */
class OpenSshFloorTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    /** {@code ssh -V} writes to standard error, not to standard output. */
    private static FakeCommandRunner saying(final String banner) {
        return new FakeCommandRunner().answering("ssh -V", "", banner + "\n");
    }

    @Test
    void aVersionAtOrAboveTheFloorIsEnough() {
        for (final String banner :
                java.util.List.of(
                        "OpenSSH_8.9p1 Ubuntu-3ubuntu0.13, OpenSSL 3.0.2 15 Mar 2022",
                        "OpenSSH_8.10p1",
                        "OpenSSH_9.0p1",
                        "OpenSSH_9.2p1 Debian-2+deb12u3, OpenSSL 3.0.15 3 Sep 2024",
                        "OpenSSH_10.2p1 Ubuntu-2ubuntu3.8, OpenSSL 3.5.5 27 Jan 2026",
                        "OpenSSH_10.0")) {
            assertThat(OpenSshFloor.ask(saying(banner)))
                    .as(banner)
                    .isInstanceOf(OpenSshFloor.Enough.class);
        }
    }

    @Test
    void aVersionBelowTheFloorIsTooOld() {
        for (final String banner :
                java.util.List.of(
                        "OpenSSH_8.8p1 Ubuntu-1", "OpenSSH_8.0p1", "OpenSSH_7.9p1 Debian-10")) {
            assertThat(OpenSshFloor.ask(saying(banner)))
                    .as(banner)
                    .isInstanceOf(OpenSshFloor.TooOld.class);
        }
    }

    @Test
    void theRefusalNamesTheVersionFoundAndTheVersionNeeded() {
        assertThat(OpenSshFloor.ask(saying("OpenSSH_8.8p1 Ubuntu-1, OpenSSL 3.0.2")))
                .isInstanceOfSatisfying(
                        OpenSshFloor.TooOld.class,
                        old ->
                                assertThat(old.refusal())
                                        .contains("OpenSSH 8.8p1")
                                        .contains("OpenSSH 8.9 or newer")
                                        .contains("ssh-keygen -Y"));
    }

    @Test
    void aBannerAfterAWarningIsStillRead() {
        assertThat(OpenSshFloor.ask(saying("a warning of the loader\nOpenSSH_8.8p1 Ubuntu-1")))
                .isInstanceOf(OpenSshFloor.TooOld.class);
    }

    @Test
    void whatCannotBeReadIsUnreadRatherThanTooOld() {
        for (final String banner :
                java.util.List.of(
                        "",
                        "ssh: unknown option -- V",
                        "Sun_SSH_1.1.5",
                        "OpenSSH_unknown",
                        "OpenSSH_99999999999999999999.1p1",
                        "my OpenSSH_8.8p1")) {
            assertThat(OpenSshFloor.ask(saying(banner)))
                    .as(banner)
                    .isInstanceOf(OpenSshFloor.Unread.class);
        }
    }

    @Test
    void anSshThatFailsIsUnreadAndNotItsWords() {
        assertThat(
                        OpenSshFloor.ask(
                                new FakeCommandRunner()
                                        .failing("ssh -V", 1, "OpenSSH_8.8p1 said but failed")))
                .isInstanceOf(OpenSshFloor.Unread.class);
    }

    @Test
    void anSshThatIsNotThereIsUnread() {
        assertThat(OpenSshFloor.ask(new FakeCommandRunner().missing("ssh")))
                .isInstanceOf(OpenSshFloor.Unread.class);
    }

    @Test
    void doctorFailsAVersionBelowTheFloorInWords(@TempDir final Path dir) {
        assertThat(probe(dir, saying("OpenSSH_8.8p1 Ubuntu-1")))
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.MISSING);
                            assertThat(probe.detail()).contains("8.8p1").contains("8.9");
                            assertThat(probe.action()).contains("8.9");
                        });
    }

    @Test
    void doctorSaysWhatItFoundAtTheFloor(@TempDir final Path dir) {
        assertThat(probe(dir, saying("OpenSSH_8.9p1 Ubuntu-3ubuntu0.13, OpenSSL 3.0.2")))
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.OK);
                            assertThat(probe.detail()).isEqualTo("8.9p1");
                        });
    }

    @Test
    void doctorSaysPlainlyThatItCouldNotReadTheVersion(@TempDir final Path dir) {
        assertThat(probe(dir, saying("Sun_SSH_1.1.5")))
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.UNKNOWN);
                            assertThat(probe.detail())
                                    .contains("could not read")
                                    .contains("8.9")
                                    .contains("not refused");
                            assertThat(probe.action()).contains("ssh -V");
                        });
    }

    @Test
    void theDocumentsStateTheFloor() throws IOException {
        assertThat(Files.readString(ROOT.resolve("doc/getting-started.md")))
                .contains("OpenSSH " + OpenSshFloor.MINIMUM + " or newer");
    }

    private static Probe probe(final Path dir, final FakeCommandRunner runner) {
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0));
        return doctor.probes().stream()
                .filter(probe -> OpenSshFloor.NAME.equals(probe.name()))
                .findFirst()
                .orElseThrow();
    }
}
