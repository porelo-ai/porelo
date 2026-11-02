package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Harm guarded: this person's forge token sent to a server it was not declared for. */
class OrganizationAccessTest {

    @TempDir private Path dir;

    /**
     * A port is another server than the one a forge's name stands for: a token held under the
     * forge's name, or declared for the host alone, must not go there.
     */
    @ParameterizedTest
    @CsvSource({
        "https://forge.example.org/acme/o.git, , tok-forge",
        "https://forge.example.org:9999/acme/o.git, , ",
        "https://forge.example.org:9999/acme/o.git, https://forge.example.org, ",
        "https://forge.example.org:9999/acme/o.git, https://forge.example.org:9999, tok-declared",
        "https://forge.example.org:9999/acme/o.git, https://forge.example.org:9999/acme/, tok-declared",
        "ssh://git@forge.example.org:2222/acme/o.git, , ",
        "git@forge.example.org:acme/o.git, , tok-forge",
    })
    void a_token_goes_to_an_address_with_a_port_only_when_declared_for_that_port(
            final String address, final String declared, final String expected) throws Exception {
        final Path file = dir.resolve("credentials.yml");
        Files.writeString(
                file,
                declared == null
                        ? "credentials: []\n"
                        : "credentials:\n  - match: "
                                + declared
                                + "\n    kind: token\n    source: vault\n    vault: declared\n");
        final Map<String, VaultEntry> held =
                Map.of(
                        "git.token.forge.example.org",
                        new VaultEntry("tok-forge", "token"),
                        "declared",
                        new VaultEntry("tok-declared", "token"));

        assertThat(
                        OrganizationAccess.forgeToken(
                                held,
                                CredentialRegistry.read(file),
                                name -> null,
                                ForgeAddress.of(address)))
                .isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void a_token_declared_in_a_variable_is_read_from_the_persons_environment() throws Exception {
        final Path file = dir.resolve("credentials.yml");
        Files.writeString(
                file,
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n"
                        + "    source: env\n    env: FORGE_TOKEN\n");

        assertThat(
                        OrganizationAccess.forgeToken(
                                Map.of(),
                                CredentialRegistry.read(file),
                                name -> "FORGE_TOKEN".equals(name) ? " tok-variable\n" : null,
                                ForgeAddress.of("https://forge.example.org/acme/o.git")))
                .isEqualTo("tok-variable");
    }
}
