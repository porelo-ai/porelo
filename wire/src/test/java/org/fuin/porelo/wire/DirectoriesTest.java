package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoriesTest {

    @Test
    void everyDirectoryMadeIsWritableByItsOwnerAlone(@TempDir Path dir) throws IOException {
        // Asked for group and others' write outright, as an umask of 000 would give them.
        final Path made =
                Directories.create(
                        dir.resolve(".local/share/porelo"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxrwxrwx")));

        assertThat(made).isDirectory();
        for (final Path each :
                new Path[] {dir.resolve(".local"), dir.resolve(".local/share"), made}) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(each)))
                    .as(each.toString())
                    .matches("rwx.-x.-x");
        }
    }

    @Test
    void aDirectoryMadeWithoutAModeIsWritableByItsOwnerAlone(@TempDir Path dir) throws IOException {
        // The account's umask decides what Files.createDirectories gives; 002 left group write.
        final Path made = Directories.create(dir.resolve("config/porelo"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(made)))
                .matches("rwx.-..-.");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(made.getParent())))
                .matches("rwx.-..-.");
    }

    @Test
    void aDirectoryThatWasThereKeepsItsMode(@TempDir Path dir) throws IOException {
        final Path shared = Files.createDirectory(dir.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxr-x"));

        Directories.create(shared.resolve("below"));

        // Somebody else's choice for a directory this did not make is theirs.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)))
                .isEqualTo("rwxrwxr-x");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(shared.resolve("below"))))
                .matches("rwx.-..-.");
    }
}
