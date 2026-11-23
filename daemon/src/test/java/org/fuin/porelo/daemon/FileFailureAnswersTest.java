package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a daemon's Failed answer for a file it could not read or write says what failed and
 * the kind of error, never the file's path on this machine.
 */
class FileFailureAnswersTest {

    private static final String TASK = "porelo-uc-work";

    private static PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private static VarlinkException failed(
            final Path dir,
            final PoreloContext context,
            final String method,
            final Map<String, Object> parameters)
            throws IOException {
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final VarlinkException[] thrown = new VarlinkException[1];
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + "." + method, parameters))
                        .isInstanceOfSatisfying(VarlinkException.class, ex -> thrown[0] = ex);
                return thrown[0];
            }
        }
    }

    @Test
    void anArchiveThatCannotBeWrittenIsAnsweredWithoutItsPath(@TempDir final Path dir)
            throws IOException {
        // A file where the archives' directory goes: creating it fails with the path as message.
        final PoreloContext context = context(dir);
        Files.createDirectories(dir.resolve("state/porelo"));
        Files.writeString(context.paths().xdg().state().resolve("archives"), "not a directory");

        final VarlinkException ex = failed(dir, context, "LogArchive", Map.of("task", TASK));

        assertThat(ex.getErrorName()).isEqualTo(PoreloDaemon.INTERFACE + ".Failed");
        assertThat(String.valueOf(ex.getParameters().get("message")))
                .as("what failed, for which task, and the kind of error")
                .contains("uc-work", "file already exists")
                .doesNotContain("/");
    }

    @Test
    void anUnreadableHandInRecordIsAnsweredWithoutItsPath(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path record =
                context.paths().xdg().state().resolve("hand-ins").resolve(TASK + ".jsonl");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{}\n");
        Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("---------"));

        final VarlinkException ex = failed(dir, context, "HandIns", Map.of("task", TASK));

        assertThat(ex.getErrorName()).isEqualTo(PoreloDaemon.INTERFACE + ".Failed");
        assertThat(String.valueOf(ex.getParameters().get("message")))
                .as("what failed, for which task, and the kind of error")
                .contains("uc-work", "access denied")
                .doesNotContain("/");
    }

    @Test
    void theKindOfErrorNamesNoPathAndTheLogKeepsIt() {
        assertThat(FileFailure.kind(new NoSuchFileException("/home/someone/x.log")))
                .isEqualTo("no such file");
        assertThat(FileFailure.kind(new AccessDeniedException("/home/someone/x.log")))
                .isEqualTo("access denied");
        assertThat(
                        FileFailure.kind(
                                new FileSystemException(
                                        "/home/someone/x.log", null, "Read-only file system")))
                .isEqualTo("Read-only file system");
        assertThat(FileFailure.kind(new IOException("/home/someone/x.log: too large")))
                .isEqualTo("an input or output error");
        assertThat(FileFailure.kind(new IOException("the record is too large")))
                .as("a message that names no path is said as it is")
                .isEqualTo("the record is too large");

        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        final String said =
                FileFailure.said(
                        "the record cannot be read",
                        new NoSuchFileException("/home/someone/x.log"),
                        new PrintStream(log, true, StandardCharsets.UTF_8));
        assertThat(said).isEqualTo("the record cannot be read: no such file");
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("the daemon's own log keeps the path")
                .contains("/home/someone/x.log");
    }
}
