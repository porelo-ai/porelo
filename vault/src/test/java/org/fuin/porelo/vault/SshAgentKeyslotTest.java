package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards share rotation against credential loss and accidental passphrase recovery. */
class SshAgentKeyslotTest {

    private byte[] share() {
        final byte[] share = new byte[32];
        new java.security.SecureRandom().nextBytes(share);
        return share;
    }

    @Test
    void aDeviceSlotCannotReplaceTheLastHostShare(@TempDir Path dir) throws Exception {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] old = share();
        final byte[] device = share();
        final Map<String, VaultEntry> entries = Map.of("service", VaultEntry.of("example-value"));
        vault.write(entries, old);
        final var captured = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        final var previous = VaultFile.unwrapped;
        try {
            VaultFile.unwrapped = master -> captured.set(master.clone());
            vault.read(old);
        } finally {
            VaultFile.unwrapped = previous;
        }
        final byte[] master = captured.get();
        final var generator =
                new org.bouncycastle.crypto.generators.HKDFBytesGenerator(
                        new org.bouncycastle.crypto.digests.SHA256Digest());
        generator.init(
                new org.bouncycastle.crypto.params.HKDFParameters(
                        device,
                        new byte[16],
                        "porelo-keyslot".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        final byte[] key = new byte[32];
        generator.generateBytes(key, 0, key.length);
        final var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
        final var deviceSlot =
                new VaultHeader.Slot(
                        VaultHeader.SHARE_SLOT,
                        new Keyslot("device", "device", "USER_SCOPED", "", "", false),
                        new byte[16],
                        new byte[12],
                        cipher.doFinal(master),
                        0,
                        0,
                        0);
        final var slots =
                new java.util.ArrayList<>(
                        VaultHeader.read(Files.readAllBytes(vault.path()), vault.path()).slots());
        slots.add(deviceSlot);
        final var writer =
                VaultFile.class.getDeclaredMethod(
                        "writeContent", Map.class, java.util.List.class, byte[].class);
        writer.setAccessible(true);
        try {
            writer.invoke(vault, entries, slots, master);
        } finally {
            java.util.Arrays.fill(master, (byte) 0);
            java.util.Arrays.fill(key, (byte) 0);
        }
        assertThat(vault.read(device))
                .as("the legacy fixture really has an independent device way in")
                .containsKey("service");
        assertThat(vault.isHostVault()).as("mixed slots cannot qualify as a host vault").isFalse();
        final byte[] before = Files.readAllBytes(vault.path());
        assertThatThrownBy(() -> vault.revoke(VaultFile.Opener.share(device), Keyslot.SSH_AGENT))
                .as("a device must not replace the person's last SSH slot")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("last way into");
        assertThat(Files.readAllBytes(vault.path()))
                .as("refusing revocation preserves every entry and slot")
                .isEqualTo(before);
        assertThatThrownBy(() -> vault.read(VaultFile.Opener.share(old)))
                .as("a mixed vault's SSH slot cannot open through a generic share")
                .isInstanceOf(NotAHostVaultException.class);
    }

    @Test
    void changingKeysEnrollsTheNextShareBeforeRevokingThePreviousOne(@TempDir Path dir) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] old = share();
        final byte[] fresh = share();
        vault.write(Map.of("service", VaultEntry.of("example-value")), old);
        final Keyslot enrolled = vault.enrollHostShare(old, fresh);
        assertThat(vault.read(old))
                .as("the previous key works until it is revoked")
                .containsKey("service");
        assertThat(vault.read(fresh))
                .as("the next key is verified before revoking the previous one")
                .containsKey("service");
        vault.revoke(VaultFile.Opener.share(fresh), Keyslot.SSH_AGENT);
        assertThatThrownBy(() -> vault.read(old))
                .as("the previous key loses access")
                .isInstanceOf(OtherVaultKeyException.class);
        assertThat(vault.read(fresh).get("service").value())
                .as("changing keys keeps sign-ins")
                .isEqualTo("example-value");
        assertThatThrownBy(() -> vault.revoke(VaultFile.Opener.share(fresh), enrolled.id()))
                .as("the last SSH share cannot be revoked")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("last way into");
    }

    @Test
    void aHostVaultRefusesDeviceEnrollment(@TempDir Path dir) throws Exception {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] old = share();
        vault.write(Map.of("service", VaultEntry.of("example-value")), old);
        final byte[] before = Files.readAllBytes(vault.path());
        assertThatThrownBy(
                        () ->
                                vault.enroll(
                                        VaultFile.Opener.share(old),
                                        share(),
                                        "device",
                                        "USER_SCOPED"))
                .as("a device slot would bypass the person's signing key")
                .isInstanceOf(VaultException.class);
        assertThat(Files.readAllBytes(vault.path()))
                .as("refused enrollment preserves the vault")
                .isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"update", "enroll", "revoke", "used", "rekey"})
    void writingThroughAHeldShareAsksItsOwnerOnlyOnce(String operation, @TempDir Path dir) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] share = share();
        final char[] passphrase = "test-passphrase".toCharArray();
        vault.write(Map.of("service", VaultEntry.of("example-value")), passphrase);
        final Keyslot device =
                vault.enroll(
                        VaultFile.Opener.passphrase(passphrase), share, "device", "USER_SCOPED");
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        final VaultFile.Opener opener =
                VaultFile.Opener.keptShare(
                        () -> {
                            calls.incrementAndGet();
                            return share.clone();
                        });
        switch (operation) {
            case "update" ->
                    vault.update(
                            opener,
                            entries -> {
                                entries.put("another", VaultEntry.of("second-value"));
                                return entries;
                            });
            case "enroll" -> vault.enroll(opener, share(), "another", "USER_SCOPED");
            case "revoke" -> vault.revoke(opener, device.id());
            case "used" -> vault.used(opener, device.id());
            case "rekey" -> vault.rekey(opener, passphrase);
            default -> throw new AssertionError(operation);
        }
        assertThat(calls.get())
                .as("%s must not transport the same share twice", operation)
                .isEqualTo(1);
        assertThat(vault.read(passphrase).get("service").value())
                .as("a single opening preserves stored entries")
                .isEqualTo("example-value");
        if (operation.equals("update"))
            assertThat(vault.read(passphrase)).as("the update is written").containsKey("another");
    }

    @Test
    void rotatingAShareKeepsEveryEntryAndRemovesTheOldWayIn(@TempDir final Path dir)
            throws Exception {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] old = share();
        final byte[] fresh = share();
        final Map<String, VaultEntry> entries = Map.of("service", VaultEntry.of("example-value"));
        vault.write(entries, old);

        vault.rekey(old, fresh);

        assertThat(vault.read(fresh)).as("rotation preserves the entries").containsKey("service");
        assertThat(vault.read(fresh).get("service").value())
                .as("rotation preserves the value")
                .isEqualTo(entries.get("service").value());
        assertThatThrownBy(() -> vault.read(old))
                .as("the old share has no way in")
                .isInstanceOf(OtherVaultKeyException.class);
        assertThat(VaultHeader.read(Files.readAllBytes(vault.path()), vault.path()).slots())
                .as("the share is the only kind of slot")
                .singleElement()
                .extracting(VaultHeader.Slot::kind)
                .isEqualTo("ssh-agent");
    }

    @Test
    void passphraseWritesAndRekeysCannotAddARecoverySlot(@TempDir final Path dir) throws Exception {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final byte[] share = share();
        vault.write(Map.of("service", VaultEntry.of("example-value")), share);
        final byte[] before = Files.readAllBytes(vault.path());

        assertThatThrownBy(() -> vault.rekey(new char[0], "new-passphrase".toCharArray()))
                .as("rekey needs an existing passphrase slot")
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.write(Map.of(), new char[0]))
                .as("write cannot replace a share-only vault")
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.rekey(VaultFile.Opener.share(share), new char[0]))
                .as("opening with a share cannot add passphrase recovery")
                .isInstanceOf(VaultException.class);
        assertThat(Files.readAllBytes(vault.path()))
                .as("refusals leave the vault intact")
                .isEqualTo(before);
        assertThat(vault.read(share)).as("the original share still works").containsKey("service");
    }
}
