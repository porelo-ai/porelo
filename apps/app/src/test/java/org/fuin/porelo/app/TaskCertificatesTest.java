package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the authority a task receives, without a container runtime. */
class TaskCertificatesTest {

    private static final String TASK = "porelo-p-trust";
    private static final String MOUNT = "/porelo/host-ca.pem";
    @TempDir Path dir;
    private final FakeCommandRunner commands =
            new FakeCommandRunner().answering(" create ", "0123456789abcdef");

    private PoreloPaths paths() {
        return new PoreloPaths(
                new XdgPaths(
                        dir.resolve("config"),
                        dir.resolve("data"),
                        dir.resolve("state"),
                        dir.resolve("run"),
                        dir),
                dir.resolve("bin"));
    }

    private Path source() {
        return paths().xdg().config().resolve("task-ca-certificates.pem");
    }

    private Path authority() throws IOException {
        Files.createDirectories(source().getParent());
        try (var in = TaskCertificatesTest.class.getResourceAsStream("/tls/task-authority.pem")) {
            Files.copy(java.util.Objects.requireNonNull(in), source());
        }
        Files.setPosixFilePermissions(source(), PosixFilePermissions.fromString("rw-------"));
        return source();
    }

    private void start() throws IOException {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: p
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                """),
                        "test");
        new TaskRunner(commands, paths()) {
            @Override
            public void startScreenWriter(String container) {}
        }.start(project, TASK, new PrintWriter(new StringWriter()));
    }

    @Test
    void aStartMountsItsOwnCopyAndInstallsItBeforeTheAgentCanRun() throws IOException {
        final Path original = authority();
        final String pem = Files.readString(original);

        start();

        final Path copy = paths().tasks().containerState(TASK).resolve("task-ca-certificates.pem");
        assertThat(copy)
                .as("each task needs its own inode for a private SELinux label")
                .hasContent(pem);
        assertThat(Files.isSameFile(original, copy))
                .as("a hard link would still relabel the account's own authority")
                .isFalse();
        final Command create = commands.only(" create ");
        assertThat(create.arguments())
                .as("the account's authority must never be relabeled")
                .containsSequence("--volume", copy + ":" + MOUNT + ":ro,Z")
                .doesNotContain(original + ":" + MOUNT + ":ro,Z")
                .containsSequence("--env", "NODE_EXTRA_CA_CERTS");
        assertThat(create.environment())
                .as("Node reads the same authority as the system store")
                .containsEntry("NODE_EXTRA_CA_CERTS", MOUNT);
        final Command update = commands.only("command -v update-ca-certificates");
        assertThat(update.arguments())
                .as("trust is installed as root in the recorded container")
                .containsSubsequence("exec", "--user", "root", "0123456789abcdef", "sh", "-ec");
        assertThat(update.arguments())
                .as("the mounted authority is passed separately from shell code")
                .endsWith("porelo-task-ca", MOUNT);
        assertThat(update.arguments().get(update.arguments().indexOf("-ec") + 1))
                .as("both system stores use the mounted authority")
                .contains(
                        "/usr/local/share/ca-certificates/porelo-host.crt",
                        "update-ca-certificates",
                        "/etc/pki/ca-trust/source/anchors/porelo-host.pem",
                        "update-ca-trust");
        assertThat(update.arguments().get(update.arguments().indexOf("-ec") + 1))
                .as("the update commands live in sbin, which a task image's PATH leaves out")
                .startsWith("PATH=/usr/local/sbin:/usr/sbin:/sbin:/usr/local/bin:/usr/bin:/bin\n");
        assertThat(commands.invocations().indexOf(update))
                .as("the store needs a running container")
                .isGreaterThan(commands.invocations().indexOf(commands.only(" start ")));
        assertThat(original).as("the account's source is left intact").hasContent(pem);
        assertThat(Files.getPosixFilePermissions(original))
                .as("the account's source stays private")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void withoutTheTaskFileNoAuthorityIsMountedOrInstalledEvenWithAHostClientsFile()
            throws IOException {
        Files.createDirectories(source().getParent());
        Files.writeString(
                paths().xdg().config().resolve("ca-certificates.pem"), "host clients only");

        start();

        assertThat(commands.only(" create ").arguments())
                .as("a host client's trust must stay on the host")
                .noneMatch(arg -> arg.contains("ca-certificates.pem") || arg.contains(MOUNT))
                .doesNotContain("NODE_EXTRA_CA_CERTS");
        assertThat(commands.only(" create ").environment())
                .as("no hidden Node trust")
                .doesNotContainKey("NODE_EXTRA_CA_CERTS");
        assertThat(commands.lines())
                .as("no trust-store step without an authority")
                .noneMatch(line -> line.contains("update-ca-"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not a certificate",
                "",
                "-----BEGIN CERTIFICATE-----\nbroken\n-----END CERTIFICATE-----"
            })
    void aMalformedAuthorityRefusesTheStartAndNamesTheFile(String content) throws IOException {
        authority();
        Files.writeString(source(), content);

        assertThatThrownBy(this::start)
                .as("a configured authority must never be silently ignored")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(source().toString())
                .hasMessageContaining("certificate");
        assertThat(commands.lines())
                .as("refusal precedes container creation and start")
                .noneMatch(line -> line.contains(" create ") || line.contains(" start "));
    }

    @Test
    void aNewTaskWithoutTheAuthorityCannotInheritAnEarlierStartsCopy() throws IOException {
        final Path state = Files.createDirectories(paths().tasks().containerState(TASK));
        final Path stale = state.resolve("task-ca-certificates.pem");
        try (var in = TaskCertificatesTest.class.getResourceAsStream("/tls/task-authority.pem")) {
            Files.copy(java.util.Objects.requireNonNull(in), stale);
        }

        start();

        assertThat(stale)
                .as("a stale snapshot must not be saved and reapplied on a later resume")
                .doesNotExist();
        assertThat(commands.only(" create ").environment())
                .as("a new task has no configured authority")
                .doesNotContainKey("NODE_EXTRA_CA_CERTS");
        assertThat(commands.lines())
                .as("there is no authority to install")
                .noneMatch(line -> line.contains("update-ca-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-r-----", "rw----r--", "rw--w----", "rw---x---"})
    void anAuthorityAccessibleToOtherAccountsRefusesTheStartAndNamesTheFix(String mode)
            throws IOException {
        authority();
        Files.setPosixFilePermissions(source(), PosixFilePermissions.fromString(mode));

        assertThatThrownBy(this::start)
                .as("no other account may access a configured task authority")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(source().toString())
                .hasMessageContaining("chmod 600");
        assertThat(commands.lines())
                .as("insecure trust never reaches a container")
                .noneMatch(line -> line.contains(" create ") || line.contains(" start "));
    }

    @Test
    void aDirectoryOrSymbolicLinkCannotStandInForTheAuthority() throws IOException {
        Files.createDirectories(source());
        assertThatThrownBy(this::start)
                .as("a directory is configuration failure, not absence")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(source().toString())
                .hasMessageContaining("regular file");
        Files.delete(source());
        Files.createSymbolicLink(source(), dir.resolve("missing.pem"));
        assertThatThrownBy(this::start)
                .as("a dangling link must not silently disable trust")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(source().toString())
                .hasMessageContaining("regular file");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 69})
    void anImageThatCannotUpdateItsStoreStopsBeforeAnAgentRuns(int code) throws IOException {
        authority();
        commands.failing("command -v update-ca-certificates", code, "not installed");

        assertThatThrownBy(this::start)
                .as("a requested trust store must not be silently omitted")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(source().toString())
                .hasMessageContaining("trust store");
        assertThat(commands.lines())
                .as("an incomplete start must not leave its container running")
                .anyMatch(line -> line.contains("stop") && line.contains("0123456789abcdef"));
    }

    @Test
    void aRestartRestoresTheMountedAuthorityReadableByTheAgent() throws IOException {
        authority();
        start();
        final PoreloContext context = new PoreloContext(commands, paths(), arguments -> 0);
        final Path state = paths().tasks().containerState(TASK);
        final Path copy = state.resolve("task-ca-certificates.pem");
        final String pem = Files.readString(copy);
        final TaskState saved = new TaskState(context);
        saved.save(TASK);
        Files.delete(copy);

        saved.restore(TASK);

        assertThat(copy)
                .as("a recorded mount must survive the runtime directory being cleared")
                .hasContent(pem);
        assertThat(Files.getPosixFilePermissions(copy))
                .as("the subordinate agent uid needs to read Node's authority")
                .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
    }

    @Test
    void resumingATaskReappliesItsAuthorityBeforeReturning() throws IOException {
        authority();
        start();
        final FakeCommandRunner resume =
                new FakeCommandRunner().answering("{{.Id}}", "0123456789abcdef");
        final PoreloContext context = new PoreloContext(resume, paths(), arguments -> 0);

        new TaskControl(context).resume(TASK);

        assertThat(resume.lines())
                .as("every start, including one without recorded helpers, reapplies trust")
                .anyMatch(line -> line.contains("command -v update-ca-certificates"));
    }
}
