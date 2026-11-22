package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Holds that the server types a run names reach the rental in their order: a type list read wrong
 * rents the shared-vCPU types, on which a build took five times its time.
 */
class LegTypeOrderTest {

    @Test
    void theTypesARunNamesAreTriedInTheirOrder() {
        assertThat(Main.providerOverride(" ccx33, ,ccx43 "))
                .as("comma-separated, blanks dropped, order kept")
                .containsExactly("ccx33", "ccx43");
    }

    @Test
    void withoutTheOptionTheBuildKindChooses() {
        assertThat(Main.providerOverride(null)).as("no option, no override").isEmpty();
    }

    @Test
    void anOptionNamingNoTypeLetsTheBuildKindChoose() {
        assertThat(Main.providerOverride(" , ")).as("an empty option, no override").isEmpty();
    }
}
