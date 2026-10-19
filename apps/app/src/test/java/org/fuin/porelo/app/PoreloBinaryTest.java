package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PoreloBinaryTest {

    @AfterEach
    void forget() {
        System.clearProperty(PoreloBinary.NAMED);
    }

    @Test
    void aNamedProgramIsTheOneStarted() {
        System.setProperty(PoreloBinary.NAMED, "/build/target/porelo");

        assertThat(PoreloBinary.path()).isEqualTo("/build/target/porelo");
    }

    @Test
    void withNothingNamedAJvmFallsBackToThePorelosOnThePath() {
        // A test's JVM is 'java', with no 'porelo' beside it: what OwnPorelo exists for.
        assertThat(PoreloBinary.path()).isEqualTo("porelo");
    }
}
