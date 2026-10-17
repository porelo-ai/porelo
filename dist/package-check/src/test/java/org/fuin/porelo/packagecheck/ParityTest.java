package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParityTest {

    /** What the deb installs, measured from a build of 2026-09-28 and trimmed. */
    private static final List<String> DEB =
            List.of(
                    "/usr/bin/porelo",
                    "/usr/share/bash-completion/completions/porelo",
                    "/usr/share/doc/porelo/copyright",
                    "/usr/share/porelo/sbom/porelo.cdx.json",
                    "/usr/share/zsh/vendor-completions/_porelo");

    private static final List<String> RPM =
            List.of(
                    "/usr/bin/porelo",
                    "/usr/share/bash-completion/completions/porelo",
                    "/usr/share/licenses/porelo/LICENSE",
                    "/usr/share/porelo/sbom/porelo.cdx.json",
                    "/usr/share/zsh/site-functions/_porelo");

    private final Reports out = new Reports();

    @Test
    void passesTwoPackagesThatDifferOnlyWhereTheirEcosystemsDo() {
        Parity.check(DEB, RPM, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("both packages install the same 4 payload file(s)");
    }

    @Test
    void namesAFileOnlyOnePackageInstalls() {
        Parity.check(with(DEB, "/usr/libexec/porelo/hooks/porelo-hook-nft"), RPM, out.report);

        assertThat(out.text())
                .contains("FAIL  the deb and the rpm do not install the same files")
                .contains("only in the deb: /usr/libexec/porelo/hooks/porelo-hook-nft");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsAZshCompletionOnlyOnePackageShipsRatherThanExcludingIt() {
        Parity.check(without(DEB, "/usr/share/zsh/vendor-completions/_porelo"), RPM, out.report);

        assertThat(out.text())
                .contains("only in the rpm: /usr/share/zsh/<completions>/_porelo")
                .contains("FAIL  the deb ships no /usr/share/zsh/vendor-completions/_porelo");
    }

    @Test
    void failsAZshCompletionInTheOtherEcosystemsDirectory() {
        final List<String> deb =
                with(
                        without(DEB, "/usr/share/zsh/vendor-completions/_porelo"),
                        "/usr/share/zsh/site-functions/_porelo");

        Parity.check(deb, RPM, out.report);

        assertThat(out.text())
                .contains("both packages install the same")
                .contains("FAIL  the deb ships no /usr/share/zsh/vendor-completions/_porelo");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsAMissingLicenseOnEitherSide() {
        Parity.check(
                without(DEB, "/usr/share/doc/porelo/copyright"),
                without(RPM, "/usr/share/licenses/porelo/LICENSE"),
                out.report);

        assertThat(out.text())
                .contains("FAIL  the deb ships no /usr/share/doc/<package>/copyright")
                .contains("FAIL  the rpm ships no /usr/share/licenses/<package>/LICENSE");
        assertThat(out.report.failures()).isEqualTo(2);
    }

    @Test
    void failsADocumentationDirectoryThatHoldsNoCopyright() {
        final List<String> deb =
                with(
                        without(DEB, "/usr/share/doc/porelo/copyright"),
                        "/usr/share/doc/porelo/changelog.gz");

        Parity.check(deb, RPM, out.report);

        assertThat(out.text())
                .contains("FAIL  the deb ships no /usr/share/doc/<package>/copyright");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsALicenseDirectoryThatHoldsNoLicense() {
        final List<String> rpm =
                with(
                        without(RPM, "/usr/share/licenses/porelo/LICENSE"),
                        "/usr/share/licenses/porelo/NOTICE");

        Parity.check(DEB, rpm, out.report);

        assertThat(out.text())
                .contains("FAIL  the rpm ships no /usr/share/licenses/<package>/LICENSE");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsAnRpmZshCompletionInDebiansDirectory() {
        final List<String> rpm =
                with(
                        without(RPM, "/usr/share/zsh/site-functions/_porelo"),
                        "/usr/share/zsh/vendor-completions/_porelo");

        Parity.check(DEB, rpm, out.report);

        assertThat(out.text())
                .contains("FAIL  the rpm ships no /usr/share/zsh/site-functions/_porelo");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsAMissingBashCompletion() {
        final String bash = "/usr/share/bash-completion/completions/porelo";

        Parity.check(without(DEB, bash), without(RPM, bash), out.report);

        assertThat(out.text())
                .contains("FAIL  the deb ships no bash completion")
                .contains("FAIL  the rpm ships no bash completion");
    }

    private static List<String> with(List<String> files, String file) {
        final List<String> changed = new ArrayList<>(files);
        changed.add(file);
        return changed;
    }

    private static List<String> without(List<String> files, String file) {
        final List<String> changed = new ArrayList<>(files);
        changed.remove(file);
        return changed;
    }
}
