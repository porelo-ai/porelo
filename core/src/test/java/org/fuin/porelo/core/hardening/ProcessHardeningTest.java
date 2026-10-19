package org.fuin.porelo.core.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcessHardening}.
 *
 * <p>Every assertion reads the value back from the kernel instead of trusting a return code, which
 * is the rule spike C5 left behind.
 *
 * <p>Only the reversible measures are exercised. {@code PR_SET_NO_NEW_PRIVS} cannot be cleared
 * again for the lifetime of the process, so applying it here would leak into every later test in
 * the same surefire JVM.
 */
class ProcessHardeningTest {

    @AfterEach
    void restoreDefaults() {
        ProcessHardening.setDumpable(1);
        ProcessHardening.dieWithParent(0);
    }

    @Test
    void disableDumpingIsVisibleWhenReadBack() {

        assertThat(ProcessHardening.dumpable()).isEqualTo(1);

        ProcessHardening.disableDumping();

        assertThat(ProcessHardening.dumpable()).isZero();
    }

    @Test
    void dieWithParentIsVisibleWhenReadBack() {

        ProcessHardening.dieWithParent(15);

        assertThat(ProcessHardening.parentDeathSignal()).isEqualTo(15);
    }

    @Test
    void dieWithParentRejectsAnUnknownSignal() {

        assertThatThrownBy(() -> ProcessHardening.dieWithParent(9999))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("PR_SET_PDEATHSIG");
    }

    @Test
    void noNewPrivilegesIsReadableWithoutBeingSet() throws java.io.IOException {

        // Reading must not change anything - the flag is irreversible once set. Not "it is off":
        // a Porelo task sets it on purpose, so a build inside one starts with it on.
        final boolean before = kernelSaysNoNewPrivileges();
        final boolean read = ProcessHardening.noNewPrivileges();

        assertThat(read).as("what the call reads is what the kernel says").isEqualTo(before);
        assertThat(kernelSaysNoNewPrivileges())
                .as("and reading it changed nothing")
                .isEqualTo(before);
    }

    private static boolean kernelSaysNoNewPrivileges() throws java.io.IOException {
        return java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("NoNewPrivs:"))
                .anyMatch(line -> line.endsWith("1"));
    }

    @Test
    void aJvmMainThreadIsNotTheThreadGroupLeader() {

        // This is the C5 finding, pinned as a test: on a JVM, prctl from this thread does not cover
        // the process. If this ever passes on a JVM the native-image requirement can be revisited;
        // until then it is why the shipped binaries are native images.
        assertThat(ProcessHardening.appliesToWholeProcess()).isFalse();
    }
}
