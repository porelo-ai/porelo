package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@link VaultPassphraseCommand}: only the current passphrase changes it. */
class VaultPassphraseCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void anUnlockedVaultIsNoLeaveToChangeItsPassphraseWithAWrongOne(@TempDir Path dir) {
        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            assertThat(
                            execute(
                                    context,
                                    "vault",
                                    "unlock",
                                    "--passphrase-command",
                                    "printf 'correct horse battery staple\\n'"))
                    .isZero();

            assertThat(
                            execute(
                                    context,
                                    "vault",
                                    "passphrase",
                                    "--passphrase-command",
                                    "printf wrong",
                                    "--new-passphrase-command",
                                    "printf changed"))
                    .isEqualTo(70);
            assertThat(err.toString()).contains("does not open").contains("nothing was changed");
            assertThat(context.vault().accepts(PASSPHRASE)).isTrue();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void theOldPassphraseTypedInThisProcessNoLongerOpensAfterTheChange(@TempDir Path dir) {
        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.requireOpener(() -> java.util.Optional.of(PASSPHRASE.clone()));

            assertThat(
                            execute(
                                    context,
                                    "vault",
                                    "passphrase",
                                    "--passphrase-command",
                                    "printf 'correct horse battery staple'",
                                    "--new-passphrase-command",
                                    "printf changed"))
                    .isZero();
            // Whatever is held now opens it; kept, the old one would be tried first and read as a
            // damaged vault.
            context.opener()
                    .ifPresent(
                            opener ->
                                    assertThat(context.vault().read(opener))
                                            .containsKey("anthropic"));
        } finally {
            keyring.forget();
        }
    }
}
