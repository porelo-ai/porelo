package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Tests for {@link PackageSteps}. */
class PackageStepsTest {

    private static final String BILL =
            """
            {"bomFormat": "CycloneDX", "specVersion": "1.6",
             "components": [
               {"name": "porelo-agent-claude", "components": [{"name": "claude-code"}]},
               {"name": "jspecify"}
             ]}""";

    @Test
    void readsEveryComponentNestedOnesIncluded() {
        // The fetched CLI sits inside the agent's own component, not beside it.
        assertThat(PackageSteps.components(BILL))
                .containsExactly("claude-code", "jspecify", "porelo-agent-claude");
    }

    @Test
    void refusesJsonThatIsNotACycloneDxBill() {
        assertThatThrownBy(
                        () ->
                                PackageSteps.components(
                                        "{\"bomFormat\": \"SPDX\", \"components\": [{\"name\": \"x\"}]}"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("not a CycloneDX bill");
        assertThatThrownBy(() -> PackageSteps.components("[]"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("not a CycloneDX bill");
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThatThrownBy(() -> PackageSteps.components("cat: /usr/share/doc/x: No such file"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("not JSON: ");
    }

    // The harm: a scenario that calls an account's copy a machine-wide install proves the lock
    // where Porelo says it is not held, or blames the package for what the account's copy does.

    @Test
    void takesThePackagesBinariesUnderUsrBinAsMachineWide() {
        assertThat(
                        PackageSteps.notMachineWide(
                                "package installed\nporelo /usr/bin/porelo\nporelod /usr/bin/porelod\n"))
                .as("the package installed and both of its binaries the ones a script runs")
                .isNull();
    }

    @Test
    void refusesAnAccountsCopyThatShadowsThePackage() {
        assertThat(
                        PackageSteps.notMachineWide(
                                "package installed\nporelo /home/build/.local/bin/porelo\n"
                                        + "porelod /usr/bin/porelod\n"))
                .as("a copy in the account's bin wears no label by its path")
                .isEqualTo("the porelo a script runs is not the package's /usr/bin/porelo");
        assertThat(
                        PackageSteps.notMachineWide(
                                "package installed\nporelo /usr/bin/porelo\n"
                                        + "porelod /home/build/.local/bin/porelod\n"))
                .as("the daemon an account runs from its own bin is not the package's")
                .isEqualTo("the porelod a script runs is not the package's /usr/bin/porelod");
    }

    @Test
    void refusesBinariesUnderUsrBinThatNoPackageInstalled() {
        assertThat(
                        PackageSteps.notMachineWide(
                                "package absent\nporelo /usr/bin/porelo\nporelod /usr/bin/porelod\n"))
                .as("files put there by hand are not the package the scenario is about")
                .isEqualTo("Porelo's package is not installed on this machine");
    }

    @Test
    void refusesAMachineWhereNoPoreloIsFound() {
        assertThat(PackageSteps.notMachineWide("package installed\nporelo none\nporelod none\n"))
                .as("nothing on the PATH is not a machine-wide install")
                .isEqualTo("the porelo a script runs is not the package's /usr/bin/porelo");
    }
}
