package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SetupScriptTest {

    private static final String SOURCE = "#!/bin/sh\necho prepared\n";

    private final Reports out = new Reports();

    @Test
    void passesAPackageHoldingTheTreesScript() {
        Main.sameScript("the deb", SOURCE, SOURCE, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
    }

    @Test
    void failsAPackageHoldingAnotherScript() {
        // A copy left in a build directory from an older tree is the case this guards.
        Main.sameScript("the rpm", SOURCE.replace("prepared", "older"), SOURCE, out.report);

        assertThat(out.text())
                .contains("FAIL  the rpm's /usr/share/porelo/setup/porelo-setup.sh is not");
    }

    @Test
    void failsAPackageHoldingNoScript() {
        Main.sameScript("the deb", null, SOURCE, out.report);

        assertThat(out.text())
                .contains("FAIL  the deb holds no /usr/share/porelo/setup/porelo-setup.sh");
    }
}
