package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/** Tests how a vault entry's credential goes on a request to a package repository. */
class VaultRepositoriesCommandTest {

    @Test
    void aBasicCredentialIsSentEncodedAndKeptOutOfAnswersInEachForm() {
        final String encoded =
                Base64.getEncoder()
                        .encodeToString("builder:a-long-password".getBytes(StandardCharsets.UTF_8));

        final VaultRepositoriesCommand.Credential credential =
                VaultRepositoriesCommand.credential(
                        new VaultEntry("builder:a-long-password", "basic", Map.of()));

        assertThat(credential.refusal()).isNull();
        assertThat(credential.header()).isEqualTo("Authorization");
        assertThat(credential.value()).isEqualTo("Basic " + encoded);
        assertThat(credential.secrets())
                .containsExactlyInAnyOrder("builder:a-long-password", encoded, "a-long-password");
        assertThat(credential.toString()).doesNotContain("password", encoded);
    }

    @Test
    void aBasicPasswordTooShortToKeepOutOfAnswersIsRefusedWithoutItsValue() {
        final VaultRepositoriesCommand.Credential refused =
                VaultRepositoriesCommand.credential(
                        new VaultEntry("builder:shortpw0123", "basic", Map.of()));
        final VaultRepositoriesCommand.Credential served =
                VaultRepositoriesCommand.credential(
                        new VaultEntry("builder:twelve-chars", "basic", Map.of()));

        assertThat(refused.refusal())
                .isNotNull()
                .contains("12 characters")
                .contains("kept out of answers")
                .doesNotContain("shortpw0123");
        assertThat(served.refusal()).isNull();
        assertThat(served.secrets()).contains("twelve-chars");
    }

    @Test
    void aBearerAndAHeaderCredentialGoWhereTheirKindSays() {
        assertThat(
                        VaultRepositoriesCommand.credential(
                                new VaultEntry("t0ken-value", "bearer", Map.of())))
                .satisfies(
                        credential -> {
                            assertThat(credential.header()).isEqualTo("Authorization");
                            assertThat(credential.value()).isEqualTo("Bearer t0ken-value");
                        });
        assertThat(
                        VaultRepositoriesCommand.credential(
                                new VaultEntry(
                                        "api-key-value",
                                        "header",
                                        Map.of("header", "X-JFrog-Art-Api"))))
                .satisfies(
                        credential -> {
                            assertThat(credential.header()).isEqualTo("X-JFrog-Art-Api");
                            assertThat(credential.value()).isEqualTo("api-key-value");
                        });
    }

    @Test
    void anEntryThatIsNoRepositoryCredentialIsRefusedWithoutItsValue() {
        assertThat(
                        VaultRepositoriesCommand.credential(
                                        new VaultEntry("no-colon-here", "basic", Map.of()))
                                .refusal())
                .contains("user:password")
                .doesNotContain("no-colon-here");
        assertThat(
                        VaultRepositoriesCommand.credential(
                                        new VaultEntry("v", "header", Map.of("header", "a b")))
                                .refusal())
                .contains("--setting header=");
        assertThat(
                        VaultRepositoriesCommand.credential(
                                        new VaultEntry("sk-key", "api-key", Map.of()))
                                .refusal())
                .contains("--type basic, bearer or header")
                .doesNotContain("sk-key");
    }
}
