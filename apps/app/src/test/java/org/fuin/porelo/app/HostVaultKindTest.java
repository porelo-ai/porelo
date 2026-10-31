package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A device or passphrase vault must never be mistaken for a host vault needing reset. */
class HostVaultKindTest {
    @org.junit.jupiter.api.Test
    void aMixedVaultCannotCacheOrUseItsSshShareAsADeviceShare(@TempDir Path dir) throws Exception {
        final byte[] share = new byte[32];
        java.util.Arrays.fill(share, (byte) 71);
        final var context = context(dir);
        make(context.vault(), "mixed", share);
        final byte[] before = Files.readAllBytes(context.vault().path());
        try {
            assertThatThrownBy(
                            () ->
                                    new Keyslots(context)
                                            .unlock(
                                                    java.util.Base64.getEncoder()
                                                            .encodeToString(share),
                                                    null))
                    .as("an SSH share must never enter the device keyring")
                    .isInstanceOf(org.fuin.porelo.vault.NotAHostVaultException.class);
            assertThat(VaultShare.held(context.paths())).as("no share was cached").isEmpty();
            assertThat(Files.readAllBytes(context.vault().path()))
                    .as("a refused unlock never updates last-used or rewrites the vault")
                    .isEqualTo(before);
            assertThatThrownBy(() -> context.vault().read(VaultFile.Opener.share(share)))
                    .as("the generic opener cannot unwrap a mixed vault's SSH slot")
                    .isInstanceOf(org.fuin.porelo.vault.NotAHostVaultException.class);
            final var cli =
                    PoreloCli.commandLine(
                            context.reading(
                                    new StandardInput(new ByteArrayInputStream(share), false)));
            final var out = new StringWriter();
            cli.setOut(new PrintWriter(out));
            cli.setErr(new PrintWriter(new StringWriter()));
            assertThat(cli.execute("vault", "unlock", "--share-stdin", "--json"))
                    .as("the same SSH share receives the named CLI refusal")
                    .isEqualTo(65);
            assertThat(Json.parse(out.toString())).isEqualTo(Map.of("vault", "not-a-host-vault"));
            assertThat(VaultShare.held(context.paths())).as("the CLI cached no share").isEmpty();
            assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
        } finally {
            VaultShare.forget(context.paths());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"passphrase", "mixed", "device"})
    void shareInputRefusesOtherVaultKindsWithoutChangingThem(String kind, @TempDir Path dir)
            throws Exception {
        final byte[] share = new byte[32];
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context =
                    context(dir)
                            .withHostVault(owner)
                            .reading(new StandardInput(new ByteArrayInputStream(share), false));
            make(context.vault(), kind, share);
            final byte[] before = Files.readAllBytes(context.vault().path());
            final var cli = PoreloCli.commandLine(context);
            final var out = new StringWriter();
            final var err = new StringWriter();
            cli.setOut(new PrintWriter(out));
            cli.setErr(new PrintWriter(err));
            assertThat(cli.execute("vault", "unlock", "--share-stdin", "--json")).isEqualTo(65);
            assertThat(Json.parse(out.toString())).isEqualTo(Map.of("vault", "not-a-host-vault"));
            assertThat(err.toString()).contains("passphrase or other slots");
            assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mixed", "device"})
    void ownerChecksTheVaultKindBeforeTryingTheShare(String kind, @TempDir Path dir)
            throws Exception {
        final byte[] share = new byte[32];
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir).withHostVault(owner);
            make(context.vault(), kind, share);
            final byte[] wrong = new byte[32];
            java.util.Arrays.fill(wrong, (byte) 19);
            assertThatThrownBy(() -> owner.keep(wrong))
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("not a host vault", "passphrase or other slots");
        }
    }

    @org.junit.jupiter.api.Test
    void aDeviceOnlyVaultNeverAsksTheHostOwnerForAnOpener(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir).withHostVault(owner);
            make(context.vault(), "device", new byte[32]);
            // A device unlock must never consult or populate the host owner's storage.
            final var marker = new java.util.concurrent.atomic.AtomicBoolean();
            final HostVaultAccess access =
                    new HostVaultAccess() {
                        public void keep(byte[] share) {
                            marker.set(true);
                        }

                        public boolean isOpen() {
                            marker.set(true);
                            return false;
                        }

                        public byte[] share() {
                            throw new AssertionError("not a host vault");
                        }

                        public boolean forget() {
                            return false;
                        }
                    };
            context.withHostVault(access).opener();
            new Keyslots(context.withHostVault(access))
                    .unlock(java.util.Base64.getEncoder().encodeToString(new byte[32]), null);
            assertThat(marker).as("device shares use the keyring, never the host owner").isFalse();
        } finally {
            VaultShare.forget(context(dir).paths());
        }
    }

    static PoreloContext context(Path dir) {
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                args -> 0);
    }

    static void make(VaultFile vault, String kind, byte[] share) throws Exception {
        final char[] passphrase = "test-passphrase".toCharArray();
        final Map<String, VaultEntry> entries = Map.of("service", VaultEntry.of("example-value"));
        vault.write(entries, passphrase);
        if (kind.equals("passphrase")) return;
        final byte[] device = new byte[32];
        vault.enroll(VaultFile.Opener.passphrase(passphrase), device, "device", "USER_SCOPED");
        vault.revoke(VaultFile.Opener.share(device), org.fuin.porelo.vault.Keyslot.PASSPHRASE);
        if (kind.equals("device")) return;
        final var observer = VaultFile.class.getDeclaredField("unwrapped");
        observer.setAccessible(true);
        final Object previous = observer.get(null);
        final var master = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        try {
            observer.set(
                    null, (java.util.function.Consumer<byte[]>) value -> master.set(value.clone()));
            vault.read(device);
        } finally {
            observer.set(null, previous);
        }
        final var headerMethod = VaultFile.class.getDeclaredMethod("header");
        headerMethod.setAccessible(true);
        final Object header = headerMethod.invoke(vault);
        final var slotsMethod = header.getClass().getDeclaredMethod("slots");
        slotsMethod.setAccessible(true);
        final List<Object> slots = new java.util.ArrayList<>((List<?>) slotsMethod.invoke(header));
        final var slotMethod =
                VaultFile.class.getDeclaredMethod("sshAgentSlot", byte[].class, byte[].class);
        slotMethod.setAccessible(true);
        slots.add(slotMethod.invoke(vault, share, master.get()));
        final var writer =
                VaultFile.class.getDeclaredMethod(
                        "writeContent", Map.class, List.class, byte[].class);
        writer.setAccessible(true);
        try {
            writer.invoke(vault, entries, slots, master.get());
        } finally {
            java.util.Arrays.fill(master.get(), (byte) 0);
        }
        assertThat(vault.read(device)).containsKey("service");
    }
}
