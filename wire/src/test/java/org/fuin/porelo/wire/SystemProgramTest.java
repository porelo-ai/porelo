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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void theInitialNamespaceTakesTheHostsRootOwnedProgram() throws Exception {
        // Refusing the host's own root would prevent the firewall hook from starting tasks.
        final Path program = executable();
        assertThat(
                        SystemProgram.find(
                                "nft",
                                List.of(directory),
                                this::rootOwned,
                                SystemProgram.rootAsSeenHere(" 0 0 4294967295\n", 65534)))
                .as("the full identity map establishes the host's own root")
                .isEqualTo(program.toRealPath());
    }

    private static final String ROOTLESS_MAP = " 0 1008 1\n 1 624288 65536\n";

    private SystemProgram.Attributes distribution(Path program, Path target, int owner) {
        return new SystemProgram.Attributes() {
            @Override
            public Map<String, Object> read(Path path) {
                return Map.of("uid", owner, "mode", path.equals(target) ? 0100755 : 040755);
            }

            @Override
            public void exists(Path path) throws IOException {
                if (!path.equals(program))
                    throw new java.nio.file.NoSuchFileException(path.toString());
            }

            @Override
            public Path realPath(Path path) {
                return target;
            }

            @Override
            public boolean executable(Path path) {
                return path.equals(target);
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"/usr/sbin", "/usr/bin", "/sbin", "/bin"})
    void aMultilineRootlessMapAllowsFixedDistributionPrograms(String directory) throws Exception {
        // The real two-line podman map must still let the hooks load the task's firewall.
        final Path program = Path.of(directory, "nft");
        final Path target = Path.of("/usr/bin/nft");
        assertThat(
                        SystemProgram.find(
                                "nft",
                                List.of(program.getParent()),
                                distribution(program, target, 65534),
                                SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 65534)))
                .as("only fixed distribution paths get the unmapped-owner exception")
                .isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {" 0 0 1\n", " 1000 0 1\n", " 0 1008 1\n 1 624288 65536\n 70000 0 1\n"})
    void aMapOfRootOutsideTheInitialNamespaceIsRefused(String map) {
        // A nested namespace must not promote its unprivileged uid zero into the host's root.
        assertThatThrownBy(() -> SystemProgram.rootAsSeenHere(map, 65534))
                .as("a short identity map or host root at another inside uid is unsafe")
                .isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "bad map",
                "0 1008 1\nmalformed\n",
                "0 1008 0\n",
                "-1 1008 1\n",
                "0 4294967295 1\n",
                "4294967294 1008 2\n",
                "0 1008 1\n0 624288 65536\n",
                "0 1008 1\n1 1008 1\n"
            })
    void anUncertainMapIsRefused(String map) {
        // An incomplete or invalid map must not grant the unmapped-owner exception.
        assertThatThrownBy(() -> SystemProgram.rootAsSeenHere(map, 65534))
                .as("every range must be understood before ownership can be trusted")
                .isInstanceOf(IOException.class);
    }

    @Test
    void anOverflowUidOfZeroCannotIdentifyRootInARootlessMap() {
        // A configurable overflow uid of zero must not turn every unmapped owner into root.
        assertThatThrownBy(() -> SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 0))
                .as("ambiguous overflow ownership cannot take the initial-root path")
                .isInstanceOf(IOException.class);
    }

    @Test
    void anotherUnmappedAccountsProgramOutsideDistributionPathsIsRefused() throws Exception {
        // Another unmapped account must not supply an executable under its own directory.
        final Path program = executable();
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(directory),
                                        distribution(program, program, 65534),
                                        SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 65534)))
                .as("the overflow uid alone cannot establish root ownership")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/usr/bin/../bin", "/usr/bin/tools", "/usr/binary"})
    void anOverflowOwnerCannotUseAnAmbiguousOrNonDistributionPath(String directory)
            throws Exception {
        // Normalization or prefix matching must not extend the distribution exception.
        final Path program = Path.of(directory, "nft");
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(program.getParent()),
                                        distribution(program, program, 65534),
                                        SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 65534)))
                .as("only normalized immediate children of the fixed directories qualify")
                .isInstanceOf(IOException.class);
    }

    @Test
    void aDistributionLinkCannotEscapeToAnotherUnmappedAccountsProgram() throws Exception {
        // A distribution symlink must not carry the overflow exception into another account's
        // files.
        final Path program = Path.of("/usr/bin/nft");
        final Path target = Path.of("/home/other/nft");
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(program.getParent()),
                                        distribution(program, target, 65534),
                                        SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 65534)))
                .as("the resolved executable must qualify independently")
                .isInstanceOf(IOException.class);
    }

    @Test
    void inARootlessNamespaceTheCallersOwnUidDoesNotPass() throws Exception {
        // The caller's own uid zero must not supply a distribution executable to the hook.
        final Path program = Path.of("/usr/bin/nft");
        assertThatThrownBy(
                        () ->
                                SystemProgram.find(
                                        "nft",
                                        List.of(program.getParent()),
                                        distribution(program, program, 0),
                                        SystemProgram.rootAsSeenHere(ROOTLESS_MAP, 65534)))
                .as("the caller's own file, uid 0 inside the namespace, is not root's")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not owned by root");
    }

    @Test
    void aProgramTakenByItsPathIsRefusedWhenAnotherAccountOwnsIt() throws Exception {
        // The test's own account owns the file, as anyone but root would on a host.
        final Path program = executable();

        assertThatThrownBy(() -> SystemProgram.at(program))
                .as("a program another account may replace is not run by its path either")
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void anAbsentProgramHasANamedOutcomeInsteadOfADiagnosticToParse() {
        assertThatThrownBy(() -> SystemProgram.find("absent", List.of(directory), this::rootOwned))
                .as("optional programs may be absent, but an unsafe installed binary must refuse")
                .isInstanceOf(SystemProgram.Missing.class)
                .hasMessage("Program absent not found at [" + directory.resolve("absent") + "]");
    }
}
