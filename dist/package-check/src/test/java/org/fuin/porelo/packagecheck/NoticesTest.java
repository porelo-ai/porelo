package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoticesTest {

    private static final String RULE = "=".repeat(78);

    private final Reports out = new Reports();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/usr/share/doc/porelo/THIRD-PARTY-NOTICES",
                "/usr/share/licenses/porelo/THIRD-PARTY-NOTICES"
            })
    void refusesMissingNoticesAtEitherPackagesRequiredPlace(String path) {
        // Otherwise a package could be published without the compiled-in code's licenses.
        new Notices("the package", path, 2).check(null, out.report);

        assertThat(out.report.failures())
                .as("missing license notices refuse publication")
                .isEqualTo(1);
        assertThat(out.text()).as("the missing file is named").contains(path);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/usr/share/doc/porelo/THIRD-PARTY-NOTICES",
                "/usr/share/licenses/porelo/THIRD-PARTY-NOTICES"
            })
    void refusesFewerJarSectionsThanTheBuildSupplied(String path) {
        // A stale or truncated notices file must not pass because it merely exists.
        new Notices("the package", path, 2)
                .check(section("org.example:alpha:1") + runtime(), out.report);

        assertThat(out.report.failures())
                .as("the runtime does not stand in for a jar")
                .isEqualTo(1);
        assertThat(out.text())
                .as("the check reports measured and required counts")
                .contains(path)
                .contains("1 jar section(s)")
                .contains("2 third-party jar(s)");
    }

    @Test
    void acceptsAllJarSectionsWithoutCountingTheRuntime() {
        new Notices("the package", "/notices", 2)
                .check(
                        section("org.example:alpha:1")
                                + section("org.example:beta:2:linux-x86_64")
                                + runtime(),
                        out.report);

        assertThat(out.report.failures())
                .as("every supplied third-party jar has a section")
                .isZero();
    }

    @Test
    void refusesRepeatedCoordinatesAsAReplacementForAMissingJar() {
        new Notices("the package", "/notices", 2)
                .check(section("org.example:alpha:1").repeat(2) + runtime(), out.report);

        assertThat(out.report.failures())
                .as("two copies of one license do not cover two jars")
                .isEqualTo(1);
    }

    @Test
    void refusesCoordinatesInLicenseTextAsAReplacementForAMissingJar() {
        new Notices("the package", "/notices", 2)
                .check(
                        section("org.example:alpha:1")
                                + "org.example:beta:2\nLicense: MIT\n"
                                + runtime(),
                        out.report);

        assertThat(out.report.failures()).as("only generator section headers count").isEqualTo(1);
    }

    @Test
    void countsTheClasspathSuppliedToTheGenerator() {
        final String classpath = "/build/alpha.jar" + File.pathSeparator + "/build/beta.jar\n";

        assertThat(Notices.jarCount(classpath))
                .as("each supplied third-party jar needs notices")
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n  \n", "/build/classes"})
    void refusesAClasspathThatCannotEstablishTheJarCount(String classpath) {
        assertThatThrownBy(() -> Notices.jarCount(classpath))
                .as("missing build inputs must never weaken the notices check")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String section(String coordinates) {
        return "\n"
                + RULE
                + "\n"
                + coordinates
                + "\nLicense: MIT\n"
                + RULE
                + "\n\n--- standard text of MIT ---\nLicense text\n";
    }

    private static String runtime() {
        return "\n"
                + RULE
                + "\nGraalVM runtime 25\n"
                + RULE
                + "\n\n--- legal/java.base/LICENSE ---\nRuntime license text\n";
    }
}
