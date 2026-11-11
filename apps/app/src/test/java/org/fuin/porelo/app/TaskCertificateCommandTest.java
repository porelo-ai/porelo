package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@code porelo task certificate}: a task key traced to its certificate. */
class TaskCertificateCommandTest {

    @TempDir Path dir;
    private Path helper;

    @org.junit.jupiter.api.BeforeEach
    void installedHostHelper() throws Exception {
        helper = Files.writeString(dir.resolve("porelo-host"), "fixture");
    }

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @Test
    void aKeptCertificateIsAnsweredByItsKeysFingerprint() throws Exception {
        final SshCertificate certificate = certificate();
        new TaskCertificateRecords(context()).keep("fix", "alice", certificate);

        assertThat(porelo("task", "certificate", certificate.fingerprint(), "--json")).isZero();
        @SuppressWarnings("unchecked")
        final Map<String, Object> answer = (Map<String, Object>) Json.parse(out.toString().strip());
        assertThat(answer)
                .containsEntry("task", "fix")
                .containsEntry("fingerprint", certificate.fingerprint());
        assertThat(runner.invocations()).as("the host is not asked").isEmpty();
    }

    @Test
    void oneThisMachineDidNotKeepIsAskedOfTheHostAndItsAnswerChecked() throws Exception {
        final SshCertificate certificate = certificate();
        final String fingerprint = certificate.fingerprint();
        runner.answering("porelo-host task show", hostRecord(fingerprint, certificate));

        assertThat(porelo("task", "certificate", fingerprint, "--json")).isZero();
        assertThat(out.toString()).contains("\"task\":\"fix\"");
        assertThat(runner.invocations().getLast().arguments())
                .as("the host's records, read through sudo")
                .startsWith(
                        HostForgeLending.SUDO.toString(),
                        "-n",
                        helper.toString(),
                        "task",
                        "show",
                        fingerprint);

        final String other = "SHA256:" + "C".repeat(43);
        out.getBuffer().setLength(0);
        assertThat(porelo("task", "certificate", other, "--json"))
                .as("an answer about another key is not believed")
                .isEqualTo(70);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aHostRecordWhoseCertificateIsOfAnotherKeyOrMissingIsNotBelieved() throws Exception {
        // The outer fingerprint is only what the answer says about itself; the certificate is what
        // a receiver would trust.
        final SshCertificate other = certificate();
        final String fingerprint = "SHA256:" + "B".repeat(43);
        runner.answering("porelo-host task show", hostRecord(fingerprint, other));

        assertThat(porelo("task", "certificate", fingerprint, "--json"))
                .as("a record holding another key's certificate")
                .isEqualTo(70);
        assertThat(out.toString()).isEmpty();

        runner.answering(
                "porelo-host task show",
                "{\"task\":\"fix\",\"fingerprint\":\"" + fingerprint + "\"}\n");
        assertThat(porelo("task", "certificate", fingerprint, "--json"))
                .as("a record holding no certificate")
                .isEqualTo(70);
        assertThat(out.toString()).isEmpty();
    }

    private static String hostRecord(final String fingerprint, final SshCertificate certificate) {
        return Json.write(
                        Map.of(
                                "task",
                                "fix",
                                "fingerprint",
                                fingerprint,
                                "certificate",
                                certificate.line()))
                + "\n";
    }

    @Test
    void noneKeptAnywhereOrNoFingerprintIsSaid() {
        runner.failing("porelo-host", 65, "no such task key\n");

        assertThat(porelo("task", "certificate", "SHA256:" + "D".repeat(43))).isEqualTo(66);
        assertThat(err.toString()).contains("no certificate");
        assertThat(porelo("task", "certificate", "fix")).isEqualTo(64);
    }

    private SshCertificate certificate() throws Exception {
        final Path ca = dir.resolve("ca");
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", ca.toString());
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", dir.resolve("task").toString());
        run(
                "ssh-keygen",
                "-q",
                "-s",
                ca.toString(),
                "-I",
                "porelo task fix by alice",
                "-n",
                "task:demo:fix",
                "-V",
                "+1d",
                dir.resolve("task.pub").toString());
        final SshCertificate certificate =
                SshCertificate.read(Files.readString(dir.resolve("task-cert.pub")).strip());
        assertThat(certificate.validAt(Instant.now())).isTrue();
        return certificate;
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private int porelo(String... args) {
        final PoreloContext machine = context();
        final CommandLine cli = PoreloCli.commandLine(machine);
        final TaskCertificateCommand command =
                cli.getSubcommands().get("task").getSubcommands().get("certificate").getCommand();
        command.setHost(new HostPerson(machine, helper, dir.resolve("host-configuration")));
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    private static void run(String... command) throws Exception {
        assertThat(
                        new ProcessBuilder(List.of(command))
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
    }
}
