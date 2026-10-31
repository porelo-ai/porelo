package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards against leaving an entire untrusted clone after a single failed deletion. */
class RegistryRepositoryTest {
    @TempDir Path dir;

    @Test
    void cleanup_attempts_every_path_even_when_one_directory_refuses_deletion() throws Exception {
        final Path scratch = Files.createDirectory(dir.resolve("scratch"));
        final List<Path> removable = removable(scratch, "before");
        final Path locked = Files.createDirectory(scratch.resolve("z-locked"));
        final Path child = Files.writeString(locked.resolve("child"), "untrusted");
        removable.addAll(removable(scratch, "after"));
        final RegistryRepository repository =
                new RegistryRepository(scratch, new ProcessCommandRunner());
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(repository::close)
                    .as("a deletion failure is reported")
                    .isInstanceOf(IOException.class);
            assertThat(child).as("the fixture really prevented its child's deletion").exists();
            assertThat(removable)
                    .as("one failure must not leave other untrusted content behind")
                    .noneMatch(Files::exists);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            repository.close();
        }
    }

    @Test
    void cleanup_removes_accessible_paths_even_when_a_directory_cannot_be_listed()
            throws Exception {
        final Path scratch = Files.createDirectory(dir.resolve("scratch"));
        final List<Path> removable = removable(scratch, "before");
        final Path locked = Files.createDirectory(scratch.resolve("z-locked"));
        Files.writeString(locked.resolve("child"), "untrusted");
        removable.addAll(removable(scratch, "after"));
        final RegistryRepository repository =
                new RegistryRepository(scratch, new ProcessCommandRunner());
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            final Throwable failure =
                    org.assertj.core.api.Assertions.catchThrowable(repository::close);
            assertThat(removable)
                    .as("an unreadable directory must not prevent cleaning other content")
                    .noneMatch(Files::exists);
            assertThat(failure)
                    .as("the unreadable directory is reported")
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            repository.close();
        }
    }

    /**
     * Untrusted files made before or after the locked directory, so that whatever order the file
     * system lists them in, some come after it.
     */
    private static List<Path> removable(final Path scratch, final String when) throws IOException {
        final List<Path> made = new ArrayList<>();
        for (final String name : List.of("a", "m", "zz")) {
            made.add(Files.writeString(scratch.resolve(name + "-" + when), "untrusted"));
        }
        return made;
    }
}
