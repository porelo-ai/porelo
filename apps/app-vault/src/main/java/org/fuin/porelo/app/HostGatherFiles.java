package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.fuin.porelo.core.hardening.LinuxReadDescriptor;
import org.fuin.porelo.wire.RootOwnedFile;

/** Pins each component of the person's push configuration before reading its bytes. */
final class HostGatherFiles {
    private static final int MAXIMUM_BYTES = 65536;
    java.time.Duration timeout = java.time.Duration.ofSeconds(10);
    RootOwnedFile.Attributes openedAttributes =
            path -> java.nio.file.Files.readAttributes(path, "unix:uid,mode");

    String read(Path home, long uid, String project) throws IOException {
        final var task =
                new java.util.concurrent.FutureTask<String>(() -> readNow(home, uid, project));
        final Thread reader = Thread.ofVirtual().name("porelo-gather-config").start(task);
        try {
            return task.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof HostGatherRefusal refusal) throw refusal;
            throw refused(
                    home.resolve(".config/porelo-host/push/" + project + ".conf"),
                    "could not be read");
        } catch (java.util.concurrent.TimeoutException ex) {
            throw refused(
                    home.resolve(".config/porelo-host/push/" + project + ".conf"),
                    "could not be read within its time limit");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw refused(
                    home.resolve(".config/porelo-host/push/" + project + ".conf"),
                    "read was interrupted");
        } finally {
            task.cancel(true);
            reader.interrupt();
        }
    }

    private String readNow(Path home, long uid, String project) throws IOException {
        final Path source = home.resolve(".config/porelo-host/push/" + project + ".conf");
        try (LinuxReadDescriptor root = LinuxReadDescriptor.open(home, true)) {
            check(root, uid, true, -1, home);
            try (LinuxReadDescriptor config =
                            directory(root, ".config", uid, -1, home.resolve(".config"));
                    LinuxReadDescriptor host =
                            directory(
                                    config,
                                    "porelo-host",
                                    uid,
                                    -1,
                                    home.resolve(".config/porelo-host"));
                    LinuxReadDescriptor push =
                            directory(
                                    host,
                                    "push",
                                    uid,
                                    0700,
                                    home.resolve(".config/porelo-host/push"));
                    LinuxReadDescriptor file = push.child(project + ".conf", false)) {
                check(file, uid, false, 0600, source);
                final byte[] bytes;
                try (var input = Files.newInputStream(file.path())) {
                    bytes = input.readNBytes(MAXIMUM_BYTES + 1);
                }
                try {
                    if (bytes.length > MAXIMUM_BYTES) throw refused(source, "exceeds 64 KiB");
                    for (byte value : bytes)
                        if (value == 0) throw refused(source, "contains NUL bytes");
                    return StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (IOException ex) {
            throw refused(
                    source,
                    "could not be read as a plain 0600 file in its person's private push directory");
        }
    }

    private LinuxReadDescriptor directory(
            LinuxReadDescriptor parent, String name, long uid, int mode, Path source)
            throws IOException {
        final LinuxReadDescriptor opened = parent.child(name, true);
        try {
            check(opened, uid, true, mode, source);
            return opened;
        } catch (IOException | RuntimeException ex) {
            opened.close();
            throw ex;
        }
    }

    private void check(
            LinuxReadDescriptor file, long uid, boolean directory, int expected, Path source)
            throws IOException {
        final var attrs = openedAttributes.read(file.path());
        if (!(attrs.get("uid") instanceof Number owner)
                || Integer.toUnsignedLong(owner.intValue()) != uid)
            throw refused(source, "is not owned by the calling uid " + uid);
        if (!(attrs.get("mode") instanceof Number mode)
                || (mode.intValue() & 0170000) != (directory ? 0040000 : 0100000)
                || (mode.intValue() & 0022) != 0
                || expected >= 0 && (mode.intValue() & 07777) != expected)
            throw refused(
                    source,
                    "is not a "
                            + (directory ? "trusted directory" : "plain 0600 file")
                            + " of the calling person");
    }

    private static HostGatherRefusal refused(Path source, String reason) {
        return new HostGatherRefusal(66, source + " " + reason);
    }
}
