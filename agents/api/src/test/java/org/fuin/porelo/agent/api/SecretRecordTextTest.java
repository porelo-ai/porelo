package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What an agent's records print of themselves, as a line must never carry what they hold. */
class SecretRecordTextTest {

    private static final String SECRET = "s3cret-value";

    @Test
    void aSetupContextDoesNotPrintItsToken() {
        final SetupContext context =
                new SetupContext(SECRET, "api-key", "/workspace", "https://e.example", "p", 0);

        assertThat(context.toString()).contains("/workspace").doesNotContain(SECRET);
    }

    @Test
    void aContainerFileDoesNotPrintItsContent() {
        final ContainerFile file = new ContainerFile("/home/a/.token", SECRET, true);

        assertThat(file.toString()).contains("/home/a/.token").doesNotContain(SECRET);
    }
}
