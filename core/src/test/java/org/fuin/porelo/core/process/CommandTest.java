package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for what a command shows of itself. */
class CommandTest {

    @Test
    void itsTextNamesTheEnvironmentAndInputButHoldsNoValue() {

        // A log line built from a command - an exception's message, a debugger's view - must not
        // carry the secrets handed to it, whichever way they went.
        final Command command =
                Command.of("transport", "poll")
                        .withEnvironment(Map.of("TOKEN", "env-secret-12345678"))
                        .withInput("{\"secrets\":{\"TOKEN\":\"input-secret-12345678\"}}");

        assertThat(command.toString())
                .contains("transport poll")
                .contains("TOKEN")
                .doesNotContain("env-secret-12345678")
                .doesNotContain("input-secret-12345678");
    }
}
