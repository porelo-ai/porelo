package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A log's identity holds while it is appended to, whether or not its file system keeps a birth
 * time.
 */
class LogIdentityTest {

    private static final Instant BORN = Instant.parse("2026-01-01T10:00:00Z");

    private static BasicFileAttributes file(
            final @Nullable Object key, final Instant created, final Instant modified) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return FileTime.from(modified);
            }

            @Override
            public FileTime lastAccessTime() {
                return FileTime.from(modified);
            }

            @Override
            public FileTime creationTime() {
                return FileTime.from(created);
            }

            @Override
            public boolean isRegularFile() {
                return true;
            }

            @Override
            public boolean isDirectory() {
                return false;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public @Nullable Object fileKey() {
                return key;
            }
        };
    }

    @Test
    void identityHoldsAcrossAppendsWhereCreationTimeFollowsModificationTime() throws Exception {
        final Instant first = BORN.plusSeconds(5);
        final Instant later = BORN.plusSeconds(60);

        assertThat(LogIdentity.of(file("(dev=1,ino=7)", first, first), false))
                .isEqualTo(LogIdentity.of(file("(dev=1,ino=7)", later, later), false));
    }

    @Test
    void identityHoldsAcrossAppendsWhereBirthTimeIsKept() throws Exception {
        // Not yet written to (creation equals modification), then written to twice: one name, which
        // a rule per file, taking equal times for no birth time, would break.
        final String empty = LogIdentity.of(file("(dev=1,ino=7)", BORN, BORN), true);

        assertThat(LogIdentity.of(file("(dev=1,ino=7)", BORN, BORN.plusSeconds(5)), true))
                .isEqualTo(empty);
        assertThat(LogIdentity.of(file("(dev=1,ino=7)", BORN, BORN.plusSeconds(60)), true))
                .isEqualTo(empty);
    }

    @Test
    void aFileMadeAnewIsAnotherLogWhereBirthTimeIsKept() throws Exception {
        final Instant newer = BORN.plusSeconds(100);

        assertThat(LogIdentity.of(file("(dev=1,ino=7)", BORN, BORN.plusSeconds(5)), true))
                .isNotEqualTo(LogIdentity.of(file("(dev=1,ino=7)", newer, newer), true));
    }

    @Test
    void aFileOfAnotherKeyIsAnotherLogWhereNoBirthTimeIsKept() throws Exception {
        assertThat(LogIdentity.of(file("(dev=1,ino=7)", BORN, BORN), false))
                .isNotEqualTo(LogIdentity.of(file("(dev=1,ino=8)", BORN, BORN), false));
    }

    @Test
    void aFileSystemNamingNoFileKeyIsRefused() {
        assertThatThrownBy(() -> LogIdentity.of(file(null, BORN, BORN), true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no file key");
    }

    @Test
    void theProbeAnswersForThisFileSystemAndLeavesNoFileBehind(@TempDir final Path dir)
            throws Exception {
        assertThat(LogIdentity.probe(dir.resolve("probe"))).isTrue();

        try (var files = Files.list(dir.resolve("probe"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aFailingProbeAnswersNoBirthTimeOnceAndIsNotAskedAgain(@TempDir final Path dir)
            throws Exception {
        final FileStore store = Files.getFileStore(dir);
        final Map<FileStore, Boolean> cache = new ConcurrentHashMap<>();
        final AtomicInteger probes = new AtomicInteger();
        final LogIdentity.Probe failing =
                directory -> {
                    probes.incrementAndGet();
                    throw new IOException("not writable");
                };

        assertThat(LogIdentity.birthTimeKept(store, dir, cache, failing)).isFalse();
        assertThat(LogIdentity.birthTimeKept(store, dir, cache, failing)).isFalse();
        assertThat(probes).hasValue(1);
    }

    @Test
    void aFileOnAStoreNotProbedIsNamedByItsKeyAlone(@TempDir final Path dir) throws Exception {
        final Path log = dir.resolve("task.log");
        Files.writeString(log, "");
        final AtomicInteger probes = new AtomicInteger();

        final String named =
                LogIdentity.of(
                        log,
                        Map.of(),
                        new ConcurrentHashMap<>(),
                        directory -> {
                            probes.incrementAndGet();
                            return true;
                        });

        assertThat(named)
                .isEqualTo(
                        LogIdentity.of(
                                Files.readAttributes(log, BasicFileAttributes.class), false));
        assertThat(probes).hasValue(0);
    }

    @Test
    void aLogKeepsOneIdentityWhileAppendedToOnThisFileSystem(@TempDir final Path dir)
            throws Exception {
        final Path log = dir.resolve("task.log");
        Files.writeString(log, "");
        final Map<FileStore, Path> probeIn = Map.of(Files.getFileStore(dir), dir.resolve("probe"));
        final Map<FileStore, Boolean> cache = new ConcurrentHashMap<>();
        final String empty = LogIdentity.of(log, probeIn, cache, LogIdentity::probe);
        Files.writeString(log, "line\n", StandardOpenOption.APPEND);

        assertThat(LogIdentity.of(log, probeIn, cache, LogIdentity::probe)).isEqualTo(empty);
    }
}
