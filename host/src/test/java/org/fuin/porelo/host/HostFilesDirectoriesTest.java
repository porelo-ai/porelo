package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prevents simultaneous pushes from failing or trusting an unchecked directory winner. */
class HostFilesDirectoriesTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"/run/porelo-host/push", "/var/lib/porelo-host/arrivals/alpha"})
    void aDirectoryCreatedByAnotherPushIsCheckedAndUsed(String name) throws Exception {
        final Path directory = root.resolve(name.substring(1));
        final var files = racing(directory, false);
        assertThatCode(() -> files.directories(directory))
                .as("another push winning directory creation must not fail this push")
                .doesNotThrowAnyException();
        assertThat(directory).as("both pushes share the private directory").isDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnsafeWinnerIsRefusedWithoutRepairingItsPermissions(boolean symbolic) throws Exception {
        final Path directory = root.resolve("push");
        final var files = racing(directory, symbolic);
        if (!symbolic) {
            // This winner has permissions the host would refuse, rather than the normal 0700.
            unsafe = true;
        }
        assertThatThrownBy(() -> files.directories(directory))
                .as("a directory race cannot bypass the ownership, type and permission checks")
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("of root's alone");
        if (!symbolic)
            assertThat((int) Files.getAttribute(directory, "unix:mode") & 0777)
                    .as("a raced directory must be checked before changing its mode")
                    .isEqualTo(0777);
    }

    private boolean unsafe;

    private HostFiles racing(Path directory, boolean symbolic) throws Exception {
        Files.createDirectories(directory.getParent());
        for (Path parent = directory.getParent();
                parent != null && parent.startsWith(root);
                parent = parent.getParent())
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        final AtomicBoolean won = new AtomicBoolean();
        return new HostFiles(
                root,
                path -> {
                    try {
                        final var attributes =
                                new HashMap<>(
                                        Files.readAttributes(
                                                path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
                        attributes.put("uid", 0);
                        if (!path.startsWith(root)) attributes.put("mode", 040755);
                        return attributes;
                    } catch (NoSuchFileException missing) {
                        if (path.equals(directory) && won.compareAndSet(false, true)) {
                            if (symbolic)
                                Files.createSymbolicLink(directory, directory.getParent());
                            else
                                Files.createDirectory(
                                        directory,
                                        PosixFilePermissions.asFileAttribute(
                                                PosixFilePermissions.fromString("rwx------")));
                            if (unsafe) Files.setAttribute(directory, "unix:mode", 0777);
                        }
                        throw missing;
                    }
                });
    }
}
