package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Keyslots}, which is what an interface sees of the keyslots.
 */
class KeyslotsTest {

    private static final char[] PASSPHRASE = "correct horse".toCharArray();

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new PoreloContext(new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private String share(final int seed) {
        final byte[] share = new byte[Keyslots.SHARE_BYTES];
        new java.util.Random(seed).nextBytes(share);
        return Base64.getEncoder().encodeToString(share);
    }

    private PoreloContext withVault(final Path dir) {
        final PoreloContext context = context(dir);
        context.vault().write(Map.of("github.token", VaultEntry.of("ghp_example")), PASSPHRASE);
        return context;
    }

    @Test
    void a_machine_without_a_vault_says_so_rather_than_failing(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(context(dir));

        assertThat(keyslots.list()).isEmpty();
        assertThat(keyslots.enroll("the laptop", share(1), "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.VAULT_WITHOUT_KEYSLOTS);
        assertThat(keyslots.unlock(share(1), null).outcome())
                .isEqualTo(Keyslots.Unlocked.VAULT_WITHOUT_KEYSLOTS);
        assertThat(keyslots.revoke("whatever").outcome())
                .isEqualTo(Keyslots.Revoked.VAULT_WITHOUT_KEYSLOTS);
    }

    @Test
    void a_new_vault_lists_the_passphrase_and_nothing_else(@TempDir final Path dir) {
        assertThat(new Keyslots(withVault(dir)).list()).singleElement()
                .satisfies(slot -> assertThat(slot.recovery()).isTrue());
    }

    /**
     * A daemon has no terminal, so a locked vault is answered rather than waited on. Enrolling
     * needs the master key, and the master key needs something that already opens the vault.
     */
    @Test
    void enrolling_into_a_locked_vault_is_refused_with_a_reason(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        final Keyslots.Enrollment enrolled =
                keyslots.enroll("the laptop", share(1), "USER_SCOPED");

        assertThat(enrolled.outcome()).isEqualTo(Keyslots.Enrolled.VAULT_LOCKED);
        assertThat(enrolled.detail()).contains("nothing here can open the vault");
    }

    @Test
    void a_share_that_is_not_thirty_two_bytes_is_refused(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        assertThat(keyslots.enroll("the laptop", "not base64 at all", "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.BAD_SHARE);
        assertThat(keyslots.enroll("the laptop",
                Base64.getEncoder().encodeToString(new byte[8]), "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.BAD_SHARE);
        assertThat(keyslots.unlock("also not base64", null).outcome())
                .isEqualTo(Keyslots.Unlocked.BAD_SHARE);
    }

    @Test
    void a_share_no_slot_takes_is_refused_and_holds_nothing(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        final Keyslots.Unlock unlocked = keyslots.unlock(share(9), null);

        assertThat(unlocked.outcome()).isEqualTo(Keyslots.Unlocked.SHARE_REJECTED);
        assertThat(unlocked.until()).as("nothing is open, so nothing runs out").isEmpty();
    }

    /**
     * The whole of it, through the paths a task actually uses: a device unlocks, and the
     * credentials read with nothing typed.
     * <p>
     * Needs the kernel keyring, which is where an unlock is held. A machine without one has no
     * unlock to hold and nothing to assert, so the test says why it did not run rather than
     * passing quietly.
     */
    @Test
    void a_device_unlocks_and_the_credentials_read_with_nothing_typed(@TempDir final Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here, so an unlock cannot be held");

        final PoreloContext context = withVault(dir);
        final Keyslots keyslots = new Keyslots(context);
        final String share = share(1);
        // Enrolling needs the vault open, and here nothing has opened it - so open it the way a
        // person at the machine would, by handing the passphrase to the vault directly.
        final org.fuin.porelo.vault.Keyslot slot = context.vault().enroll(
                org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE),
                Base64.getDecoder().decode(share), "the laptop", "USER_SCOPED");
        try {
            assertThat(context.readableCredentials()).as("shut before the device speaks").isEmpty();

            final Keyslots.Unlock unlocked = keyslots.unlock(share, 5);

            assertThat(unlocked.outcome()).isEqualTo(Keyslots.Unlocked.UNLOCKED);
            assertThat(unlocked.slot()).isNotNull();
            assertThat(unlocked.slot().id()).isEqualTo(slot.id());
            assertThat(unlocked.until()).isNotEmpty();
            assertThat(unlocked.slot().lastUsed()).as("a device that unlocks says when")
                    .isNotEmpty();
            assertThat(context.readableCredentials()).as("open, with nothing typed")
                    .hasValueSatisfying(entries -> assertThat(entries).containsKey("github.token"));
        } finally {
            VaultShare.forget(context.paths());
        }
    }

    @Test
    void a_share_that_unlocked_is_not_answered_as_rejected_when_its_last_use_cannot_be_written(
            @TempDir final Path dir) throws java.io.IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here, so an unlock cannot be held");
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root writes into a read-only directory, so the write cannot be made to fail");

        // The share is held, and the vault open with it, before "last used" is written: a failure there answered
        // SHARE_REJECTED while the vault stood open, and a person was told the device was refused.
        final PoreloContext context = withVault(dir);
        final String share = share(1);
        context.vault().enroll(org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE),
                Base64.getDecoder().decode(share), "the laptop", "USER_SCOPED");
        final Path directory = context.vault().path().getParent();
        java.nio.file.Files.setPosixFilePermissions(directory,
                java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        try {
            final Keyslots.Unlock unlocked = new Keyslots(context).unlock(share, 5);

            assertThat(context.readableCredentials()).as("the vault is open with the share").isPresent();
            assertThat(unlocked.outcome()).as("what the person is told is what happened")
                    .isEqualTo(Keyslots.Unlocked.UNLOCKED);
            assertThat(unlocked.until()).isNotEmpty();
        } finally {
            java.nio.file.Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            VaultShare.forget(context.paths());
        }
    }

    @Test
    void forgetting_the_share_shuts_the_vault_again(@TempDir final Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here, so an unlock cannot be held");

        final PoreloContext context = withVault(dir);
        final String share = share(1);
        context.vault().enroll(org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE),
                Base64.getDecoder().decode(share), "the laptop", "USER_SCOPED");
        new Keyslots(context).unlock(share, 5);

        VaultShare.forget(context.paths());

        assertThat(context.readableCredentials()).isEmpty();
    }
}
