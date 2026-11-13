package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Removing a project's arrivals never follows a link and decides once what there is. */
class ProjectArrivalsRemovalTest {

    @TempDir Path root;

    private ProjectFiles files;

    @BeforeEach
    void host() throws Exception {
        final int owner = (Integer) Files.getAttribute(root, "unix:uid");
        files = new ProjectFiles(root, owner);
        Files.createDirectories(files.etc.resolve("organization/arrivals"));
        rootsAlone(files.etc.resolve("organization/arrivals"));
    }

    /** Group and others may not write, whatever the account's umask gave. */
    private void rootsAlone(final Path directory) throws Exception {
        for (Path each = directory; each.startsWith(root); each = each.getParent()) {
            Files.setPosixFilePermissions(
                    each, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aProjectWithoutArrivalsLeavesNothingToRemove() throws Exception {
        ProjectArrivals.removeProject(files, "alpha");

        assertThat(files.etc.resolve("organization/arrivals")).isEmptyDirectory();
    }

    @Test
    void aProjectsArrivalsAreRemovedWhole() throws Exception {
        final Path arrivals =
                Files.createDirectories(files.etc.resolve("organization/arrivals/alpha"));
        rootsAlone(arrivals);
        Files.writeString(arrivals.resolve("code.jsonl"), "{}\n");
        Files.setPosixFilePermissions(
                arrivals.resolve("code.jsonl"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        ProjectArrivals.removeProject(files, "alpha");

        assertThat(arrivals).doesNotExist();
    }

    @Test
    void arrivalsThatAreALinkAreRefusedAndWhatTheyLeadToStays() throws Exception {
        final Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        final Path kept = Files.writeString(elsewhere.resolve("code.jsonl"), "{}\n");
        Files.createSymbolicLink(files.etc.resolve("organization/arrivals/alpha"), elsewhere);

        assertThatThrownBy(() -> ProjectArrivals.removeProject(files, "alpha"))
                .isInstanceOf(HostRefusal.class);
        assertThat(kept).exists();
    }
}
