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
    void withNothingNamedATestsJvmStartsNoPoreloOfThePersons() {
        // A test's JVM is 'java', with no 'porelo' beside it; a real run's fallback here, the one
        // on the path, would run the installed release on the person's account.
        assertThat(java.nio.file.Path.of(PoreloBinary.path()))
                .as("the program a test falls back to does not exist")
                .isAbsolute()
                .doesNotExist();
    }
}
