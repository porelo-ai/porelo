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

    /**
     * Harm guarded: git runs in a clean environment, so the helper found the variable empty, every
     * read and write answered that the organization could not be reached, and nobody was told why.
     */
    @org.junit.jupiter.api.Test
    void a_token_declared_in_a_variable_reaches_the_helper_git_starts() throws Exception {
        final Path binary = dir.resolve("porelo");
        Files.writeString(binary, "#!/bin/sh\necho username=x\necho password=$FORGE_TOKEN\n");
        Files.setPosixFilePermissions(
                binary, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, binary.toString());
        try {
            final PoreloContext context =
                    new PoreloContext(
                            new org.fuin.porelo.core.process.ProcessCommandRunner(),
                            new PoreloPaths(
                                    org.fuin.porelo.core.config.XdgPaths.of(name -> null, dir),
                                    dir.resolve("bin")),
                            arguments -> 0,
                            name -> "FORGE_TOKEN".equals(name) ? "tok-variable" : null);
            final Path file = context.paths().xdg().config().resolve("credentials.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n"
                            + "    source: env\n    env: FORGE_TOKEN\n");
            final String address = "https://forge.example.org/acme/o.git";

            try (var lease = OrganizationAccess.lent(context, null).lend(address)) {
                assertThat(lease.environment())
                        .as("no bookkeeping of ours goes to git")
                        .containsOnlyKeys("FORGE_TOKEN");
                final java.util.List<String> command =
                        new java.util.ArrayList<>(java.util.List.of("git"));
                command.addAll(lease.arguments());
                command.addAll(java.util.List.of("credential", "fill"));
                final ProcessBuilder builder =
                        new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().clear();
                builder.environment().put("PATH", System.getenv("PATH"));
                builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
                builder.environment().put("GIT_TERMINAL_PROMPT", "0");
                builder.environment().putAll(lease.environment());
                final Process git = builder.start();
                git.getOutputStream()
                        .write(
                                "protocol=https\nhost=forge.example.org\n\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                git.getOutputStream().close();
                final String said =
                        new String(
                                git.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertThat(git.waitFor()).as(said).isZero();
                assertThat(said).contains("password=tok-variable");
            }
        } finally {
            System.clearProperty(PoreloBinary.NAMED);
        }
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
