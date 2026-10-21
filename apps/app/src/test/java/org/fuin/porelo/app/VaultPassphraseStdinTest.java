package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code --passphrase-stdin} on {@code vault unlock} and {@code vault init}, against the
 * real kernel keyring.
 *
 * <p>A program hands the passphrase over on standard input, as over an ssh channel; nothing it says
 * may be printed back. The keyring description is derived from the vault's path in a temporary
 * directory, so nothing here touches an operator's own cached passphrase.
 */
class VaultPassphraseStdinTest {

    private static final String PASSPHRASE = "correct horse battery staple";

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(final Path dir, final String typed, final boolean terminal) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0)
                .reading(
                        new StandardInput(
                                new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)),
                                terminal));
    }

    private int execute(final PoreloContext context, final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private static KernelKeyring keyring(final PoreloContext context) {
        return new KernelKeyring(context.paths().vault().vaultKeyringKey());
    }

    @Test
    void unlocksWithTheLineAProgramHandsOver(@TempDir final Path dir) {

        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir, PASSPHRASE + "\n", false);
        context.vault()
                .write(
                        Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")),
                        PASSPHRASE.toCharArray());
        try {
            assertThat(execute(context, "vault", "unlock", "--passphrase-stdin"))
                    .as("exit code; " + err)
                    .isZero();
            assertThat(keyring(context).read()).as("cached").isPresent();
            assertThat(out.toString() + err).as("never printed").doesNotContain(PASSPHRASE);
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void initMakesAVaultFromOneLineAndCachesIt(@TempDir final Path dir) {

        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir, PASSPHRASE + "\n", false);
        try {
            assertThat(execute(context, "vault", "init", "--passphrase-stdin"))
                    .as("exit code; " + err)
                    .isZero();
            assertThat(context.vault().accepts(PASSPHRASE.toCharArray()))
                    .as("the vault opens with the line")
                    .isTrue();
            assertThat(keyring(context).read()).as("cached").isPresent();
            assertThat(out.toString() + err).as("never printed").doesNotContain(PASSPHRASE);
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void refusesAtATerminalAndNamesThePrompt(@TempDir final Path dir) {

        // Typed there, the passphrase would echo and land in the terminal's scrollback.
        final PoreloContext context = context(dir, PASSPHRASE + "\n", true);
        context.vault()
                .write(
                        Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")),
                        PASSPHRASE.toCharArray());

        assertThat(execute(context, "vault", "unlock", "--passphrase-stdin"))
                .as("exit code")
                .isEqualTo(64);
        assertThat(err.toString()).as("the refusal").contains("standard input is a terminal");
    }

    @Test
    void refusesASecondSourceBesideItAndNamesIt(@TempDir final Path dir) {

        final PoreloContext context = context(dir, PASSPHRASE + "\n", false);
        context.vault()
                .write(
                        Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")),
                        PASSPHRASE.toCharArray());

        assertThat(
                        execute(
                                context,
                                "vault",
                                "unlock",
                                "--passphrase-stdin",
                                "--passphrase-command",
                                "cat"))
                .as("exit code")
                .isEqualTo(64);
        assertThat(err.toString()).as("the refusal").contains("--passphrase-command");
    }

    @Test
    void anEmptyLineLeavesTheVaultShut(@TempDir final Path dir) {

        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir, "\n", false);
        context.vault()
                .write(
                        Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")),
                        PASSPHRASE.toCharArray());
        try {
            assertThat(execute(context, "vault", "unlock", "--passphrase-stdin"))
                    .as("exit code")
                    .isEqualTo(70);
            assertThat(err.toString())
                    .as("the refusal")
                    .contains("standard input gave no passphrase");
            assertThat(keyring(context).read()).as("nothing cached").isEmpty();
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void initFromNothingMakesNoVault(@TempDir final Path dir) {

        final PoreloContext context = context(dir, "", false);

        assertThat(execute(context, "vault", "init", "--passphrase-stdin"))
                .as("exit code")
                .isEqualTo(70);
        assertThat(context.vault().exists()).as("no vault").isFalse();
    }
}
