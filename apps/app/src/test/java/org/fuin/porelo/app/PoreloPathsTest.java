package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link PoreloPaths}. */
@Isolated("The keyring prefix is shared by every vault in this JVM")
class PoreloPathsTest {

    @Test
    void prefersTheOperatorsOwnBuildOverThePackage(@TempDir Path dir) throws IOException {

        // Given
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(own.resolve("porelo-hook-nft"), "");
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        // When & Then
        assertThat(PoreloPaths.hookBinaries(own, packaged)).isEqualTo(own);
    }

    @Test
    void fallsBackToThePackagedLocation(@TempDir Path dir) throws IOException {

        // A packaged install is the normal case: the hooks are in /usr/libexec/porelo/hooks and
        // nothing is in ~/.local/bin. Looking only at the latter made "porelo setup" fail there.
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        assertThat(PoreloPaths.hookBinaries(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void namesTheOperatorsDirectoryWhenNothingIsInstalled(@TempDir Path dir) {

        // So the error tells a developer where their build should have put them.
        final Path own = dir.resolve("local");
        assertThat(PoreloPaths.hookBinaries(own, dir.resolve("libexec"))).isEqualTo(own);
    }

    @Test
    void anEmptyDirectoryDoesNotCount(@TempDir Path dir) throws IOException {

        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        // ~/.local/bin exists on almost every machine and is usually not empty; existence of the
        // directory says nothing about whether the hooks are in it.
        Files.writeString(own.resolve("something-else"), "");

        assertThat(PoreloPaths.hookBinaries(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void namesThePackagedHooksItHides(@TempDir Path dir) throws IOException {

        // Silent shadowing runs yesterday's firewall code after installing today's package.
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(own.resolve("porelo-hook-nft"), "");
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        assertThat(PoreloPaths.shadowedHooks(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void reportsNothingShadowedOnAPlainPackagedInstall(@TempDir Path dir) throws IOException {

        // A warning that is always printed is a warning nobody reads.
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        assertThat(PoreloPaths.shadowedHooks(packaged, packaged)).isNull();
    }

    @Test
    void keysThePassphraseCacheToTheVaultItUnlocks(@TempDir Path dir) {

        // One shared name meant unlocking one vault silently replaced the cached passphrase of
        // another. An acceptance run could log the operator out of their own vault that way.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final PoreloPaths one = new PoreloPaths(xdg, dir.resolve("bin"));

        assertThat(one.vault().vaultKeyringKey())
                .as("the process's configured prefix isolates its cached passphrases")
                .startsWith(System.getProperty("porelo.keyring.prefix", "porelo") + ":vault:");
        assertThat(one.vault().vaultKeyringKey())
                .as("the same vault has a stable cache key")
                .isEqualTo(one.vault().vaultKeyringKey());
    }

    @Test
    void anUnsetKeyringPrefixKeepsTheAccountsExistingCacheNames(@TempDir Path dir) {
        final String before = System.clearProperty("porelo.keyring.prefix");
        try {
            final VaultPaths vault =
                    new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")).vault();
            assertThat(vault.vaultKeyringKey())
                    .as("the product's default cache name stays compatible")
                    .startsWith("porelo:vault:");
            assertThat(vault.vaultShareKeyringKey())
                    .as("shares use the same default prefix")
                    .isEqualTo(vault.vaultKeyringKey() + ":share");
        } finally {
            restoreKeyringPrefix(before);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a",
                "porelo-test-another-jvm",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void aConfiguredPrefixKeepsBothVaultSecretsApartFromOtherProcesses(
            String prefix, @TempDir Path dir) {
        final String before = System.setProperty("porelo.keyring.prefix", prefix);
        try {
            final VaultPaths vault =
                    new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")).vault();
            assertThat(vault.vaultKeyringKey())
                    .as("only the configured process prefix owns this cache")
                    .startsWith(prefix + ":vault:");
            assertThat(vault.vaultShareKeyringKey())
                    .as("the device share is isolated with its vault")
                    .isEqualTo(vault.vaultKeyringKey() + ":share");
        } finally {
            restoreKeyringPrefix(before);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Porelo",
                "with space",
                "porelo:vault",
                "porelo\n",
                "../other",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void anInvalidKeyringPrefixFallsBackToTheAccountsDefault(String prefix, @TempDir Path dir) {
        final String before = System.clearProperty("porelo.keyring.prefix");
        try {
            final VaultPaths vault =
                    new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")).vault();
            final String defaultName = vault.vaultKeyringKey();
            System.setProperty("porelo.keyring.prefix", prefix);
            assertThat(vault.vaultKeyringKey())
                    .as("arbitrary property input never becomes a key description")
                    .isEqualTo(defaultName);
            assertThat(vault.vaultShareKeyringKey())
                    .as("invalid input cannot change the share's prefix either")
                    .isEqualTo(defaultName + ":share");
        } finally {
            restoreKeyringPrefix(before);
        }
    }

    private static void restoreKeyringPrefix(String before) {
        if (before == null) {
            System.clearProperty("porelo.keyring.prefix");
        } else {
            System.setProperty("porelo.keyring.prefix", before);
        }
    }

    @Test
    void putsAReviewCopyWhereSomebodyWillFindIt(@TempDir Path dir) {

        // Two things this pins, and the second is easy to undo by accident. It must not land in the
        // operator's repository - the default used to be beside the project file, so a review left
        // 'porelo-review-shell/' untracked in a checkout Porelo promises not to touch; one had sat
        // on a test machine for two days. And it must not land under the data directory either:
        // this is the one thing Porelo makes for a person to open in their own editor, and a file
        // dialog does not show .local/share.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final Path review =
                new PoreloPaths(xdg, dir.resolve("bin")).gate().reviewCheckout("utils4j", "shell");

        assertThat(review)
                .isEqualTo(dir.resolve("porelo").resolve("reviews").resolve("utils4j-shell"));
        assertThat(review.startsWith(xdg.data()))
                .as("visible, not under the data directory")
                .isFalse();
    }

    @Test
    void usesTheOperatorsOwnVaultWhenNothingOverridesIt(@TempDir Path dir) {

        // The negative case: an override that leaked in by accident would send every command at a
        // vault the operator never chose.
        assertThat(
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin"))
                                .vault()
                                .vaultFile())
                .isEqualTo(XdgPaths.of(name -> null, dir).data().resolve("vault.bin"));
    }
}
