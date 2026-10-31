package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to the socket file, which is the part an interface trips over rather than the
 * protocol.
 */
class VarlinkServerSocketTest {
    @Test
    void anotherNumericOwnerIsRefusedBeforePermissionsChangeOrBind(@TempDir Path dir)
            throws Exception {
        final long uid = ((Number) Files.getAttribute(dir, "unix:uid")).longValue();
        onlyRootAbove(dir);
        final var permissions =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(dir, permissions);
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        dir.resolve("foreign.sock"),
                                        "org.example.Test1",
                                        null,
                                        () -> uid + 1))
                .as("an untrusted parent must be refused before binding or changing its mode")
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("The socket directory must belong to this uid");
        assertThat(Files.getPosixFilePermissions(dir))
                .as("the foreign directory is untouched")
                .isEqualTo(permissions);
        assertThat(dir.resolve("foreign.sock")).doesNotExist();
    }

    @Test
    void aSocketDirectoryOthersCouldRenameIsRefused(@TempDir Path dir) throws Exception {
        // A runtime directory leading to a directory anybody may write to, with no sticky bit.
        final Path open = Files.createDirectory(dir.resolve("open"));
        Files.setPosixFilePermissions(
                open, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        final Path runtime = Files.createSymbolicLink(dir.resolve("runtime"), open);
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        runtime.resolve("porelo").resolve("d.sock"),
                                        "org.example.Test1"))
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage(
                        "The directory above the socket's must not be writable by others");
        assertThat(open.resolve("porelo")).doesNotExist();

        Files.setPosixFilePermissions(
                open, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final Path socket = open.resolve("porelo").resolve("d.sock");
        Files.createDirectories(socket.getParent());
        Files.createSymbolicLink(socket, dir.resolve("elsewhere.sock"));
        assertThatThrownBy(() -> new VarlinkServer(socket, "org.example.Test1"))
                .as("a link at the socket is refused, not followed")
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("The socket must not be a symbolic link");
    }

    @Test
    void everyDirectoryThePathPassesIsJudgedAsTheKernelFollowsIt(@TempDir Path dir)
            throws Exception {
        final Path open = Files.createDirectory(dir.resolve("open"));
        Files.setPosixFilePermissions(
                open, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        final var own = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        final Path sub = Files.createDirectories(open.resolve("porelo").resolve("sub"));
        Files.setPosixFilePermissions(sub, own);
        Files.setPosixFilePermissions(sub.getParent(), own);
        final Path safe = Files.createDirectory(dir.resolve("safe"));
        Files.setPosixFilePermissions(safe, own);
        // A link inside the open directory leads somewhere private: the open one is still passed.
        final Path runtime = Files.createSymbolicLink(open.resolve("runtime"), safe);
        // So it is when a private link leads through it to another link, or through its ".."s.
        final Path chained = Files.createSymbolicLink(dir.resolve("chained"), runtime);
        final Path climbing =
                Files.createSymbolicLink(
                        dir.resolve("climbing"),
                        Path.of("open", "porelo", "sub", "..", "..", "..", "safe"));
        // A target's slashes stay on its names as readlink gives them: "../" is still "..".
        final Path deep = Files.createDirectories(dir.resolve("a").resolve("b"));
        Files.setPosixFilePermissions(deep, own);
        Files.setPosixFilePermissions(deep.getParent(), own);
        // Made by ln: Path.of would drop the slash before the link is written.
        final Path up = deep.resolve("up");
        assertThat(new ProcessBuilder("ln", "-s", "../", up.toString()).start().waitFor()).isZero();
        assertThat(Files.readSymbolicLink(up).toString()).isEqualTo("../");
        for (final Path socket :
                List.of(
                        sub.resolve("..").resolve("again").resolve("d.sock"),
                        sub.resolve("deeper").resolve("d.sock"),
                        open.resolve("missing").resolve("porelo").resolve("d.sock"),
                        runtime.resolve("porelo").resolve("d.sock"),
                        chained.resolve("porelo").resolve("d.sock"),
                        climbing.resolve("porelo").resolve("d.sock"),
                        up.resolve("..").resolve("open").resolve("porelo").resolve("d.sock"))) {
            assertThatThrownBy(() -> new VarlinkServer(socket, "org.example.Test1"))
                    .as("a directory others may rename from, however far up: %s", socket)
                    .isInstanceOf(VarlinkException.class)
                    .hasRootCauseMessage(
                            "The directory above the socket's must not be writable by others");
        }
        assertThat(open.resolve("missing")).doesNotExist();
        assertThat(sub.resolve("deeper")).doesNotExist();
        assertThat(safe.resolve("porelo")).doesNotExist();
    }

    @Test
    void aLinkLeadingNowhereOrDotDotBelowAMissingNameIsRefused(@TempDir Path dir) throws Exception {
        final Path dangling =
                Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("nowhere"));
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        dangling.resolve("porelo").resolve("d.sock"),
                                        "org.example.Test1"))
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("A link on the way to the socket leads nowhere");
        // Made by createDirectories, ".." below a missing name could step out where nothing judged.
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        dir.resolve("missing")
                                                .resolve("..")
                                                .resolve("x")
                                                .resolve("d.sock"),
                                        "org.example.Test1"))
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage(
                        "The socket's directory must be made by names alone below one that does"
                                + " not exist");
        assertThat(dir.resolve("nowhere")).doesNotExist();
        assertThat(dir.resolve("missing")).doesNotExist();
    }

    @Test
    void aDirectoryAboveIsThisAccountsOrRootsAndOnlyItsOwnGroupMayWriteIt(@TempDir Path dir)
            throws Exception {
        final long uid = ((Number) Files.getAttribute(dir, "unix:uid")).longValue();
        final Path held = Files.createDirectory(dir.resolve("held"));
        Files.setPosixFilePermissions(
                held, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        final Path parent = held.resolve("porelo");
        VarlinkServer.refuseForeignAbove(parent, uid);

        Files.setPosixFilePermissions(
                held, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x"));
        final var attributes =
                Files.readAttributes(held, java.nio.file.attribute.PosixFileAttributes.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                attributes.group().getName().equals(attributes.owner().getName()),
                "the account has a group of its own");
        VarlinkServer.refuseForeignAbove(parent, uid);

        // Under another group its members could rename the socket's directory away.
        final String[] groups =
                new String(
                                new ProcessBuilder("id", "-Gn")
                                        .start()
                                        .getInputStream()
                                        .readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .strip()
                        .split(" ");
        final String other = groups[groups.length - 1];
        org.junit.jupiter.api.Assumptions.assumeFalse(
                other.equals(attributes.owner().getName()), "the account is in another group");
        Files.getFileAttributeView(held, java.nio.file.attribute.PosixFileAttributeView.class)
                .setGroup(
                        held.getFileSystem()
                                .getUserPrincipalLookupService()
                                .lookupPrincipalByGroupName(other));
        assertThatThrownBy(() -> VarlinkServer.refuseForeignAbove(parent, uid))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("The directory above the socket's must not be writable by others");

        // Last, since it needs every directory above to be root's: another uid's directory, also
        // where a user namespace shows the owner as overflowuid, is anybody's.
        Files.setPosixFilePermissions(
                held, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        onlyRootAbove(dir);
        assertThatThrownBy(() -> VarlinkServer.refuseForeignAbove(parent, uid + 1))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("The directory above the socket's must belong to this uid");
    }

    @Test
    void aSocketsDirectoryIsNamedByItsOwnName(@TempDir Path dir) throws Exception {
        final Path real = Files.createDirectory(dir.resolve("real"));
        final Path link = Files.createSymbolicLink(dir.resolve("linked"), real);
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        link.resolve(".").resolve("daemon.sock"),
                                        "org.example.Test1"))
                .as("a trailing dot does not hide the link")
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("The socket directory must not be a symbolic link");
        assertThatThrownBy(
                        () ->
                                new VarlinkServer(
                                        real.resolve("..").resolve("daemon.sock"),
                                        "org.example.Test1"))
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("The socket's directory must be named, not reached by ..");
        assertThat(real.resolve("daemon.sock")).doesNotExist();
    }

    @Test
    void aRelativeSocketPathIsBoundAsGiven(@TempDir Path dir) throws Exception {
        // Short relative paths are how a long checkout stays under the kernel's 108 bytes.
        final Path socket =
                Path.of("").toAbsolutePath().relativize(dir.resolve("runtime").resolve("d.sock"));
        try (VarlinkServer server = new VarlinkServer(socket, "org.example.Test1")) {
            assertThat(server.socketPath()).isEqualTo(socket);
            assertThat(socket.isAbsolute()).isFalse();
            assertThat(socket).exists();
        }
    }

    /** A test that names another uid as its own needs every directory above it to be root's. */
    private static void onlyRootAbove(Path dir) throws Exception {
        for (Path each = dir.toRealPath().getParent(); each != null; each = each.getParent()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    ((Number) Files.getAttribute(each, "unix:uid")).longValue() == 0,
                    each + " is not root's, so another uid cannot pass it");
        }
    }

    @Test
    void theOwnUserIsANumericUidRatherThanANameLookup() throws Exception {
        final var method = VarlinkServer.class.getDeclaredMethod("ownUser");
        method.setAccessible(true);
        final String status = Files.readString(Path.of("/proc/self/status"));
        final long uid =
                Long.parseLong(
                        status.lines()
                                .filter(line -> line.startsWith("Uid:"))
                                .findFirst()
                                .orElseThrow()
                                .trim()
                                .split("\\s+")[1]);
        assertThat(method.invoke(null)).isEqualTo(uid);
    }

    @Test
    void aSymlinkedSocketParentIsRefusedWithoutChangingItsTarget(@TempDir Path dir)
            throws Exception {
        final Path real = Files.createDirectory(dir.resolve("real"));
        final var permissions =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(real, permissions);
        final Path link = Files.createSymbolicLink(dir.resolve("linked"), real);
        assertThatThrownBy(
                        () -> new VarlinkServer(link.resolve("daemon.sock"), "org.example.Test1"))
                .isInstanceOf(VarlinkException.class)
                .hasRootCauseMessage("The socket directory must not be a symbolic link");
        assertThat(Files.getPosixFilePermissions(real)).isEqualTo(permissions);
        assertThat(real.resolve("daemon.sock")).doesNotExist();
    }

    @Test
    void theSocketDirectoryIsPrivateBeforeItCanAcceptConnections(@TempDir Path dir)
            throws Exception {
        final Path parent = dir.resolve("runtime");
        Files.createDirectories(parent);
        Files.setPosixFilePermissions(
                parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        try (VarlinkServer server =
                new VarlinkServer(parent.resolve("daemon.sock"), "org.example.Test1")) {
            assertThat(Files.getPosixFilePermissions(parent))
                    .as("private while the server is up")
                    .isEqualTo(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aPeerOfAnotherUidCannotCallAnyMethod(@TempDir Path dir) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean called =
                new java.util.concurrent.atomic.AtomicBoolean();
        try (VarlinkServer server =
                new VarlinkServer(
                        dir.resolve("daemon.sock"),
                        "org.example.Test1",
                        client -> new VarlinkServer.Peer(2000, 123, false))) {
            server.method(
                    "Read",
                    (parameters, replies) -> {
                        called.set(true);
                        replies.last(java.util.Map.of("share", "must not leave"));
                    });
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(() -> client.call("org.example.Test1.Read", java.util.Map.of()))
                        .as("another uid is disconnected before dispatch")
                        .isInstanceOf(VarlinkException.class);
                assertThat(called).as("no method received the unauthorized call").isFalse();
            } finally {
                server.close();
                serving.join();
            }
        }
    }

    @Test
    void aSocketNobodyIsListeningOnIsTakenOver(@TempDir Path dir) throws Exception {

        // The ordinary case after a machine was rebooted or a daemon was killed with SIGKILL: a
        // name with nothing behind it. Refusing to start over it would need somebody to delete a
        // file by hand before their daemon would run.
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer first = new VarlinkServer(socket, "org.example.Test1")) {
            assertThat(socket).exists();
        }
        assertThat(socket).as("close unlinks it").doesNotExist();

        Files.createFile(socket);
        try (VarlinkServer second = new VarlinkServer(socket, "org.example.Test1")) {
            assertThat(socket).exists();
        }
    }

    @Test
    void aSocketSomebodyIsListeningOnIsNotTakenAway(@TempDir Path dir) throws Exception {

        // The defect: binding unlinked whatever was there. The file is only a name, so unlinking it
        // does not stop the process holding the bound socket - it makes that process unreachable.
        // The first daemon went on running, serving nobody, and nothing said so.
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer first = new VarlinkServer(socket, "org.example.Test1")) {
            assertThatThrownBy(() -> new VarlinkServer(socket, "org.example.Test1"))
                    .isInstanceOf(VarlinkException.class)
                    .hasMessageContaining("Another server is already listening");
            assertThat(socket).as("the first one still has it").exists();
        }
    }

    @Test
    void closingUnlinksTheSocketSoNothingIsLeftAnswering(@TempDir Path dir) throws Exception {

        // An interface cannot tell a socket file with nothing behind it from a daemon that is still
        // starting: both refuse the connection. Leaving one behind is leaving a question nobody can
        // answer.
        final Path socket = dir.resolve("porelod.sock");
        final VarlinkServer server = new VarlinkServer(socket, "org.example.Test1");
        assertThat(socket).exists();
        server.close();
        assertThat(socket).doesNotExist();
    }
}
