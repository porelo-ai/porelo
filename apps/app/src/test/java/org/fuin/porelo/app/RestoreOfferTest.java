package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that restoring a gate over a mirror that is there offers to move it aside under a dated
 * name, never over another, instead of sending a person to move it.
 */
class RestoreOfferTest {

    @TempDir private Path dir;

    @Test
    void theMirrorGoesAsideUnderADatedNameThatIsNeverTaken() throws Exception {
        final Path mirror = Files.createDirectories(dir.resolve("uc.git"));
        assertThat(GateRestoreCommand.aside(mirror, LocalDate.of(2026, 10, 11)))
                .isEqualTo(dir.resolve("uc.git.aside-2026-10-11"));
        Files.createDirectories(dir.resolve("uc.git.aside-2026-10-11"));
        assertThat(GateRestoreCommand.aside(mirror, LocalDate.of(2026, 10, 11)))
                .isEqualTo(dir.resolve("uc.git.aside-2026-10-11-2"));
    }

    @Test
    void itIsAskedAndNobodyToAskIsTheRefusalAsBefore() {
        final Path mirror = dir.resolve("uc.git");
        assertThat(
                        GateRestoreCommand.moveAside(
                                mirror,
                                new Offer(
                                        question -> "",
                                        false,
                                        false,
                                        new PrintWriter(new StringWriter(), true))))
                .isTrue();
        assertThat(GateRestoreCommand.moveAside(mirror, Offer.NOBODY)).isFalse();
    }
}
