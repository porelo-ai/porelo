package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's logs kept in one archive.
 * <p>
 * <strong>What is measured hardest is what stays out and who can read it.</strong> The archive is made to be kept and
 * handed on, so the agent's own words must not ride along unasked, and nobody but the account may open it at any
 * moment.
 */
class TaskLogArchiveTest {

    private static final String CONTAINER = "sokar-uc-work";

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Path logs(final Path dir) throws IOException {
        final Path state = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(state.resolve("gate.log"), "push accepted\n");
        Files.writeString(state.resolve("events.jsonl"), "{\"daddr\":\"192.0.2.10\"}\n");
        Files.writeString(state.resolve(TaskLogArchive.AGENT_OUTPUT), "what the work was about\n");
        return state;
    }

    private static Map<String, byte[]> read(final Path archive) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (final var entry : java.util.Collections.list(zip.entries())) {
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    private static List<Path> all(final Path state) throws IOException {
        try (var files = Files.list(state)) {
            return files.sorted().toList();
        }
    }

    @Test
    void keepsTheLogsAndWhatSokarSaysButNotTheAgentsOwnWordsUnasked(@TempDir final Path dir) throws IOException {

        final Path target = dir.resolve("kept.zip");

        TaskLogArchive.write(target, "uc-work", all(logs(dir)), Map.of("status.txt", "running\n"), false);

        assertThat(read(target).keySet()).containsExactly("uc-work/README.txt", "uc-work/events.jsonl",
                "uc-work/gate.log", "uc-work/status.txt", "uc-work/SHA256SUMS");
    }

    @Test
    void takesTheAgentsOwnWordsWhenAskedFor(@TempDir final Path dir) throws IOException {

        final Path target = dir.resolve("kept.zip");

        TaskLogArchive.write(target, "uc-work", all(logs(dir)), Map.of(), true);

        assertThat(read(target)).containsKey("uc-work/task.log");
    }

    @Test
    void everyFileIsListedWithTheChecksumOfWhatWasWritten(@TempDir final Path dir) throws Exception {

        final Path target = dir.resolve("kept.zip");
        TaskLogArchive.write(target, "uc-work", all(logs(dir)), Map.of("status.txt", "running\n"), false);

        final Map<String, byte[]> entries = read(target);
        final List<String> lines = new String(entries.get("uc-work/SHA256SUMS"), StandardCharsets.UTF_8).lines()
                .toList();

        // The form 'sha256sum -c' reads: the sum, two spaces, the name beside the list.
        assertThat(lines).hasSize(entries.size() - 1);
        for (final String line : lines) {
            final String name = line.substring(66);
            final byte[] bytes = entries.get("uc-work/" + name);
            assertThat(line.substring(0, 64)).as(name)
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    @Test
    void isReadableByTheOwnerOnlyAndNeverOverwritesAFile(@TempDir final Path dir) throws IOException {

        final Path target = dir.resolve("kept.zip");
        TaskLogArchive.write(target, "uc-work", List.of(), Map.of(), false);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
        final byte[] before = Files.readAllBytes(target);
        assertThatThrownBy(() -> TaskLogArchive.write(target, "uc-work", List.of(), Map.of(), false))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test
    void aLinkAmongTheLogsIsNotFollowed(@TempDir final Path dir) throws IOException {

        final Path state = logs(dir);
        final Path secret = Files.writeString(dir.resolve("secret.txt"), "not the task's\n");
        Files.createSymbolicLink(state.resolve("other.log"), secret);
        final Path target = dir.resolve("kept.zip");

        TaskLogArchive.write(target, "uc-work", all(state), Map.of(), false);

        assertThat(read(target)).doesNotContainKey("uc-work/other.log");
    }

    @Test
    void aTaskWithoutLogsStillGetsWhatSokarSaysAboutIt(@TempDir final Path dir) throws IOException {

        // A restart took its logs. The commands still answer, and a refusal is kept with its exit code, so a missing
        // part is never an empty file.
        final SokarContext context = context(dir);
        final Path target = dir.resolve("kept.zip");

        final List<String> files = TaskLogsCommand.archive(context, CONTAINER, target, false);

        assertThat(files).containsExactly("README.txt", "status.txt", "files.txt", "talk.txt",
                TaskLogArchive.SUMS);
        assertThat(read(target).get("uc-work/talk.txt")).isNotEmpty();
    }

    @Test
    void namesTheArchiveAfterTheTaskAndTheTimeInUtc() {

        assertThat(TaskLogArchive.defaultName("uc-work", java.time.Instant.parse("2026-10-13T17:45:09Z")))
                .isEqualTo("sokar-logs-uc-work-20261013T174509Z.zip");
    }
}
