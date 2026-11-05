package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link TaskToken}, which authenticates every push an agent makes. */
class TaskTokenTest {

    @Test
    void keepsItselfOutOfAnyLineItLandsIn() {

        final TaskToken token = TaskToken.mint();

        // Not a character of it: a prefix is part of a secret.
        assertThat(token.toString()).isEqualTo("TaskToken");
    }

    @Test
    void comparesInConstantTime() {

        final TaskToken token = TaskToken.mint();

        assertThat(token.matches(token.value())).isTrue();
        assertThat(token.matches("something else")).isFalse();
    }

    @Test
    void mintsADifferentTokenEveryTime() {

        assertThat(TaskToken.mint().value()).isNotEqualTo(TaskToken.mint().value());
    }
}
