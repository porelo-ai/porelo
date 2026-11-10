package org.fuin.porelo.core.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostReadDescriptorTest {
    @TempDir Path dir;

    @Test
    void aarch64FlagsStillRefuseLinksAndRequireDirectories() throws Exception {
        // Incorrect ABI flags would silently allow a substituted configuration link.
        final int flags = LinuxReadDescriptor.flags("aarch64", true);
        assertThat(flags & 0100000).isNotZero();
        assertThat(flags & 040000).isNotZero();
        assertThat(flags & 0200000).isZero();
        assertThatThrownBy(() -> LinuxReadDescriptor.flags("unknown", true))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void aPinnedDirectoryCannotOpenALinkedChild() throws Exception {
        // A relative path swap must not replace the private file after its parent was pinned.
        Files.writeString(dir.resolve("original"), "private");
        Files.createSymbolicLink(dir.resolve("linked"), Path.of("original"));
        try (var parent = LinuxReadDescriptor.open(dir, true)) {
            assertThatThrownBy(() -> parent.child("linked", false))
                    .isInstanceOf(java.io.IOException.class);
            try (var file = parent.child("original", false)) {
                assertThat(Files.readString(file.path())).isEqualTo("private");
            }
        }
    }
}
