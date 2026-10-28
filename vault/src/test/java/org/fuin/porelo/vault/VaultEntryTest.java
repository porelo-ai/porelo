package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link VaultEntry}: an entry says what it is without ever saying its secret. */
class VaultEntryTest {

    @Test
    void anEntryIsTheSameAsAnotherOnlyWithTheSameSecretKindAndSettings() {
        final VaultEntry entry = new VaultEntry("secret-1", "oauth", Map.of("scope", "read"));

        assertThat(entry.sameAs(new VaultEntry("secret-1", "oauth", Map.of("scope", "read"))))
                .isTrue();
        assertThat(entry.sameAs(new VaultEntry("secret-2", "oauth", Map.of("scope", "read"))))
                .isFalse();
        assertThat(entry.sameAs(new VaultEntry("secret-1", null, Map.of("scope", "read"))))
                .isFalse();
        assertThat(entry.sameAs(new VaultEntry("secret-1", "oauth", Map.of()))).isFalse();
        assertThat(VaultEntry.of("x").sameAs(VaultEntry.of("x"))).isTrue();
    }

    @Test
    void anEntryPrintedSaysItsKindAndSettingsAndNeverItsSecret() {

        // A record prints every component, so the generated toString put the secret into whatever
        // printed one.
        final VaultEntry entry =
                new VaultEntry("sk-very-secret-value", "api-key", Map.of("host", "example.org"));

        assertThat(entry.toString())
                .doesNotContain("sk-very-secret-value")
                .contains("api-key")
                .contains("example.org");
    }
}
