package org.fuin.porelo.core.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** In a process of its own: a umask holds for the whole process, and tests here run beside it. */
class OwnerOnlyFilesTest {

    @TempDir Path dir;

    /** Writes a file and a directory itself, and has a child do so. */
    static final class Writer {
        private Writer() {}

        public static void main(final String[] args) throws Exception {
            OwnerOnlyFiles.fromNowOn();
            final Path at = Path.of(args[0]);
            Files.writeString(at.resolve("file"), "x");
            Files.createDirectory(at.resolve("directory"));
            final Process child =
                    new ProcessBuilder("touch", at.resolve("child").toString()).start();
            if (child.waitFor() != 0) {
                throw new IllegalStateException("touch failed");
            }
        }
    }

    /** In this process as well, and put back at once: the build's FFM check sees only this one. */
    @Test
    void theCallAnswersWhatItWas() {
        final int was = OwnerOnlyFiles.umask(0077);
        try {
            assertThat(OwnerOnlyFiles.umask(was)).isEqualTo(0077);
        } finally {
            OwnerOnlyFiles.umask(was);
        }
    }

    @Test
    void whatThisProcessAndItsChildrenCreateIsOwnerOnlyUnderAnyUmask() throws Exception {
        for (final String umask : List.of("000", "002", "022")) {
            final Path at = Files.createDirectory(dir.resolve(umask));
            final String java = ProcessHandle.current().info().command().orElseThrow();
            final Process process =
                    new ProcessBuilder(
                                    "sh",
                                    "-c",
                                    "umask "
                                            + umask
                                            + " && exec \"$0\" --enable-native-access=ALL-UNNAMED"
                                            + " -cp \"$1\" \"$2\" \"$3\"",
                                    java,
                                    System.getProperty("java.class.path"),
                                    Writer.class.getName(),
                                    at.toString())
                            .redirectErrorStream(true)
                            .start();
            final String said =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(said).isZero();

            assertThat(mode(at.resolve("file"))).isEqualTo("rw-------");
            assertThat(mode(at.resolve("directory"))).isEqualTo("rwx------");
            assertThat(mode(at.resolve("child"))).isEqualTo("rw-------");
        }
    }

    private static String mode(final Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
