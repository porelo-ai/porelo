package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A repository's maturity: one of four words, read without a warning and refused otherwise. */
class ProjectRepositoryMaturityTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            repositories:
              core:
            """;

    private static String file(final String maturity) {
        return HEAD + "    maturity: \"" + maturity + "\"\n";
    }

    @ParameterizedTest
    @ValueSource(strings = {"crazy-chicken", "early-bird", "stable", "deprecated"})
    void readsEachOfTheFourWordsAsAKnownKey(final String maturity) {

        // Refusing one of them would stop every machine following the project.
        assertThat(ProjectReader.read(new StringReader(file(maturity)), "test").repository("core"))
                .as("the file is read")
                .isNotNull();
        assertThat(ProjectReader.unknownKeys(new StringReader(file(maturity)), "test"))
                .as("a known key is not reported as one that does nothing")
                .isEmpty();
    }

    @Test
    void refusesAWordThatIsNoneOfTheFour() {

        // A misspelled word would otherwise state no maturity while looking as if it did.
        assertThatThrownBy(() -> ProjectReader.read(new StringReader(file("early_bird")), "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.maturity'")
                .hasMessageContaining("early_bird");
    }
}
