package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.vault.SshCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RemoteStart}: the host asks, the person's own agent certifies, and only the
 * certificate goes back over the start's connection. A request shown instead of answered leaves the
 * host waiting for ever, so each test has a limit.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RemoteStartTest {

    @TempDir Path dir;

    private Process agent;

    private Path socket;

    private String person;

    @BeforeEach
    void agentHoldingThePersonsKey() throws Exception {
        person = keygen("person");
        keygen("task");
        socket = dir.resolve("agent.sock");
        agent =
                new ProcessBuilder("ssh-agent", "-D", "-a", socket.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        for (int i = 0; i < 100 && !Files.exists(socket); i++) {
            Thread.sleep(20);
        }
        final ProcessBuilder add =
                new ProcessBuilder("ssh-add", "-q", dir.resolve("person").toString());
        add.environment().put("SSH_AUTH_SOCK", socket.toString());
        assertThat(
                        add.redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
    }

    @AfterEach
    void stopAgent() throws Exception {
        agent.destroy();
        agent.waitFor(5, TimeUnit.SECONDS);
    }

    @Test
    void theHostsRequestIsCertifiedByTheAgentAndTheRestOfItsOutputShown() throws Exception {
        final Path host = host(principalLine("task:uc:fix", Instant.now().plusSeconds(3600)));
        final StringWriter out = new StringWriter();

        final int code =
                new RemoteStart(List.of(host.toString()), socket)
                        .run(
                                List.of("task", "run", "fix", "--project", "it's"),
                                "uc",
                                "fix",
                                new PrintWriter(out),
                                new PrintWriter(new StringWriter()));

        assertThat(code).isZero();
        assertThat(out.toString()).contains("started fix").doesNotContain("certify");
        final SshCertificate certificate =
                SshCertificate.read(Files.readString(dir.resolve("answered")).strip());
        assertThat(certificate.signer()).isEqualTo(person);
        assertThat(certificate.principals()).containsExactly("task:uc:fix");
        assertThat(Files.readString(dir.resolve("command")))
                .as("each argument quoted for the host's shell")
                .isEqualTo("porelo 'task' 'run' 'fix' '--project' 'it'\\''s'\n");
    }

    @Test
    void aRequestForAnotherTaskOrLongerThanAWeekIsNotCertified() throws Exception {
        // A host asking for another project's task, or naming no certifier so that the agent's
        // first key signs, would get a certificate the person never meant to give.
        final Instant hour = Instant.now().plusSeconds(3600);
        for (final String line :
                List.of(
                        principalLine("task:uc:other", hour),
                        principalLine("task:prod:fix", hour),
                        principalLine("task:uc:fix", Instant.now().plusSeconds(30L * 24 * 3600)),
                        principalLine("task:uc:fix", hour).replace("[\"" + person + "\"]", "[]"),
                        principalLine("task:uc:fix", hour)
                                .replace(
                                        "[\"" + person + "\"]",
                                        "[\"" + person + "\",\"ssh-rsa AAAA\"]"))) {
            Files.deleteIfExists(dir.resolve("answered"));
            final StringWriter err = new StringWriter();
            final int code =
                    new RemoteStart(List.of(host(line).toString()), socket)
                            .run(
                                    List.of("task", "run", "fix"),
                                    "uc",
                                    "fix",
                                    new PrintWriter(new StringWriter()),
                                    new PrintWriter(err));

            assertThat(code).as(line).isEqualTo(65);
            assertThat(Files.readString(dir.resolve("answered")).strip())
                    .as("nothing certified")
                    .isEmpty();
            assertThat(err.toString()).contains("not certified");
        }
    }

    @Test
    void theHostsOutputReachesTheTerminalWithoutItsControlsAndCutToALine() throws Exception {
        // What the host prints is the host's: an escape sequence printed raw writes the person's
        // clipboard, and a line that never ends would take this machine's memory.
        final Path script = dir.resolve("noisy.sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "printf 'a\\033]52;c;ZWNobyBwd25lZA==\\007b\\n'\n"
                        + "printf 'c\\033[2Jd\\n' >&2\n"
                        + "head -c 100000 /dev/zero | tr '\\0' x\n"
                        + "printf '\\nend\\n'\n");
        script.toFile().setExecutable(true);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();

        final int code =
                new RemoteStart(List.of(script.toString()), socket)
                        .run(
                                List.of("task", "run", "fix"),
                                "uc",
                                "fix",
                                new PrintWriter(out),
                                new PrintWriter(err));

        assertThat(code).isZero();
        assertThat(out.toString() + err.toString())
                .doesNotContain("\u001b")
                .doesNotContain("\u0007");
        assertThat(out.toString().lines().toList())
                .contains("a?]52;c;ZWNobyBwd25lZA==?b", "end")
                .anyMatch(line -> line.length() == 16 * 1024 && line.matches("x+"));
        assertThat(err.toString()).contains("c?[2Jd");
    }

    @Test
    void aProjectThatIsNoProjectsNameCertifiesNothing() throws Exception {
        // ssh-keygen takes a comma in a principal as a second principal: "a,root" asked for and
        // matched as given would certify the task's key as root.
        final Path host = host(principalLine("task:a,root:fix", Instant.now().plusSeconds(3600)));
        final StringWriter err = new StringWriter();

        final int code =
                new RemoteStart(List.of(host.toString()), socket)
                        .run(
                                List.of("task", "run", "fix"),
                                "a,root",
                                "fix",
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(err));

        assertThat(code).isEqualTo(65);
        assertThat(Files.readString(dir.resolve("answered")).strip()).isEmpty();
    }

    @Test
    void anEndAlreadyPastIsRefusedBeforeTheAgentIsAsked() throws Exception {
        // An end before year one would be written for ssh-keygen as a year far ahead.
        for (final Instant end :
                List.of(Instant.parse("-9998-12-31T23:59:59Z"), Instant.now().minusSeconds(60))) {
            Files.deleteIfExists(dir.resolve("answered"));
            final StringWriter err = new StringWriter();

            final int code =
                    new RemoteStart(
                                    List.of(host(principalLine("task:uc:fix", end)).toString()),
                                    socket)
                            .run(
                                    List.of("task", "run", "fix"),
                                    "uc",
                                    "fix",
                                    new PrintWriter(new StringWriter()),
                                    new PrintWriter(err));

            assertThat(code).as(end.toString()).isEqualTo(65);
            assertThat(err.toString()).as(end.toString()).contains("an end already past");
        }
    }

    @Test
    void aTaskThatIsNoTasksNameCertifiesNothing() throws Exception {
        // As for the project: "fix,root" asked for and matched as given would certify root.
        final Path host = host(principalLine("task:uc:fix,root", Instant.now().plusSeconds(3600)));

        final int code =
                new RemoteStart(List.of(host.toString()), socket)
                        .run(
                                List.of("task", "run", "fix"),
                                "uc",
                                "fix,root",
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));

        assertThat(code).isEqualTo(65);
        assertThat(Files.readString(dir.resolve("answered")).strip()).isEmpty();
    }

    @Test
    void aTaskWithoutAProjectIsCertifiedByTheAgentsFirstKeyAsAtAStartHere() throws Exception {
        final Path host =
                host(
                        principalLine("task:default:fix", Instant.now().plusSeconds(3600))
                                .replace("[\"" + person + "\"]", "[]"));

        final int code =
                new RemoteStart(List.of(host.toString()), socket)
                        .run(
                                List.of("task", "run", "fix"),
                                "default",
                                "fix",
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));

        assertThat(code).isZero();
        assertThat(SshCertificate.read(Files.readString(dir.resolve("answered")).strip()).signer())
                .isEqualTo(person);
    }

    @Test
    void aRequestThatDoesNotReadIsRefusedAndAnsweredNotShown() throws Exception {
        // Shown instead of answered, the host would wait for its answer for ever; a request
        // longer than a line is cut and reads no more than a broken one.
        for (final String line :
                List.of(
                        "{\"certify\":bad",
                        principalLine("task:uc:fix", Instant.now().plusSeconds(3600))
                                .replace("porelo task fix by alice", "x".repeat(20_000)))) {
            Files.deleteIfExists(dir.resolve("answered"));
            final StringWriter out = new StringWriter();
            final StringWriter err = new StringWriter();

            final int code =
                    new RemoteStart(List.of(host(line).toString()), socket)
                            .run(
                                    List.of("task", "run", "fix"),
                                    "uc",
                                    "fix",
                                    new PrintWriter(out),
                                    new PrintWriter(err));

            assertThat(code).isEqualTo(65);
            assertThat(out.toString()).doesNotContain("certify");
            assertThat(err.toString()).contains("the host's request does not read");
            assertThat(Files.readString(dir.resolve("answered")).strip()).isEmpty();
        }
    }

    @Test
    void aRefusalStaysOneWhenTheHostHasGoneBeforeItsAnswer() throws Exception {
        final Path script = dir.resolve("gone.sh");
        Files.writeString(
                script,
                "#!/bin/sh\nprintf '%s\\n' '"
                        + principalLine("task:uc:other", Instant.now().plusSeconds(3600))
                        + "'\nexec 0<&-\nexit 0\n");
        script.toFile().setExecutable(true);

        final int code =
                new RemoteStart(List.of(script.toString()), socket)
                        .run(
                                List.of("task", "run", "fix"),
                                "uc",
                                "fix",
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));

        assertThat(code).isEqualTo(65);
    }

    @Test
    void aLineIsNeverCutBetweenTheHalvesOfACharacter() throws Exception {
        final Path script = dir.resolve("wide.sh");
        Files.writeString(
                script,
                "#!/bin/sh\nhead -c 16383 /dev/zero | tr '\\0' x\nprintf '\\360\\237\\230\\200tail\\n'\n");
        script.toFile().setExecutable(true);
        final StringWriter out = new StringWriter();

        new RemoteStart(List.of(script.toString()), socket)
                .run(
                        List.of("task", "run", "fix"),
                        "uc",
                        "fix",
                        new PrintWriter(out),
                        new PrintWriter(new StringWriter()));

        assertThat(out.toString().lines().findFirst().orElseThrow()).hasSize(16383).matches("x+");
    }

    /** A stand-in for ssh: asks once, keeps the answer, then says it started. */
    private Path host(String request) throws Exception {
        final Path script = dir.resolve("host.sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "printf '%s\\n' \"$1\" > '"
                        + dir.resolve("command")
                        + "'\n"
                        + "echo 'building image'\n"
                        + "printf '%s\\n' '"
                        + request
                        + "'\n"
                        + "IFS= read -r answer\n"
                        + "printf '%s\\n' \"$answer\" > '"
                        + dir.resolve("answered")
                        + "'\n"
                        + "[ -n \"$answer\" ] || exit 65\n"
                        + "echo 'started fix'\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private String principalLine(String principal, Instant before) throws Exception {
        final String task =
                Files.readString(dir.resolve("task.pub")).strip().split(" ", 3)[0]
                        + " "
                        + Files.readString(dir.resolve("task.pub")).strip().split(" ", 3)[1];
        return "{\"certify\":{\"key\":\""
                + task
                + "\",\"principal\":\""
                + principal
                + "\",\"key_id\":\"porelo task fix by alice\",\"valid_before\":\""
                + before
                + "\",\"certifiers\":[\""
                + person
                + "\"]}}";
    }

    private String keygen(String name) throws Exception {
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-N",
                                        "",
                                        "-C",
                                        name,
                                        "-f",
                                        dir.resolve(name).toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        final String[] fields = Files.readString(dir.resolve(name + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }
}
