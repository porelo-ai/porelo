package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RootOwnedFileTest {
    @TempDir Path directory;

    private Path file() throws IOException {
        final Path file = Files.writeString(directory.resolve("host-data"), "host data");
        Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        return file;
    }

    private Map<String, Object> rootOwned(Path path) throws IOException {
        final Map<String, Object> values =
                new HashMap<>(
                        Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
        values.put("uid", 0);
        // Isolate the link-type check from symlinks' normally world-writable permission bits.
        if (Files.isSymbolicLink(path)) {
            values.put("mode", ((Number) values.get("mode")).intValue() & ~0022);
        }
        if (!path.startsWith(directory)) {
            values.put("mode", 040755);
        }
        return values;
    }

    private void refuses(Path file, Path unsafe, String attribute, int value, String reason) {
        assertThatThrownBy(
                        () ->
                                RootOwnedFile.read(
                                        file,
                                        100,
                                        path -> {
                                            final Map<String, Object> values = rootOwned(path);
                                            if (path.equals(unsafe)) {
                                                values.put(attribute, value);
                                            }
                                            return values;
                                        },
                                        0))
                .as(reason)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(unsafe.toString());
    }

    @Test
    void aRootOwnedFileIsReadCompletelyAtTheLimit() throws Exception {
        final Path file = file();
        assertThat(RootOwnedFile.read(file, 9, this::rootOwned, 0))
                .as("a complete checked file fits exactly at the byte limit")
                .isEqualTo("host data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aFileOwnedByAnotherAccountIsRefused() throws Exception {
        // Another account must not supply data interpreted by root.
        final Path file = file();
        refuses(file, file, "uid", 1000, "unprivileged ownership is refused");
    }

    @Test
    void aParentOwnedByAnotherAccountIsRefused() throws Exception {
        // Another account must not replace a file through its directory.
        refuses(file(), directory, "uid", 1000, "unprivileged parent ownership is refused");
    }

    @ParameterizedTest
    @ValueSource(ints = {0100664, 0100646})
    void aFileWritableByGroupOrOthersIsRefused(int mode) throws Exception {
        // Another account must not change data after root trusts its owner.
        final Path file = file();
        refuses(file, file, "mode", mode, "writable host data is refused");
    }

    @ParameterizedTest
    @ValueSource(ints = {040775, 040757, 041777})
    void aWritableParentIncludingAStickyDirectoryIsRefused(int mode) throws Exception {
        // Sticky directories still let their file owners replace entries.
        refuses(file(), directory, "mode", mode, "every parent must exclude other writers");
    }

    @Test
    void theRootDirectoryItselfIsChecked() throws Exception {
        // An unchecked ancestor would leave a path replacement route open.
        refuses(file(), Path.of("/"), "mode", 040777, "checks extend through the filesystem root");
    }

    @Test
    void aSymbolicLinkIsRefused() throws Exception {
        // A link must not redirect a privileged read to another file.
        final Path target = file();
        final Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        assertThatThrownBy(() -> RootOwnedFile.read(link, 100, this::rootOwned, 0))
                .as("a link is not a regular host data file")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void aSymbolicLinkInAParentIsRefused() throws Exception {
        // A parent link can redirect the entire checked path.
        file();
        final Path link = Files.createSymbolicLink(directory.resolve("link"), directory);
        assertThatThrownBy(
                        () ->
                                RootOwnedFile.read(
                                        link.resolve("host-data"), 100, this::rootOwned, 0))
                .as("parent links are refused before opening the leaf")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a directory");
    }

    @ParameterizedTest
    @ValueSource(ints = {0040755, 0010600, 0140600})
    void aNonregularFileIsRefusedBeforeOpening(int mode) throws Exception {
        // Devices, FIFOs and sockets could block or have privileged side effects.
        final Path file = file();
        refuses(file, file, "mode", mode, "only regular files may be read");
    }

    @Test
    void aFileExceedingTheReadLimitIsRefused() throws Exception {
        // Unbounded data must not exhaust the privileged program's memory.
        final Path file = file();
        assertThatThrownBy(() -> RootOwnedFile.read(file, 8, this::rootOwned, 0))
                .as("the byte after the limit distinguishes truncation from a complete read")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds read limit");
    }

    @Test
    void aMissingFileIsRefused() {
        // Missing configuration must not be treated as safe empty data.
        assertThatThrownBy(
                        () ->
                                RootOwnedFile.read(
                                        directory.resolve("absent"), 100, this::rootOwned, 0))
                .as("absence fails closed")
                .isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", "/etc/../etc/passwd"})
    void anAmbiguousPathIsRefused(String path) {
        // Normalizing away a directory can skip a symlink or permission check.
        assertThatThrownBy(() -> RootOwnedFile.read(Path.of(path), 100, this::rootOwned, 0))
                .as("only explicit absolute normalized paths are checked")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void anUnboundedOrInvalidLimitIsRefused(int limit) throws Exception {
        // Limit overflow must not permit an unlimited read.
        final Path file = file();
        assertThatThrownBy(() -> RootOwnedFile.read(file, limit, this::rootOwned, 0))
                .as("read bounds cannot overflow")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnmappedHostsRootIsAcceptedButTheNamespaceOwnerIsRefused() throws Exception {
        final Path file = file();
        final RootOwnedFile.Attributes unmappedRoot =
                path -> {
                    final Map<String, Object> values = rootOwned(path);
                    values.put("uid", 65534);
                    return values;
                };
        assertThat(RootOwnedFile.read(file, 100, unmappedRoot, 65534))
                .as("the host's root may appear as the overflow uid")
                .hasSize(9);
        // Namespace uid zero may be the unprivileged caller rather than the host's root.
        assertThatThrownBy(() -> RootOwnedFile.read(file, 100, this::rootOwned, 65534))
                .as("uid zero inside a rootless namespace cannot supply host configuration")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not owned by root");
    }

    @Test
    void theOpenItselfDoesNotFollowALinkEvenAfterAttributeChecks() throws Exception {
        // NOFOLLOW at open prevents a leaf replacement from following a link.
        final Path file = file();
        final Path link = Files.createSymbolicLink(directory.resolve("link"), file);
        assertThatThrownBy(
                        () ->
                                RootOwnedFile.read(
                                        link,
                                        100,
                                        path -> {
                                            final Map<String, Object> values = rootOwned(path);
                                            if (path.equals(link)) {
                                                values.put("mode", 0100644);
                                            }
                                            return values;
                                        },
                                        0))
                .as("open refuses a link independently of the prior type check")
                .isInstanceOf(IOException.class);
    }
}
