package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** The runtime's time limits, and a task image's apt settings, under the account's factors. */
class RuntimeTimeFactorsTest {

    private static final Project PROJECT =
            new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);

    @Test
    void anAptDownloadsTimeoutFollowsTheServicesFactor() {
        assertThat(Containerfile.aptTimeout(LimitFactors.parse("services=2"))).isEqualTo(40);
        assertThat(Containerfile.aptTimeout(LimitFactors.DEFAULT)).isEqualTo(20);
        assertThat(
                        Containerfile.render(
                                PROJECT, ImageLayers.none(), LimitFactors.parse("services=2")))
                .contains("'Acquire::http::Timeout \"40\";'")
                .contains("'Acquire::https::Timeout \"40\";'");
        assertThat(Containerfile.render(PROJECT, ImageLayers.none(), LimitFactors.DEFAULT))
                .contains("'Acquire::http::Timeout \"20\";'")
                .contains("'Acquire::https::Timeout \"20\";'");
    }

    @Test
    void anAptDownloadsRetriesFollowTheRetriesFactor() {
        // Three tries, the first and two again; twice as many is six, five of them again.
        assertThat(Containerfile.aptRetries(LimitFactors.parse("retries=2"))).isEqualTo(5);
        assertThat(Containerfile.aptRetries(LimitFactors.DEFAULT)).isEqualTo(2);
        assertThat(
                        Containerfile.render(
                                PROJECT, ImageLayers.none(), LimitFactors.parse("retries=2")))
                .contains("'Acquire::Retries \"5\";'");
        assertThat(Containerfile.render(PROJECT, ImageLayers.none(), LimitFactors.DEFAULT))
                .contains("'Acquire::Retries \"2\";'");
    }

    @Test
    void anImageBuiltUnderOtherFactorsHasAnotherRecipe() {
        // The factors are in the recipe, so an image built before they changed reads as stale.
        assertThat(Containerfile.fingerprint(PROJECT, LimitFactors.parse("services=2")))
                .isNotEqualTo(Containerfile.fingerprint(PROJECT, LimitFactors.DEFAULT));
        assertThat(Containerfile.fingerprint(PROJECT, LimitFactors.DEFAULT))
                .isEqualTo(Containerfile.fingerprint(PROJECT));
    }

    @Test
    void aContainersStopGraceFollowsTheMachinesFactor() {
        final FakeCommandRunner runner = new FakeCommandRunner();

        new Podman(runner).limitedBy(LimitFactors.parse("machines=2")).stop("porelo-uc-shell-1");
        new Podman(runner).stop("porelo-uc-shell-2");

        assertThat(runner.lines())
                .anySatisfy(line -> assertThat(line).contains("stop --time 10 porelo-uc-shell-1"))
                .anySatisfy(line -> assertThat(line).contains("stop --time 5 porelo-uc-shell-2"));
    }
}
