package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that a broker records its pid before it publishes the token a start waits for. */
class VaultServePidFirstTest {

    @Test
    void theTokenIsPublishedOnlyOnceThePidIsRecorded(@TempDir Path dir) {

        // A start takes the broker for up once its token is there: written before the pid, a start
        // that failed in between found nothing to stop it by, and the broker held the credential.
        final Path pidFile = dir.resolve("vault.pid");
        final List<String> seen = new ArrayList<>();

        final boolean published =
                VaultServeCommand.recordedThenPublished(
                        pidFile,
                        () -> seen.add(Files.exists(pidFile) ? "pid, then token" : "token first"),
                        new PrintWriter(new StringWriter()));

        assertThat(published).isTrue();
        assertThat(seen)
                .as("the pid is there when the token is")
                .containsExactly("pid, then token");
    }

    @Test
    void aBrokerThatCannotRecordItsPidPublishesNoToken(@TempDir Path dir) throws Exception {
        final Path pidFile = Files.createDirectories(dir.resolve("vault.pid"));
        final List<String> seen = new ArrayList<>();
        final StringWriter err = new StringWriter();

        final boolean published =
                VaultServeCommand.recordedThenPublished(
                        pidFile, () -> seen.add("token"), new PrintWriter(err, true));

        assertThat(published).as("it refuses to serve unnamed").isFalse();
        assertThat(seen).as("so no start takes it for up").isEmpty();
        assertThat(err.toString()).contains("cannot write " + pidFile);
    }
}
