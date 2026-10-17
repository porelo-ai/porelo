package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.supervisor.DeviceGrant;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a grant is kept as - one decision, which the terminal and the daemon's {@code
 * Authorize} share.
 */
class VaultAuthorizeCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @Test
    void aTokenThatNeverExpiresIsKeptAsItIsAndOneThatExpiresWithNothingToRenewItIsRefused(
            @TempDir Path dir) {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);

            final VaultAuthorizeCommand.Kept lasting =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "copilot",
                            new DeviceGrant.Outcome("granted", null, "gho_1", null));
            final VaultAuthorizeCommand.Kept hourly =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "hourly",
                            new DeviceGrant.Outcome("granted", null, "at-1", Duration.ofHours(1)));
            final VaultAuthorizeCommand.Kept renewed =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "forge",
                            new DeviceGrant.Outcome(
                                    "granted", "rt-1", "at-1", Duration.ofHours(1)));

            assertThat(lasting.kept()).isTrue();
            assertThat(lasting.detail()).contains("does not expire").contains("cannot revoke");
            assertThat(hourly.kept()).isFalse();
            assertThat(hourly.detail()).contains("no refresh token");
            assertThat(renewed).isEqualTo(new VaultAuthorizeCommand.Kept(true, ""));
            final Map<String, org.fuin.porelo.vault.VaultEntry> grants =
                    context.readableGrants().orElseThrow();
            assertThat(grants.get("copilot").type()).isEqualTo(VaultAuthorizeCommand.HELD);
            assertThat(grants.get("copilot").value()).isEqualTo("gho_1");
            assertThat(grants.get("forge").type()).isEqualTo("refresh-token");
            assertThat(grants).doesNotContainKey("hourly");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aGrantIsKeptInAVaultADevicesShareOpened(@TempDir Path dir) {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // The daemon opens the vault with a device's share and has no terminal to ask a passphrase
        // at: the person granted at the service, and keeping it asked for the passphrase alone, so
        // the grant was lost.
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final byte[] share = new byte[Keyslots.SHARE_BYTES];
        new java.util.Random(7).nextBytes(share);
        context.vault().write(Map.of(), PASSPHRASE);
        context.vault()
                .enroll(
                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE),
                        share.clone(),
                        "the laptop",
                        "USER_SCOPED");
        VaultShare.keep(context.paths(), share, Duration.ofMinutes(5));
        try {
            final VaultAuthorizeCommand.Kept kept =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "forge",
                            new DeviceGrant.Outcome(
                                    "granted", "rt-1", "at-1", Duration.ofHours(1)));

            assertThat(kept)
                    .as("kept with what opened the vault")
                    .isEqualTo(new VaultAuthorizeCommand.Kept(true, ""));
            assertThat(
                            context.vault()
                                    .read(
                                            org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                                    PASSPHRASE))
                                    .get(TaskSecrets.GRANT_PREFIX + "forge")
                                    .value())
                    .isEqualTo("rt-1");
        } finally {
            VaultShare.forget(context.paths());
        }
    }
}
