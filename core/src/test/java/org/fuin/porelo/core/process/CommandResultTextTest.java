package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a command's result prints of itself, as a program's output may hold a secret. */
class CommandResultTextTest {

    @Test
    void itPrintsNeitherStream() {
        final CommandResult result =
                new CommandResult(Command.of("vault", "read"), 3, "out-secret", "err-secret");

        assertThat(result.toString()).contains("3").doesNotContain("secret");
    }
}
