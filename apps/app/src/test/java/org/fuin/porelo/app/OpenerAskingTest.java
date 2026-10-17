package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PoreloContext#openerAsking}: a shut vault opened where it is needed, at a
 * terminal only.
 */
class OpenerAskingTest {

    private static final char[] PASSPHRASE = "correct horse".toCharArray();

    @TempDir Path dir;

    @org.junit.jupiter.api.AfterEach
    void forget() {
        PoreloContext.forgetAsked();
    }

    private final StringWriter said = new StringWriter();

    private final List<String> shown = new ArrayList<>();

    private PoreloContext context(final VaultPrompt prompt) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt);
        context.vault().write(Map.of("a", VaultEntry.of("one")), PASSPHRASE);
        return context;
    }

    private VaultPrompt answering(final String... answers) {
        final Deque<String> left = new ArrayDeque<>(List.of(answers));
        return prompt -> {
            shown.add(prompt);
            return left.isEmpty() ? null : left.pop().toCharArray();
        };
    }

    @Test
    void asksNobodyWithoutATerminalAndStaysShut() {
        // A script, a pipe, the daemon: nothing waits for input, and the caller refuses as before.
        assertThat(context(VaultPrompt.NOBODY).openerAsking(false, new PrintWriter(said)))
                .isEmpty();
    }

    @Test
    void opensItForThisCommandWithThePassphraseTyped() {
        final PoreloContext context = context(answering("correct horse"));

        final var opener = context.openerAsking(false, new PrintWriter(said));

        assertThat(opener).isPresent();
        assertThat(context.vault().read(opener.get())).containsKey("a");
        assertThat(shown).containsExactly("Vault passphrase (for this command only): ");
        assertThat(context.readableCredentials())
                .as("the rest of this command reads it")
                .isPresent();
        assertThat(context.openerAsking(false, new PrintWriter(said)))
                .as("asked once per command")
                .isPresent();
        assertThat(shown).hasSize(1);
        PoreloContext.forgetAsked();
        assertThat(context.opener()).as("nothing kept beyond this process").isEmpty();
    }

    @Test
    void asksAgainAfterAWrongPassphraseAndSaysSo() {
        final PoreloContext context = context(answering("wrong", "also wrong", "correct horse"));

        assertThat(context.openerAsking(false, new PrintWriter(said))).isPresent();
        assertThat(shown).hasSize(3);
        assertThat(said.toString()).contains("that passphrase does not open");
    }

    @Test
    void refusesAfterThreeWrongPassphrases() {
        final PoreloContext context = context(answering("one", "two", "three", "correct horse"));

        assertThat(context.openerAsking(false, new PrintWriter(said))).isEmpty();
        assertThat(shown).as("never a fourth time").hasSize(3);
    }

    @Test
    void unlocksItAsVaultUnlockDoesWhenATaskNeedsItAfterwards() {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context(answering("correct horse"));
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            assertThat(context.openerAsking(true, new PrintWriter(said))).isPresent();

            assertThat(shown)
                    .containsExactly(
                            "Vault passphrase (unlocks it as 'porelo vault unlock' does): ");
            assertThat(context.opener()).as("the next command finds it open").isPresent();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void asksNothingWhenTheVaultIsOpenAlready() {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context(answering("correct horse"));
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        keyring.store(PASSPHRASE.clone(), null);
        try {
            assertThat(context.openerAsking(true, new PrintWriter(said))).isPresent();
            assertThat(shown).isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void asksNothingWhereThereIsNoVault() {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0)
                        .asking(answering("correct horse"));

        assertThat(context.openerAsking(false, new PrintWriter(said))).isEmpty();
        assertThat(shown).as("a vault that is not there is not one to unlock").isEmpty();
        assertThat(new VaultFile(context.vault().path()).exists()).isFalse();
    }

    @Test
    void asksOnlyWhereTheCommandNeedsTheVault() {
        // A task that takes no credential is started without a word about the vault, as before.
        final PoreloContext context = context(answering("correct horse"));

        context.openIfShut(false, false, new PrintWriter(said));
        assertThat(shown).isEmpty();

        context.openIfShut(true, false, new PrintWriter(said));
        assertThat(shown).hasSize(1);
        assertThat(context.readableCredentials()).isPresent();
    }
}
