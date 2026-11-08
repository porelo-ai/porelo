package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A dry run of {@code porelo tighten} and the run itself, as the command and the daemon ask. */
class TighteningTest {

    @TempDir Path dir;

    private XdgPaths paths;

    private Path data;

    @BeforeEach
    void machine() throws Exception {
        data = owner(dir.resolve("data"));
        paths =
                new XdgPaths(
                        owner(dir.resolve("config")),
                        data,
                        owner(dir.resolve("state")),
                        owner(dir.resolve("runtime")),
                        owner(dir.resolve("home")));
    }

    private static Path owner(final Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private Path file(final Path path, final String mode) throws Exception {
        Files.createDirectories(path.getParent());
        // Only what the test names is loose, whatever the umask made of its parents.
        for (Path at = path.getParent(); !at.equals(dir); at = at.getParent()) {
            Files.setPosixFilePermissions(at, PosixFilePermissions.fromString("rwx------"));
        }
        Files.writeString(path, "x");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        return path;
    }

    private static String mode(final Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void aDryRunListsEachPathFromItsModeToTheTightenedOneAndChangesNoMode() throws Exception {
        final Path registry = file(data.resolve("projects/one"), "rw-rw-r--");
        final Path executable = file(data.resolve("projects/run"), "rwxr-xr-x");

        final Tightening.Outcome dry = Tightening.of(paths, true);

        assertThat(dry.changes())
                .as("each loose path, from its mode to the tightened one")
                .containsExactlyInAnyOrder(
                        new Tightening.Change(registry, "rw-rw-r--", "rw-------"),
                        new Tightening.Change(executable, "rwxr-xr-x", "rwx------"));
        assertThat(dry.left()).as("nothing it could not change").isEmpty();
        assertThat(mode(registry)).as("a dry run changes no mode").isEqualTo("rw-rw-r--");
        assertThat(mode(executable)).as("a dry run changes no mode").isEqualTo("rwxr-xr-x");
    }

    @Test
    void theRunChangesExactlyWhatTheDryRunListed() throws Exception {
        file(data.resolve("projects/one"), "rw-rw-r--");
        file(data.resolve("mirrors/one.git/config"), "rw-r--r--");
        Files.setPosixFilePermissions(
                data.resolve("mirrors/one.git"), PosixFilePermissions.fromString("rwxr-xr-x"));

        final Tightening.Outcome dry = Tightening.of(paths, true);
        final Tightening.Outcome run = Tightening.of(paths, false);

        assertThat(dry.changes()).as("a dry run that lists something").isNotEmpty();
        assertThat(run.changes())
                .as("the run changes what the dry run listed")
                .isEqualTo(dry.changes());
        for (final Tightening.Change change : dry.changes()) {
            assertThat(mode(change.path()))
                    .as("the mode the dry run said " + change.path() + " would get")
                    .isEqualTo(change.to());
        }
        assertThat(Tightening.of(paths, true).changes())
                .as("nothing is left to tighten after the run")
                .isEmpty();
    }

    @Test
    void aPathThatIsALinkByNowIsSaidAsLeftInTheDryRunAndTheRun() throws Exception {
        final Path outside = file(dir.resolve("outside/mine"), "rw-r--r--");
        final Path swapped = data.resolve("swapped");
        Files.createSymbolicLink(swapped, outside);
        final java.util.List<Path> found = java.util.List.of(swapped);

        final Tightening.Outcome dry = Tightening.of(found, true);
        final Tightening.Outcome run = Tightening.of(found, false);

        assertThat(dry.left())
                .as("what cannot be changed is said, never skipped")
                .containsExactly(new Tightening.Left(swapped, Tightening.Reason.LINK));
        assertThat(run.left()).as("the run agrees with the dry run").isEqualTo(dry.left());
        assertThat(mode(outside)).as("a link is never followed").isEqualTo("rw-r--r--");
    }

    @Test
    void aMachineOfThisAccountAloneHasNothingToTighten() throws Exception {
        file(data.resolve("projects/one"), "rw-------");

        final Tightening.Outcome dry = Tightening.of(paths, true);

        assertThat(dry.found()).as("nothing loose").isZero();
        assertThat(dry.changes()).as("nothing to change").isEmpty();
    }
}
