package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SocketContext}.
 *
 * <p>The behavior that matters is only observable on a machine running SELinux, so the checks that
 * need one say so rather than being asserted everywhere and passing for the wrong reason.
 */
class SocketContextTest {

    @TempDir Path dir;

    static boolean selinux() {
        return Files.isRegularFile(Path.of("/sys/fs/selinux/enforce"));
    }

    @Test
    void namesTheTypeThePolicyDefines() {
        assertThat(SocketContext.TYPE).isEqualTo("porelo_socket_t");
    }

    @Test
    void takesAnEmptyMountPointForNoSelinux() {
        // WSL2's kernel has SELinux built in and leaves /sys/fs/selinux as an empty directory
        // without running it: doctor called the policy missing on a machine that needs none.
        assertThat(SocketContext.selinuxPresent(dir)).isFalse();
    }

    @Test
    void takesAMountedSelinuxfsForSelinux() throws Exception {
        // Mounted in enforcing and permissive mode alike, so both need the policy.
        Files.writeString(dir.resolve("enforce"), "0");
        assertThat(SocketContext.selinuxPresent(dir)).isTrue();
    }

    @Test
    void doesNothingWhereThereIsNoSelinux() {

        // Binding must never fail because a machine has no policy, or no task would run there.
        try (SocketContext context = SocketContext.applied()) {
            assertThat(context.isApplied()).isEqualTo(selinux() && SocketContext.available());
        }
    }

    @Test
    void reportsNoPolicyWhereThereIsNoSelinux() {
        if (!selinux()) {
            assertThat(SocketContext.available()).isFalse();
            assertThat(SocketContext.selinuxPresent()).isFalse();
        }
    }

    @Test
    @EnabledIf("selinux")
    void keepsTheProcessSelinuxUserAndReplacesOnlyTheType() throws Exception {

        final String context = SocketContext.socketContext();

        assertThat(context).endsWith(":object_r:" + SocketContext.TYPE + ":s0");
        assertThat(context.split(":")[0])
                .isEqualTo(
                        Files.readString(Path.of("/proc/thread-self/attr/current"))
                                .trim()
                                .replace("\0", "")
                                .split(":")[0]);
    }

    @Test
    @EnabledIf("selinux")
    void leavesNothingSetBehind() throws Exception {

        // An empty write is not a write at all, so a wrong clear would label every later socket.
        try (SocketContext ignored = SocketContext.applied()) {
            assertThat(ignored).isNotNull();
        }
        assertThat(
                        Files.readString(Path.of("/proc/thread-self/attr/sockcreate"))
                                .trim()
                                .replace("\0", ""))
                .isEmpty();
    }

    /**
     * The answer the kernel gives when the permission asked about is allowed: connectto is bit 24.
     */
    private static final String ALLOWED = "800000 ffffffff 0 ffffffff 5 0";

    private static final String DENIED = "0 ffffffff 0 ffffffff 5 0";

    private static final String TARGET = "unconfined_u:object_r:porelo_socket_t:s0";

    /** A selinuxfs with the class and permission numbers Fedora 44 gives unix_stream_socket. */
    private Path selinuxfs() throws IOException {
        final Path perms = Files.createDirectories(dir.resolve("class/unix_stream_socket/perms"));
        Files.writeString(dir.resolve("class/unix_stream_socket/index"), "24\n");
        Files.writeString(perms.resolve("connectto"), "24\n");
        return dir;
    }

    @Test
    void findsNothingRefusedWhereTheLoadedModuleGrantsEveryDomain() throws IOException {
        final List<String> asked = new ArrayList<>();

        final List<String> refused =
                SocketContext.refused(
                        selinuxfs(),
                        TARGET,
                        query -> {
                            asked.add(query);
                            return ALLOWED;
                        });

        assertThat(refused).as("refused with module 1.1 loaded").isEmpty();
        assertThat(asked)
                .as("the questions put to the kernel")
                .contains(
                        "system_u:system_r:sshd_session_t:s0 " + TARGET + " 24",
                        "system_u:system_r:container_t:s0 " + TARGET + " 24");
    }

    @Test
    void namesSshdWhereTheLoadedModuleIsOlderThanThePackages() throws IOException {

        // Module 1.0 let no sshd domain connect, and under selinux-policy 44 every socket forward
        // with ssh -L was reset; doctor still said "installed" (measured on Fedora 44, 2026-10-18).
        final List<String> refused =
                SocketContext.refused(
                        selinuxfs(), TARGET, query -> query.contains(":sshd") ? DENIED : ALLOWED);

        assertThat(refused)
                .as("what an older module refuses")
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("sshd").contains("ssh -L"));
    }

    @Test
    void passesOverADomainThePolicyDoesNotHave() throws IOException {

        // sshd_session_t exists only where OpenSSH splits its session process; the module grants it
        // in an optional block, and the kernel refuses a question naming a type it does not know.
        final List<String> refused =
                SocketContext.refused(
                        selinuxfs(),
                        TARGET,
                        query -> {
                            if (query.contains("sshd_session_t")) {
                                throw new IOException("Invalid argument");
                            }
                            return ALLOWED;
                        });

        assertThat(refused).as("refused on a policy without sshd_session_t").isEmpty();
    }

    @Test
    void saysSoWhenTheKernelAnswersNoQuestionAtAll() throws IOException {
        final Path selinuxfs = selinuxfs();

        assertThatThrownBy(
                        () ->
                                SocketContext.refused(
                                        selinuxfs,
                                        TARGET,
                                        query -> {
                                            throw new IOException("Permission denied");
                                        }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unconfined_t");
    }
}
