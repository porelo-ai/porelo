package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.HostVaultOwner;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An alternate vault must never release the account vault's opening share. */
class HostVaultScopeTest {
    @Test
    void anOptionalVaultMayBeAbsentOrNull(@TempDir Path dir) {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0);
        final Map<String, Object> parameters = new java.util.HashMap<>();
        assertThatCode(() -> VaultMethods.requireRequestedVault(context, parameters))
                .as("an omitted optional path keeps the existing account-wide methods usable")
                .doesNotThrowAnyException();
        parameters.put("vault", null);
        assertThatCode(() -> VaultMethods.requireRequestedVault(context, parameters))
                .as("varlink null has the same meaning as an absent optional path")
                .doesNotThrowAnyException();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "moved", "swapped"})
    void forgettingDoesNotNeedTheVaultFile(String state, @TempDir Path dir) throws Exception {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0);
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext owned = context.withHostVault(owner);
            context.vault().write(Map.of(), new byte[32]);
            owner.keep(new byte[32]);
            final Path saved = dir.resolve("saved.bin");
            if (state.equals("missing")) java.nio.file.Files.delete(context.vault().path());
            else java.nio.file.Files.move(context.vault().path(), saved);
            if (state.equals("swapped")) context.vault().write(Map.of(), new byte[32]);
            assertThat(
                            VaultMethods.forgetHostVault(
                                    owned, Map.of("vault", context.vault().path().toString())))
                    .as("forget releases the held share even when the encrypted file is %s", state)
                    .isTrue();
            if (!state.equals("missing"))
                java.nio.file.Files.move(
                        saved,
                        context.vault().path(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThat(owner.isOpen())
                    .as("restoring the original file cannot restore its erased share")
                    .isFalse();
        }
    }

    @Test
    void clearingAnotherVaultLeavesThisOwnerUsable(@TempDir Path dir) {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context =
                    new PoreloContext(
                                    new FakeCommandRunner(),
                                    new PoreloPaths(
                                            XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                    args -> 0)
                            .withHostVault(owner);
            final byte[] share = new byte[32];
            java.util.Arrays.fill(share, (byte) 29);
            context.vault().write(Map.of(), share);
            owner.keep(share);
            new org.fuin.porelo.vault.VaultFile(dir.resolve("other.bin"))
                    .write(Map.of(), new byte[32]);
            VaultMethods.forgetHostVault(
                    context, Map.of("vault", dir.resolve("other.bin").toString()));
            assertThat(owner.share()).as("another vault cannot clear this share").isEqualTo(share);
            VaultMethods.forgetHostVault(
                    context,
                    Map.of(
                            "vault",
                            context.vault().path().toAbsolutePath().normalize().toString()));
            assertThat(owner.isOpen()).as("the owning vault can release its share").isFalse();
        }
    }
}
