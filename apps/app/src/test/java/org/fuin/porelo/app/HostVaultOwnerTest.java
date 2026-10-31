package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards lock and clear against leaving a second, usable way into a host vault. */
class HostVaultOwnerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"check", "keep"})
    void detectingASwappedVaultPermanentlyReleasesTheShare(String action, @TempDir Path dir)
            throws Exception {
        final var invalidations = new java.util.concurrent.atomic.AtomicInteger();
        try (HostVaultOwner owner = new HostVaultOwner(invalidations::incrementAndGet)) {
            final PoreloContext context = context(dir, owner);
            final var field = HostVaultOwner.class.getDeclaredField("secret");
            field.setAccessible(true);
            final var held = (org.fuin.porelo.vault.LockedSecret) field.get(owner);
            final Path saved = dir.resolve("saved.bin");
            java.nio.file.Files.move(context.vault().path(), saved);
            context.vault().write(Map.of(), new byte[32]);
            final int before = invalidations.get();
            if (action.equals("check")) assertThat(owner.isOpen()).isFalse();
            else
                assertThatThrownBy(() -> owner.keep(new byte[31]))
                        .isInstanceOf(VaultException.class);
            assertThatThrownBy(held::copy)
                    .as("obsolete locked pages are released immediately")
                    .isInstanceOf(VaultException.class);
            assertThat(invalidations.get()).isGreaterThan(before);
            java.nio.file.Files.move(
                    saved,
                    context.vault().path(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThat(owner.isOpen()).as("moving back cannot restore an earlier unlock").isFalse();
        }
    }

    @Test
    void lockingDoesNotReadAMalformedReplacementAfterForgetting(@TempDir Path dir)
            throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            java.nio.file.Files.write(context.vault().path(), new byte[] {1, 2, 3});
            final var output = new StringWriter();
            assertThat(VaultLockCommand.lock(context, new PrintWriter(output))).isZero();
            assertThat(output.toString()).contains("locked");
            assertThat(owner.isOpen()).isFalse();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"direct,true", "cli,true", "cli,false"})
    void clearingAMalformedReplacementStillReleasesTheHeldShare(
            String entry, boolean force, @TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner(MessageWatch::dropVaultCache)) {
            final PoreloContext context = context(dir, owner);
            final var field = HostVaultOwner.class.getDeclaredField("secret");
            field.setAccessible(true);
            final var held = (org.fuin.porelo.vault.LockedSecret) field.get(owner);
            VaultCache.hold();
            VaultCache.read(context.vault(), context.requireOpener());
            java.nio.file.Files.write(context.vault().path(), new byte[] {1, 2, 3});
            if (entry.equals("direct")) {
                assertThat(new VaultClearing(context).clear(false, force).refused())
                        .as("force clears an unread replacement")
                        .isFalse();
            } else {
                final var cli = PoreloCli.commandLine(context);
                final var args =
                        new java.util.ArrayList<>(java.util.List.of("vault", "clear", "--yes"));
                if (force) args.add("--force");
                assertThat(cli.execute(args.toArray(String[]::new)))
                        .as("without force the unread file is kept")
                        .isEqualTo(force ? 0 : 1);
            }
            assertThatThrownBy(held::copy)
                    .as("a malformed replacement cannot prevent forgetting the old share")
                    .isInstanceOf(VaultException.class);
            final var cache = VaultCache.class.getDeclaredField("kept");
            cache.setAccessible(true);
            assertThat(cache.get(null))
                    .as("malformed content cannot retain decrypted entries")
                    .isNull();
        } finally {
            VaultCache.stop();
        }
    }

    @Test
    void aRefusedClearOfAReplacedVaultStillReleasesTheOldShare(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner(MessageWatch::dropVaultCache)) {
            final PoreloContext context = context(dir, owner);
            final var field = HostVaultOwner.class.getDeclaredField("secret");
            field.setAccessible(true);
            final var held = (org.fuin.porelo.vault.LockedSecret) field.get(owner);
            VaultCache.hold();
            VaultCache.read(context.vault(), context.requireOpener());
            java.nio.file.Files.delete(context.vault().path());
            context.vault().write(Map.of(), new byte[32]);
            assertThat(new VaultClearing(context).clear(false, false).refused())
                    .as("an unread replacement requires force to delete")
                    .isTrue();
            assertThat(context.vault().exists())
                    .as("the refusal preserves the replacement file")
                    .isTrue();
            assertThatThrownBy(held::copy)
                    .as("refusing deletion still releases the obsolete host share")
                    .isInstanceOf(VaultException.class);
            final var cache = VaultCache.class.getDeclaredField("kept");
            cache.setAccessible(true);
            assertThat(cache.get(null))
                    .as("refusing deletion drops the obsolete decrypted entries")
                    .isNull();
        } finally {
            VaultCache.stop();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "lock,missing",
        "lock,moved",
        "lock,swapped",
        "clear,missing",
        "clear,moved",
        "clear,swapped"
    })
    void lockingAndClearingEraseSecretsWhenTheFileChanges(
            String operation, String state, @TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner(MessageWatch::dropVaultCache)) {
            final PoreloContext context = context(dir, owner);
            final var field = HostVaultOwner.class.getDeclaredField("secret");
            field.setAccessible(true);
            final var held = (org.fuin.porelo.vault.LockedSecret) field.get(owner);
            VaultCache.hold();
            VaultCache.read(context.vault(), context.requireOpener());
            final var cache = VaultCache.class.getDeclaredField("kept");
            cache.setAccessible(true);
            final Path saved = dir.resolve("saved.bin");
            if (state.equals("missing")) java.nio.file.Files.delete(context.vault().path());
            else java.nio.file.Files.move(context.vault().path(), saved);
            if (state.equals("swapped")) context.vault().write(Map.of(), new byte[32]);
            if (operation.equals("lock")) VaultLockCommand.forget(context);
            else
                assertThat(new VaultClearing(context).clear(false, true).refused())
                        .as("clear releases secrets despite the changed file")
                        .isFalse();
            assertThatThrownBy(held::copy)
                    .as("the original locked pages are released")
                    .isInstanceOf(VaultException.class);
            assertThat(cache.get(null))
                    .as("the decrypted cache is invalidated immediately")
                    .isNull();
        } finally {
            VaultCache.stop();
        }
    }

    @Test
    void replacingTheVaultClosesThePreviousLockedShare(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final var field = HostVaultOwner.class.getDeclaredField("secret");
            field.setAccessible(true);
            final var held = (org.fuin.porelo.vault.LockedSecret) field.get(owner);
            java.nio.file.Files.delete(context.vault().path());
            final byte[] fresh = new byte[32];
            java.util.Arrays.fill(fresh, (byte) 37);
            context.vault().write(Map.of(), fresh);
            owner.keep(fresh);
            assertThatThrownBy(held::copy)
                    .as("replacement closes the previous locked allocation")
                    .isInstanceOf(VaultException.class);
            assertThat(owner.share()).as("the replacement share alone is usable").isEqualTo(fresh);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"forget", "lock", "clear"})
    void lockingImmediatelyDropsTheDecryptedCache(String operation, @TempDir Path dir)
            throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner(MessageWatch::dropVaultCache)) {
            final PoreloContext context = context(dir, owner);
            VaultCache.hold();
            VaultCache.read(context.vault(), context.requireOpener());
            final var kept = VaultCache.class.getDeclaredField("kept");
            kept.setAccessible(true);
            assertThat(kept.get(null)).as("the daemon has decrypted entries in memory").isNotNull();
            switch (operation) {
                case "forget" -> owner.forget();
                case "lock" -> VaultLockCommand.lock(context, new PrintWriter(new StringWriter()));
                case "clear" -> new VaultClearing(context).clear(false, true);
                default -> throw new AssertionError(operation);
            }
            assertThat(kept.get(null)).as("%s drops entries before returning", operation).isNull();
        } finally {
            VaultCache.stop();
        }
    }

    @Test
    void aWrongLengthDecodeErasesTheRejectedBytes() {
        final byte[] decoded = new byte[] {7, 8, 9};
        assertThat(Keyslots.decoded(decoded)).as("wrong-length shares are refused").isNull();
        assertThat(decoded).as("rejected decoded bytes are erased").containsOnly((byte) 0);
    }

    @Test
    void lockStillErasesBothLocalKeyringEntriesWhenTheOwnerDoesNotAnswer(@TempDir Path dir) {
        final java.util.List<String> erased = new java.util.ArrayList<>();
        final HostVaultAccess absent =
                new HostVaultAccess() {
                    public void keep(byte[] share) {
                        throw new UnsupportedOperationException();
                    }

                    public boolean isOpen() {
                        return false;
                    }

                    public byte[] share() {
                        throw new UnsupportedOperationException();
                    }

                    public boolean forget() {
                        assertThat(erased)
                                .as("local erasure precedes any wait for the daemon")
                                .containsExactly("passphrase", "device");
                        throw new VaultException("owner unavailable");
                    }
                };
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                args -> 0)
                        .withHostVault(absent);
        assertThatThrownBy(
                        () ->
                                VaultLockCommand.forget(
                                        context,
                                        () -> {
                                            erased.add("passphrase");
                                            return org.fuin.porelo.vault.KernelKeyring.Forgotten
                                                    .CLEARED;
                                        },
                                        () -> {
                                            erased.add("device");
                                            return org.fuin.porelo.vault.KernelKeyring.Forgotten
                                                    .CLEARED;
                                        }))
                .as("an unanswered daemon still makes lock fail after local erasure")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("passphrase: CLEARED", "device share: CLEARED");
        assertThat(erased)
                .as("both local opening credentials are erased")
                .containsExactly("passphrase", "device");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"forget", "lock", "clear"})
    @org.junit.jupiter.api.Timeout(15)
    void aLockDuringVerificationCannotBeUndoneByTheUnlock(String operation, @TempDir Path dir)
            throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final var reading = new java.util.concurrent.CountDownLatch(1);
            final var proceed = new java.util.concurrent.CountDownLatch(1);
            owner.bind(
                    new org.fuin.porelo.vault.VaultFile(context.vault().path()) {
                        @Override
                        public Map<String, VaultEntry> read(byte[] share) {
                            reading.countDown();
                            try {
                                if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new AssertionError("verification was not released");
                                }
                            } catch (InterruptedException ex) {
                                throw new AssertionError(ex);
                            }
                            return super.read(share);
                        }
                    });
            final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final byte[] share = new byte[32];
            java.util.Arrays.fill(share, (byte) 81);
            final Thread unlocking =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            owner.keep(share);
                                        } catch (Throwable ex) {
                                            failure.set(ex);
                                        }
                                    });
            try {
                assertThat(reading.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("unlock is verifying outside the monitor")
                        .isTrue();
                switch (operation) {
                    case "forget" -> owner.forget();
                    case "lock" ->
                            VaultLockCommand.lock(context, new PrintWriter(new StringWriter()));
                    case "clear" -> new VaultClearing(context).clear(false, true);
                    default -> throw new AssertionError(operation);
                }
            } finally {
                proceed.countDown();
                unlocking.join();
            }
            assertThat(failure.get())
                    .as("the interrupted unlock is refused")
                    .isInstanceOf(VaultException.class);
            assertThat(owner.isOpen()).as("%s wins over the in-flight unlock", operation).isFalse();
        }
    }

    @Test
    void lockReleasesTheOwnerAndInvalidatesAnEarlierOpener(@TempDir Path dir) {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final var earlier = context.requireOpener();
            assertThat(context.vault().read(earlier))
                    .as("the owner opens the vault")
                    .containsKey("service");
            assertThat(VaultLockCommand.lock(context, new PrintWriter(new StringWriter())))
                    .as("lock reaches the owner without a kernel keyring")
                    .isZero();
            assertThat(owner.isOpen()).as("the share has been released").isFalse();
            assertThat(context.opener()).as("no later opener exists").isEmpty();
            assertThatThrownBy(() -> context.vault().read(earlier))
                    .as("an earlier opener retains no share of its own")
                    .isInstanceOf(VaultException.class);
        }
    }

    @Test
    void clearReleasesTheOwnerEvenWithoutAKeyring(@TempDir Path dir) {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final var result = new VaultClearing(context).clear(false, true);
            assertThat(result.refused()).as("an idle host vault can be cleared").isFalse();
            assertThat(context.vault().exists()).as("the encrypted file is gone").isFalse();
            assertThat(owner.isOpen()).as("clearing also erases the held share").isFalse();
        }
    }

    @Test
    void stoppingTheOwnerRefusesFurtherUnlocks(@TempDir Path dir) {
        final HostVaultOwner owner = new HostVaultOwner();
        final PoreloContext context = context(dir, owner);
        owner.close();
        assertThat(context.opener()).as("a daemon restart loses the share").isEmpty();
        assertThatThrownBy(() -> owner.keep(new byte[32]))
                .as("a stopped owner cannot be revived")
                .isInstanceOf(VaultException.class);
    }

    @Test
    void replacingTheVaultNeverReleasesTheEarlierShare(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final var replacement =
                    new org.fuin.porelo.vault.VaultFile(dir.resolve("replacement.bin"));
            replacement.write(Map.of(), new byte[32]);
            java.nio.file.Files.move(
                    replacement.path(),
                    context.vault().path(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThat(owner.isOpen()).as("a replacement vault is locked").isFalse();
            assertThatThrownBy(owner::share)
                    .as("the earlier share cannot leave the owner")
                    .isInstanceOf(VaultException.class);
        }
    }

    @Test
    void clearRefusesBeforeDeletingWhenTheOwnerCannotForget(@TempDir Path dir) {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final PoreloContext context = context(dir, owner);
            final HostVaultAccess unavailable =
                    new HostVaultAccess() {
                        public void keep(byte[] share) {
                            owner.keep(share);
                        }

                        public boolean isOpen() {
                            return owner.isOpen();
                        }

                        public byte[] share() {
                            return owner.share();
                        }

                        public boolean forget() {
                            assertThat(context.vault().exists())
                                    .as("the file must still exist when the owner is asked")
                                    .isTrue();
                            throw new VaultException("owner unavailable");
                        }
                    };
            final var result =
                    new VaultClearing(context.withHostVault(unavailable)).clear(false, true);
            assertThat(result.refused()).as("an unanswered forget refuses clearing").isTrue();
            assertThat(context.vault().exists()).as("the encrypted vault stays").isTrue();
            assertThat(owner.isOpen()).as("the test simulates the owner still holding it").isTrue();
        }
    }

    private PoreloContext context(Path dir, HostVaultOwner owner) {
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                args -> 0)
                        .withHostVault(owner);
        final byte[] share = new byte[32];
        java.util.Arrays.fill(share, (byte) 81);
        context.vault().write(Map.of("service", VaultEntry.of("example-value")), share);
        owner.keep(share);
        java.util.Arrays.fill(share, (byte) 0);
        return context;
    }
}
