package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards release ordering so a share cannot be paged out before it is erased. */
class LockedSecretTest {
    @Test
    void theKernelReportsLockedPagesExcludedFromDumps() throws Exception {
        final long locked = lockedKib();
        final long excluded = excludedKib();
        try (LockedSecret secret = new LockedSecret(new byte[32])) {
            org.assertj.core.api.SoftAssertions.assertSoftly(
                    softly -> {
                        try {
                            softly.assertThat(lockedKib())
                                    .as("mlock is visible to the kernel")
                                    .isGreaterThan(locked);
                            softly.assertThat(excludedKib())
                                    .as("MADV_DONTDUMP is visible to the kernel")
                                    .isGreaterThan(excluded);
                        } catch (java.io.IOException ex) {
                            throw new AssertionError(ex);
                        }
                    });
        }
        assertThat(lockedKib()).as("release returns the locked pages").isEqualTo(locked);
        assertThat(excludedKib())
                .as("release returns the pages excluded from dumps")
                .isEqualTo(excluded);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"page", "allocate", "lock", "nodump"})
    void failuresEraseAndReleaseEveryAllocation(String failure) {
        final var arena = java.lang.foreign.Arena.ofShared();
        final java.util.concurrent.atomic.AtomicBoolean unlocked =
                new java.util.concurrent.atomic.AtomicBoolean();
        final LockedSecret.Protection protection =
                new LockedSecret.Protection() {
                    public long pageSize() {
                        if (failure.equals("page")) {
                            throw new VaultException("page size failed");
                        }
                        return failure.equals("allocate") ? 3 : 4096;
                    }

                    public void lock(MemorySegment memory) {
                        memory.fill((byte) 73);
                        if (failure.equals("lock")) {
                            throw new VaultException("lock failed");
                        }
                    }

                    public void excludeFromDumps(MemorySegment memory) {
                        throw new VaultException("nodump failed");
                    }

                    public void unlock(MemorySegment memory) {
                        assertThat(memory.toArray(ValueLayout.JAVA_BYTE))
                                .as("failure erases the allocation before unlocking")
                                .containsOnly((byte) 0);
                        unlocked.set(true);
                    }
                };
        try {
            assertThatThrownBy(() -> new LockedSecret(new byte[32], protection, arena))
                    .as("an unprotected allocation is refused")
                    .isInstanceOf(RuntimeException.class);
            assertThat(arena.scope().isAlive())
                    .as("every failed allocation closes its arena")
                    .isFalse();
            assertThat(unlocked.get())
                    .as("only a successful mlock needs munlock")
                    .isEqualTo(failure.equals("nodump"));
        } finally {
            if (arena.scope().isAlive()) {
                arena.close();
            }
        }
    }

    private static long lockedKib() throws java.io.IOException {
        return java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("VmLck:"))
                .mapToLong(line -> Long.parseLong(line.trim().split("\\s+")[1]))
                .findFirst()
                .orElseThrow();
    }

    private static long excludedKib() throws java.io.IOException {
        long size = 0;
        long total = 0;
        for (String line :
                java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/smaps"))) {
            if (line.startsWith("Size:")) {
                size = Long.parseLong(line.trim().split("\\s+")[1]);
            } else if (line.startsWith("VmFlags:") && List.of(line.split("\\s+")).contains("dd")) {
                total += size;
            }
        }
        return total;
    }

    @Test
    void erasesBeforeUnlockingAndRefusesCopiesAfterRelease() {
        final List<String> calls = new ArrayList<>();
        final LockedSecret.Protection protection =
                new LockedSecret.Protection() {
                    @Override
                    public void lock(MemorySegment memory) {
                        calls.add("lock");
                    }

                    @Override
                    public void excludeFromDumps(MemorySegment memory) {
                        calls.add("nodump");
                    }

                    @Override
                    public void unlock(MemorySegment memory) {
                        assertThat(memory.toArray(ValueLayout.JAVA_BYTE))
                                .as("the native allocation is erased before munlock")
                                .containsOnly((byte) 0);
                        calls.add("unlock");
                    }
                };
        final byte[] original = new byte[32];
        java.util.Arrays.fill(original, (byte) 71);
        final LockedSecret secret = new LockedSecret(original, protection);
        assertThat(secret.copy()).as("the locked allocation holds the share").isEqualTo(original);
        secret.close();
        secret.close();
        assertThat(calls)
                .as("protected before use, released only once")
                .containsExactly("lock", "nodump", "unlock");
        assertThatThrownBy(secret::copy)
                .as("released shares cannot be read")
                .isInstanceOf(VaultException.class);
    }
}
