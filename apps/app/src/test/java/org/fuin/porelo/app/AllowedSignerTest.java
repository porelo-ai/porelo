package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link AllowedSigner}. */
class AllowedSignerTest {

    private static final int UNWRITABLE = 3;

    /**
     * A scratch file that cannot be written is still removed, so a caller's directory holds no copy
     * of the signers it was asked about: the read runs in a JVM of its own that may create files
     * but not write a byte into one.
     */
    @Test
    void removes_its_check_file_when_writing_it_fails(@TempDir final Path dir) throws Exception {
        final Path signers = dir.resolve("signers");
        Files.writeString(
                signers, "demo " + SigningKey.generate("one").authorizedKeysLine() + "\n");
        final Path scratch = Files.createDirectory(dir.resolve("scratch"));

        final CommandResult result =
                new ProcessCommandRunner()
                        .run(
                                Command.of(
                                        "bash",
                                        "-c",
                                        "ulimit -f 0 && exec \"$@\"",
                                        "bash",
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-XX:-UsePerfData",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        UnwritableRead.class.getName(),
                                        signers.toString(),
                                        scratch.toString()));

        assertThat(result.exitCode())
                .as("the write failed as an unwritable scratch file: %s", result.standardError())
                .isEqualTo(UNWRITABLE);
        try (var left = Files.list(scratch)) {
            assertThat(left.toList()).as("the check file is gone").isEmpty();
        }
    }

    /** Reads a signers file with its scratch file in a directory, in a JVM of its own. */
    static final class UnwritableRead {

        private UnwritableRead() {}

        public static void main(final String[] args) throws Exception {
            try {
                AllowedSigner.read(
                        command -> {
                            throw new IllegalStateException("ssh-keygen is not reached");
                        },
                        Path.of(args[0]),
                        Map.of(),
                        Path.of(args[1]));
            } catch (final UncheckedIOException ex) {
                System.exit(UNWRITABLE);
            }
        }
    }
}
