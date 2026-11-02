package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link PrivateDirectory}. */
class PrivateDirectoryTest {

    @Test
    void aHomeItMakesIsWritableByItsOwnerAlone(@TempDir Path root) throws Exception {
        // Seen red only under a umask that leaves the group's write, such as 002.
        final Path home = root.resolve("state/porelo");
        PrivateDirectory.make(home, home.resolve("identity/ops"));
        for (final Path made : new Path[] {root.resolve("state"), home}) {
            assertThat(Files.getPosixFilePermissions(made))
                    .as(made.toString())
                    .doesNotContain(
                            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
        }
    }
}
