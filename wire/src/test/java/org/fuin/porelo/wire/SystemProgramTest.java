package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards against executing a program another account can replace. */
class SystemProgramTest {

    @TempDir Path directory;

    private Path executable() throws IOException {
        final Path program = Files.writeString(directory.resolve("nft"), "");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
        return program;
    }

    // Model root ownership without requiring tests to run with root privileges.
    private Map<String, Object> rootOwned(Path path) throws IOException {
        final Map<String, Object> attributes =
                new HashMap<>(Files.readAttributes(path, "unix:uid,mode"));
        attributes.put("uid", 0);
        if (!path.startsWith(directory)) {
            attributes.put("mode", 040755);
        }
        return attributes;
    }

    @Test
    void aProgramOwnedByAnotherAccountIsRefused() throws Exception {
        final Path program = executable();
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(directory),
                                        path -> {
                                            final Map<String, Object> attributes = rootOwned(path);
                                            if (path.equals(program)) {
                                                attributes.put("uid", 1000);
                                            }
                                            return attributes;
                                        }))
                .as("another account must not supply the firewall program")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(program.toString())
                .hasMessageContaining("not owned by root");
    }

    @Test
    void aGroupWritableDirectoryIsRefused() throws Exception {
        executable();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxr-x"));
        assertThatThrownBy(() -> SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("a group member must not replace the firewall program")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("writable by group or others");
    }

    @Test
    void aMissingProgramIsRefused() {
        assertThatThrownBy(() -> SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("a missing program must not fall back to PATH")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(directory.resolve("nft").toString())
                .hasMessageContaining("not found");
    }

    @Test
    void aRootOwnedExecutableHasAnAbsolutePath() throws Exception {
        final Path program = executable();
        assertThat(SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("the child must execute the checked program rather than search PATH")
                .isEqualTo(program.toRealPath());
    }

    @Test
    void aDirectoryOwnedByAnotherAccountIsRefused() throws Exception {
        executable();
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(directory),
                                        path -> {
                                            final Map<String, Object> attributes = rootOwned(path);
                                            if (path.equals(directory)) {
                                                attributes.put("uid", 1000);
                                            }
                                            return attributes;
                                        }))
                .as("another account must not control the program directory")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("not owned by root");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"rwxrwxr-x", "rwxr-xrwx"})
    void aWritableProgramIsRefused(String permissions) throws Exception {
        final Path program = executable();
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString(permissions));
        assertThatThrownBy(() -> SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("other accounts must not edit the firewall executable")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(program.toString())
                .hasMessageContaining("writable by group or others");
    }

    @Test
    void aNonexecutableProgramIsRefused() throws Exception {
        final Path program = executable();
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("finding a file does not mean the firewall can be loaded")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(program.toString())
                .hasMessageContaining("not a regular executable file");
    }

    @Test
    void aDistributionSymlinkReturnsItsCheckedTarget() throws Exception {
        final Path target = executable();
        Files.move(target, directory.resolve("real-nft"));
        Files.createSymbolicLink(target, Path.of("real-nft"));
        assertThat(SystemProgram.find("nft", List.of(directory), this::rootOwned))
                .as("distribution symlinks must execute the checked target")
                .isEqualTo(directory.resolve("real-nft").toRealPath());
    }

    @Test
    void theHostsRootIsWhereTheNamespaceMapsItOrTheOverflowUidWhereItDoesNot() {
        // As measured in rootless podman's namespace: the caller is uid 0, the host's root
        // unmapped.
        assertThat(
                        SystemProgram.rootAsSeenHere(
                                "         0       1008          1\n         1     624288      65536\n",
                                65534))
                .as("under rootless podman the host's root reads as the overflow uid")
                .isEqualTo(65534);
        assertThat(SystemProgram.rootAsSeenHere("         0          0 4294967295\n", 65534))
                .as("in the host's own namespace root is uid 0")
                .isEqualTo(0);
        assertThat(SystemProgram.rootAsSeenHere("      1000          0          1\n", 65534))
                .as("a namespace that maps the host's root sees it where it maps it")
                .isEqualTo(1000);
    }

    @Test
    void inARootlessNamespaceTheHostsRootPassesAndTheCallersOwnUidDoesNot() throws Exception {
        final Path program = executable();
        final SystemProgram.Attributes asSeenRootless =
                path -> {
                    final Map<String, Object> attributes = rootOwned(path);
                    attributes.put("uid", 65534);
                    return attributes;
                };

        assertThat(SystemProgram.find("nft", List.of(directory), asSeenRootless, 65534))
                .as("the host's root's program, read as the overflow uid, is taken")
                .isEqualTo(program.toRealPath());
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(directory),
                                        path -> {
                                            final Map<String, Object> attributes =
                                                    asSeenRootless.read(path);
                                            if (path.equals(program)) {
                                                attributes.put("uid", 0);
                                            }
                                            return attributes;
                                        },
                                        65534))
                .as("the caller's own file, uid 0 inside the namespace, is not root's")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not owned by root");
    }
}
