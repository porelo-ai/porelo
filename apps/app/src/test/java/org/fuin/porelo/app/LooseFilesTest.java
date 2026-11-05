package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code porelo doctor} names, and what it leaves because a task reads it. */
class LooseFilesTest {

    @TempDir Path dir;

    private XdgPaths paths;

    private Path data;

    private Path state;

    private LooseFiles looseFiles;

    @BeforeEach
    void tightMachine() throws Exception {
        data = owner(dir.resolve("data"));
        state = owner(dir.resolve("state"));
        paths =
                new XdgPaths(
                        owner(dir.resolve("config")),
                        data,
                        state,
                        owner(dir.resolve("runtime")),
                        owner(dir.resolve("home")));
        looseFiles = new LooseFiles(paths);
    }

    private static Path owner(final Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private Path file(final Path path, final String mode) throws Exception {
        Files.createDirectories(path.getParent());
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
    void aMachineOfThisAccountAloneHasNothingToName() throws Exception {
        file(data.resolve("projects/one"), "rw-------");

        assertThat(looseFiles.found()).isEmpty();
    }

    @Test
    void aFileAnyoneElseMayReadIsNamedInEachPlaceAndAnyMode() throws Exception {
        final Path registry = file(data.resolve("projects/one"), "rw-rw-r--");
        final Path usage = file(state.resolve("usage/one"), "rw-r-----");
        final Path checkout =
                file(paths.home().resolve("porelo/reviews/gated-one/README.md"), "rw----r--");

        assertThat(looseFiles.found()).contains(registry, usage, checkout);
    }

    @Test
    void aLooseDirectoryIsNamedAndStandsForWhatIsBelowIt() throws Exception {
        final Path mirror = file(data.resolve("mirrors/one.git/config"), "rw-rw-r--").getParent();
        Files.setPosixFilePermissions(mirror, PosixFilePermissions.fromString("rwxrwxr-x"));

        final List<Path> found = looseFiles.found();

        assertThat(found).contains(mirror, mirror.resolve("config"));
        assertThat(LooseFiles.topmost(found)).containsExactly(mirror);
    }

    @Test
    void aLinkIsNeitherFollowedNorNamed() throws Exception {
        final Path outside = file(dir.resolve("outside/secret"), "rw-r--r--");
        owner(data.resolve("projects"));
        Files.createSymbolicLink(data.resolve("projects/link"), outside);

        assertThat(looseFiles.found()).isEmpty();
    }

    @Test
    void whatATaskReadsAndWhatAnImageCopiesAreLeftOpen() throws Exception {
        file(state.resolve("mail/task/box/inbox/new/one"), "rw-r--r--");
        file(state.resolve("mail/task/.guide1.tmp"), "rw-r--r--");
        file(state.resolve("guides/task/guide.md"), "rw-r--r--");
        file(state.resolve("guides/.guide1.tmp"), "rw-r--r--");
        file(data.resolve("build/project/agent-tree/bin/agent"), "rwxr-xr-x");

        assertThat(looseFiles.found()).isEmpty();
    }

    @Test
    void whatIsNotMountedIsNamedBesideWhatIs() throws Exception {
        final Path refused = file(state.resolve("mail/task/refused/one"), "rw-r--r--");
        final Path record = file(state.resolve("tasks/task/note"), "rw-r--r--");
        final Path containerfile = file(data.resolve("build/project/Containerfile"), "rw-r--r--");
        final Path sameName = file(state.resolve("usage/box/screen"), "rw-r--r--");

        assertThat(looseFiles.found())
                .containsExactlyInAnyOrder(refused, record, containerfile, sameName);
    }

    @Test
    void aPathThatBecameALinkIsNotFollowedOutOfTheRoots() throws Exception {
        final Path outside = file(dir.resolve("outside/mine"), "rw-r--r--");
        final Path swapped = data.resolve("swapped");
        Files.createSymbolicLink(swapped, outside);

        assertThat(LooseFiles.tighten(List.of(swapped))).containsExactly(swapped);

        assertThat(mode(outside)).isEqualTo("rw-r--r--");
    }

    @Test
    void aSecondNameOutsideKeepsItsMode() throws Exception {
        final Path object = file(data.resolve("mirrors/one.git/objects/ab/cd"), "r--r--r--");
        final Path persons = dir.resolve("outside/clone-object");
        Files.createDirectories(persons.getParent());
        Files.createLink(persons, object);

        assertThat(LooseFiles.tighten(looseFiles.found())).isEmpty();

        assertThat(mode(object)).isEqualTo("r--------");
        assertThat(mode(persons)).isEqualTo("r--r--r--");
        assertThat(Files.readString(object)).isEqualTo("x");
        assertThat(Files.list(object.getParent())).hasSize(1);
    }

    @Test
    void tighteningTakesOnlyWhatOthersHadAndLeavesNothingToName() throws Exception {
        final Path executable = file(data.resolve("projects/run"), "rwxrwxr-x");
        final Path plain = file(data.resolve("projects/one"), "rw-r--r--");

        assertThat(LooseFiles.tighten(looseFiles.found())).isEmpty();

        assertThat(mode(executable)).isEqualTo("rwx------");
        assertThat(mode(plain)).isEqualTo("rw-------");
        assertThat(looseFiles.found()).isEmpty();
    }

    @Test
    void theProbeNamesThemAndTheWayOut() throws Exception {
        final Path registry = file(data.resolve("projects/one"), "rw-rw-r--");

        final Probe probe = DoctorCommand.privateFiles(looseFiles);

        assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(DoctorCommand.ready(List.of(probe)))
                .as("the daemon's answer and this command's stay one")
                .isTrue();
        assertThat(DoctorCommand.exit(List.of(probe), true)).isEqualTo(1);
        assertThat(probe.detail()).contains(registry.toString());
        assertThat(probe.action()).contains("porelo tighten");
    }

    @Test
    void theCommandSaysWhatItChangedAndThatNothingIsLeft() throws Exception {
        final Path registry = file(data.resolve("projects/one"), "rw-rw-r--");
        final java.io.StringWriter out = new java.io.StringWriter();
        final java.io.StringWriter err = new java.io.StringWriter();

        final int exit =
                TightenCommand.tighten(
                        looseFiles, new java.io.PrintWriter(out), new java.io.PrintWriter(err));

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("tightened 1 of 1", registry.toString());
        assertThat(mode(registry)).isEqualTo("rw-------");

        final java.io.StringWriter again = new java.io.StringWriter();
        assertThat(
                        TightenCommand.tighten(
                                looseFiles,
                                new java.io.PrintWriter(again),
                                new java.io.PrintWriter(err)))
                .isZero();
        assertThat(again.toString()).contains("nothing to tighten");
    }

    @Test
    void theProbeIsQuietOnAMachineOfThisAccountAlone() {
        final Probe probe = DoctorCommand.privateFiles(looseFiles);

        assertThat(probe.state()).isEqualTo(Probe.State.OK);
        assertThat(DoctorCommand.exit(List.of(probe), true)).isZero();
    }
}
