package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
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
 * Tests for {@link VaultStatusCommand}, against the real kernel keyring.
 *
 * <p>A playbook branches on the exit code alone, so each case pins its code. The keyring
 * description is derived from the vault's path in a temporary directory, so nothing here touches
 * the passphrase an operator has cached for their own vault.
 */
class VaultStatusCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
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

    private KernelKeyring keyring(PoreloContext context) {
        return new KernelKeyring(context.paths().vault().vaultKeyringKey());
    }

    @Test
    void anAccountWithoutAVaultAnswersFourAndSaysWhere(@TempDir Path dir) {

        final PoreloContext context = context(dir);

        assertThat(execute(context, "vault", "status")).as("exit code").isEqualTo(4);
        assertThat(out.toString())
                .as("the line for a person")
                .startsWith("none")
                .contains(context.vault().path().toString());
    }

    @Test
    void aVaultNothingOpensAnswersThree(@TempDir Path dir) {

        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);

        assertThat(execute(context, "vault", "status")).as("exit code").isEqualTo(3);
        assertThat(out.toString()).as("the line for a person").startsWith("shut");
    }

    @Test
    void aVaultTheCachedPassphraseOpensAnswersZero(@TempDir Path dir) {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        try {
            keyring(context).store(PASSPHRASE.clone(), null);

            assertThat(execute(context, "vault", "status")).as("exit code").isEqualTo(0);
            assertThat(out.toString()).as("the line for a person").startsWith("open");
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void aCachedPassphraseThatNoLongerOpensTheVaultIsShutNotOpen(@TempDir Path dir) {

        // A playbook told "open" here would go on to a vault that refuses its first read.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        try {
            keyring(context).store("an old passphrase".toCharArray(), null);

            assertThat(execute(context, "vault", "status")).as("exit code").isEqualTo(3);
            assertThat(out.toString())
                    .as("the line for a person")
                    .startsWith("shut")
                    .contains("no longer opens");
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void aFileThatIsNoVaultIsARefusalNotAShutVault(@TempDir Path dir) throws Exception {

        // Told "shut", a play would ask a person for a passphrase that can open nothing.
        final PoreloContext context = context(dir);
        Files.createDirectories(context.vault().path().getParent());
        Files.writeString(context.vault().path(), "not a vault");

        assertThat(execute(context, "vault", "status")).as("exit code").isEqualTo(70);
        assertThat(out.toString()).as("no answer on standard output").isEmpty();
    }

    @Test
    void theJsonAnswerNamesExistsOpenAndPath(@TempDir Path dir) {

        // The names are the contract a playbook reads; a rename has to fail here, not in a play.
        final PoreloContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);

        assertThat(execute(context, "vault", "status", "--json")).as("exit code").isEqualTo(3);
        assertThat(out.toString().strip())
                .as("one JSON object")
                .isEqualTo(
                        "{\"exists\":true,\"open\":false,\"path\":\""
                                + context.vault().path()
                                + "\"}");
    }
}
