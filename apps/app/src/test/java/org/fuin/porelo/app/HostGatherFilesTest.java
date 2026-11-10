package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherFilesTest {
    @TempDir Path dir;
    private Path home;
    private Path conf;
    private long uid;
    private final HostGatherFiles files = new HostGatherFiles();

    @BeforeEach
    void fixture() throws Exception {
        home = dir.resolve("home");
        conf = home.resolve(".config/porelo-host/push/acme.conf");
        Files.createDirectories(conf.getParent());
        for (Path path : paths()) Files.setAttribute(path, "unix:mode", 0700);
        Files.writeString(conf, "fixture configuration\n");
        Files.setAttribute(conf, "unix:mode", 0600);
        uid = ((Number) Files.getAttribute(home, "unix:uid")).longValue();
    }

    private java.util.List<Path> paths() {
        return java.util.List.of(
                home,
                home.resolve(".config"),
                home.resolve(".config/porelo-host"),
                conf.getParent());
    }

    @Test
    void readsTheCheckedPersonFile() throws Exception {
        // A valid private config must remain usable without privilege or another person's home.
        assertThat(files.read(home, uid, "acme")).isEqualTo("fixture configuration\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "home-link",
                "config-link",
                "host-link",
                "push-link",
                "file-link",
                "home-mode",
                "config-mode",
                "host-mode",
                "push-mode",
                "file-mode",
                "missing",
                "directory",
                "foreign",
                "oversized",
                "nul"
            })
    void refusesUntrustedPathsAndBytes(String defect) throws Exception {
        // Unchecked config ownership or bytes can redirect a credential.
        Path victim =
                switch (defect.split("-")[0]) {
                    case "home" -> home;
                    case "config" -> home.resolve(".config");
                    case "host" -> home.resolve(".config/porelo-host");
                    case "push" -> conf.getParent();
                    default -> conf;
                };
        if (defect.endsWith("-link")) {
            final Path moved = victim.resolveSibling(victim.getFileName() + "-moved");
            Files.move(victim, moved);
            Files.createSymbolicLink(victim, moved);
        } else if (defect.endsWith("-mode")) {
            Files.setAttribute(
                    victim,
                    "unix:mode",
                    victim.equals(conf) ? 0644 : defect.equals("push-mode") ? 0755 : 0770);
        } else if (defect.equals("missing")) Files.delete(conf);
        else if (defect.equals("directory")) {
            Files.delete(conf);
            Files.createDirectory(conf);
        } else if (defect.equals("oversized")) Files.writeString(conf, " ".repeat(65537));
        else if (defect.equals("nul")) Files.writeString(conf, "configuration\0hidden");
        final long expected = defect.equals("foreign") ? uid + 1 : uid;
        assertThatThrownBy(() -> files.read(home, expected, "acme"))
                .isInstanceOfSatisfying(
                        HostGatherRefusal.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(66));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "mode", "fifo"})
    void rechecksTheOpenedFileBeforeReading(String defect) {
        // A path check alone must not accept an inode swapped before open or block on a pipe.
        files.openedAttributes =
                path -> {
                    final var actual =
                            new java.util.HashMap<>(Files.readAttributes(path, "unix:uid,mode"));
                    if (((Number) actual.get("mode")).intValue() / 010000 == 010) {
                        if (defect.equals("foreign")) actual.put("uid", uid + 1);
                        if (defect.equals("mode")) actual.put("mode", 0100644);
                        if (defect.equals("fifo")) actual.put("mode", 0010600);
                    }
                    return actual;
                };
        assertThatThrownBy(() -> files.read(home, uid, "acme"))
                .isInstanceOf(HostGatherRefusal.class);
    }

    @Test
    void rejectsARealFifoWithoutWaitingForAWriter() throws Exception {
        // A substituted pipe must not hang a vault-backed transfer while holding its credentials.
        Files.delete(conf);
        final Process fifo =
                new ProcessBuilder("/usr/bin/mkfifo", "-m", "600", conf.toString()).start();
        assertThat(fifo.waitFor()).isZero();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () ->
                        assertThatThrownBy(() -> files.read(home, uid, "acme"))
                                .isInstanceOf(HostGatherRefusal.class));
    }

    @Test
    void aStalledReadRefusesWithinItsBudget() {
        // A filesystem that stalls must not hold a transfer's in-memory credentials indefinitely.
        files.timeout = java.time.Duration.ofMillis(20);
        final var original = files.openedAttributes;
        files.openedAttributes =
                path -> {
                    try {
                        new java.util.concurrent.CountDownLatch(1)
                                .await(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("interrupted", ex);
                    }
                    return original.read(path);
                };
        assertThatThrownBy(() -> files.read(home, uid, "acme"))
                .isInstanceOf(HostGatherRefusal.class);
    }

    @Test
    void aRenamedConfigIsReadThroughItsCheckedDescriptor() throws Exception {
        // Replacing a path after open must not replace the bytes whose ownership was validated.
        final var original = files.openedAttributes;
        files.openedAttributes =
                path -> {
                    final var attrs = original.read(path);
                    if ((((Number) attrs.get("mode")).intValue() & 0170000) == 0100000) {
                        Files.move(conf, conf.resolveSibling("saved.conf"));
                        Files.writeString(conf, "replacement config");
                    }
                    return attrs;
                };
        assertThat(files.read(home, uid, "acme")).isEqualTo("fixture configuration\n");
    }

    @Test
    void unsignedUidsCanOwnTheConfiguration() throws Exception {
        // A valid high Linux uid must not be mistaken for a different negative owner.
        final var original = files.openedAttributes;
        files.openedAttributes =
                path -> {
                    final var attrs = new java.util.HashMap<>(original.read(path));
                    attrs.put("uid", (int) 4000000000L);
                    return attrs;
                };
        assertThat(files.read(home, 4000000000L, "acme")).isEqualTo("fixture configuration\n");
    }
}
