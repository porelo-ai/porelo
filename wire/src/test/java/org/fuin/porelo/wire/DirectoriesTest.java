package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link Directories}, each under a umask the test sets: under {@code 022} the system
 * keeps the group's write out by itself, so only {@code 002} shows what {@code Directories} does.
 */
class DirectoriesTest {

    @ParameterizedTest(name = "umask {0}")
    @ValueSource(ints = {0002, 0022})
    void everyDirectoryMadeIsWritableByItsOwnerAlone(final int umask, @TempDir Path dir)
            throws Exception {
        Umask.under(
                umask,
                () -> {
                    // Asked for group and others' write outright, as an umask of 000 would give.
                    final Path made =
                            Directories.create(
                                    dir.resolve(".local/share/porelo"),
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rwxrwxrwx")));

                    assertThat(made).isDirectory();
                    for (final Path each :
                            new Path[] {dir.resolve(".local"), dir.resolve(".local/share"), made}) {
                        assertThat(
                                        PosixFilePermissions.toString(
                                                Files.getPosixFilePermissions(each)))
                                .as(each.toString())
                                .matches("rwx.-x.-x");
                    }
                });
    }

    @ParameterizedTest(name = "umask {0}")
    @ValueSource(ints = {0002, 0022})
    void aDirectoryMadeWithoutAModeIsWritableByItsOwnerAlone(final int umask, @TempDir Path dir)
            throws Exception {
        Umask.under(
                umask,
                () -> {
                    // The umask decides what Files.createDirectories gives; 002 left group write.
                    final Path made = Directories.create(dir.resolve("config/porelo"));

                    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(made)))
                            .matches("rwx.-..-.");
                    assertThat(
                                    PosixFilePermissions.toString(
                                            Files.getPosixFilePermissions(made.getParent())))
                            .matches("rwx.-..-.");
                });
    }

    @Test
    void underUmask002TheSystemAloneLeavesTheGroupsWrite(@TempDir Path dir) throws Exception {
        // What the two tests above stand on: without Directories, 002 lets the group write, so a
        // test passing under it is a test of Directories and not of the umask.
        Umask.under(
                0002,
                () ->
                        assertThat(
                                        PosixFilePermissions.toString(
                                                Files.getPosixFilePermissions(
                                                        Files.createDirectories(
                                                                dir.resolve("plain")))))
                                .isEqualTo("rwxrwxr-x"));
    }

    @Test
    void aDirectoryThatWasThereKeepsItsMode(@TempDir Path dir) throws Exception {
        Umask.under(
                0002,
                () -> {
                    final Path shared = Files.createDirectory(dir.resolve("shared"));
                    Files.setPosixFilePermissions(
                            shared, PosixFilePermissions.fromString("rwxrwxr-x"));

                    Directories.create(shared.resolve("below"));

                    // Somebody else's choice for a directory this did not make is theirs.
                    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)))
                            .isEqualTo("rwxrwxr-x");
                    assertThat(
                                    PosixFilePermissions.toString(
                                            Files.getPosixFilePermissions(shared.resolve("below"))))
                            .matches("rwx.-..-.");
                });
    }
}
