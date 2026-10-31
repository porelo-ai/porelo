package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code porelo task check-certificate}, with certificates {@code ssh-keygen -s} made and
 * a real {@code ssh-keygen} revocation list. Harm guarded: a host that records a certificate this
 * accepts hands the task a forge token, so one signed by anybody else, revoked, or for another task
 * or person would push in that person's name.
 */
class TaskCheckCertificateCommandTest {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneOffset.UTC);

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private String person;

    private Path keys;

    @BeforeEach
    void makeKeys() throws Exception {
        person = keygen("person");
        keygen("task");
        keygen("stranger");
        keys = Files.writeString(dir.resolve("keys"), person + " alice@example.org\n");
    }

    @Test
    void aCertificateAsTheStartIssuesItIsAnsweredWithTheContractsFields() throws Exception {
        final String line = issued("task:demo:fix", "porelo task fix by alice");
        final Path krl = revocationList("stranger");

        assertThat(check(line + "\n", "--krl", krl.toString(), "--json"))
                .as(err.toString())
                .isZero();

        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(new ArrayList<Object>(answer.keySet()))
                .as("the host reads these names")
                .containsExactly(
                        "task",
                        "project",
                        "principal",
                        "key_id",
                        "fingerprint",
                        "signed_by",
                        "valid_after",
                        "valid_before");
        assertThat(answer.get("task")).isEqualTo("fix");
        assertThat(answer.get("project")).isEqualTo("demo");
        assertThat(answer.get("principal")).isEqualTo("task:demo:fix");
        assertThat(answer.get("key_id")).isEqualTo("porelo task fix by alice");
        assertThat(answer.get("fingerprint")).isEqualTo(PersonCard.fingerprint(keygen("task")));
        assertThat(answer.get("signed_by")).isEqualTo(PersonCard.fingerprint(person));
        final Instant after = Instant.parse((String) answer.get("valid_after"));
        final Instant before = Instant.parse((String) answer.get("valid_before"));
        assertThat((String) answer.get("valid_after")).as("UTC, in seconds").endsWith("Z");
        assertThat(after).isBefore(Instant.now());
        assertThat(before).isAfter(Instant.now().plusSeconds(7 * 86_400 - 300));
        assertThat(dir.resolve("home")).as("no home is made or written").doesNotExist();
    }

    @Test
    void aTaskWithNoProjectIsAccepted() throws Exception {
        assertThat(check(issued("task:default:fix", "porelo task fix by alice")))
                .as(err.toString())
                .isZero();
        assertThat(out.toString()).contains("task:default:fix");
    }

    @Test
    void aCertifierClockAFewMinutesAheadIsAcceptedAsTheStartAcceptsIt() throws Exception {
        assertThat(check(sign("person", "task:demo:fix", "porelo task fix by alice", "+3m:+7d")))
                .as("valid from three minutes ahead: " + err)
                .isZero();
        assertThat(check(sign("person", "task:demo:fix", "porelo task fix by alice", "+6m:+7d")))
                .as("valid from six minutes ahead")
                .isEqualTo(65);
        assertThat(err.toString()).contains("not valid now");
    }

    @Test
    void aCertificateSignedByNoKeyInForceIsRefused() throws Exception {
        assertThat(check(sign("stranger", "task:demo:fix", "porelo task fix by alice", "-1m:+7d")))
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("signed by " + PersonCard.fingerprint(keygen("stranger")))
                .contains("not a key of alice in force");
        assertThat(out.toString()).isEmpty();

        Files.writeString(keys, "");
        assertThat(check(issued("task:demo:fix", "porelo task fix by alice")))
                .as("an empty file refuses every certificate")
                .isEqualTo(65);
    }

    @Test
    void aRevokedSigningKeyOrTaskKeyIsRefused() throws Exception {
        final String line = issued("task:demo:fix", "porelo task fix by alice");

        assertThat(check(line, "--krl", revocationList("person").toString())).isEqualTo(65);
        assertThat(err.toString()).contains(PersonCard.fingerprint(person)).contains("revoked");

        assertThat(check(line, "--krl", revocationList("task").toString())).isEqualTo(65);
        assertThat(err.toString())
                .contains(PersonCard.fingerprint(keygen("task")))
                .contains("revoked");
    }

    @Test
    void aCertificateOfAnotherTaskLoginOrMorePrincipalsIsRefused() throws Exception {
        assertThat(check(issued("task:demo:other", "porelo task fix by alice")))
                .as("another task's principal")
                .isEqualTo(65);
        assertThat(check(issued("task:Demo_X:fix", "porelo task fix by alice")))
                .as("no project's name")
                .isEqualTo(65);
        assertThat(check(issued("task:demo:fix", "porelo task other by alice")))
                .as("another task's key id")
                .isEqualTo(65);
        assertThat(check(issued("task:demo:fix", "porelo task fix by bob")))
                .as("another login's key id")
                .isEqualTo(65);
        assertThat(err.toString()).contains("porelo task fix by alice");
        assertThat(check(issued("task:demo:fix,task:demo:other", "porelo task fix by alice")))
                .as("two principals")
                .isEqualTo(65);
        assertThat(err.toString()).contains("exactly one principal");
        assertThat(check(issued("task:fix", "porelo task fix by alice")))
                .as("a principal without its project, whose one colon both ends share")
                .isEqualTo(65);
        assertThat(err.toString()).contains("exactly one principal");
        final String task = "t".repeat(56);
        assertThat(
                        check(
                                issued("task:demo:" + task, "porelo task " + task + " by alice"),
                                "--task",
                                task))
                .as("a project and task whose container name the start refuses as too long")
                .isEqualTo(65);
        assertThat(
                        check(
                                issued("task:default:" + task, "porelo task " + task + " by alice"),
                                "--task",
                                task))
                .as("the same task with no project, as the start allows it")
                .isZero();
    }

    @Test
    void anExpiredCertificateOrOneValidLongerThanAWeekIsRefused() throws Exception {
        assertThat(check(sign("person", "task:demo:fix", "porelo task fix by alice", "-2d:-1d")))
                .isEqualTo(65);
        assertThat(err.toString()).contains("not valid now");
        assertThat(check(sign("person", "task:demo:fix", "porelo task fix by alice", "-1m:+30d")))
                .isEqualTo(65);
        assertThat(err.toString()).contains("longer than 7 days");
    }

    @Test
    void extensionsOrCriticalOptionsTheStartDoesNotIssueAreRefused() throws Exception {
        assertThat(
                        check(
                                sign(
                                        "person",
                                        "task:demo:fix",
                                        "porelo task fix by alice",
                                        "-1m:+7d",
                                        "-O",
                                        "extension:porelo@example.org")))
                .as("an extra extension")
                .isEqualTo(65);
        assertThat(err.toString()).contains("extensions");
        assertThat(
                        check(
                                sign(
                                        "person",
                                        "task:demo:fix",
                                        "porelo task fix by alice",
                                        "-1m:+7d",
                                        "-O",
                                        "no-pty")))
                .as("one fewer")
                .isEqualTo(65);
        assertThat(
                        check(
                                sign(
                                        "person",
                                        "task:demo:fix",
                                        "porelo task fix by alice",
                                        "-1m:+7d",
                                        "-O",
                                        "force-command=/bin/true")))
                .as("a critical option")
                .isEqualTo(65);
    }

    @Test
    void standardInputIsOneBoundedLineBeforeAnythingParsesIt() throws Exception {
        final String line = issued("task:demo:fix", "porelo task fix by alice");

        assertThat(check(line + "\n" + line + "\n")).as("a second line").isEqualTo(65);
        assertThat(err.toString()).contains("more than");
        assertThat(check(line + "\n\n")).as("a second, empty line").isEqualTo(65);
        assertThat(check("")).as("no line").isEqualTo(65);
        assertThat(check("\n")).as("an empty line").isEqualTo(65);
        assertThat(check(line + "\r\n")).as("a carriage return").isEqualTo(65);
        assertThat(err.toString()).contains("printable ASCII");
        assertThat(check(line + " \u0000\n")).as("a NUL byte").isEqualTo(65);
        assertThat(check(line + " k\u00e9y\n")).as("a byte beyond ASCII").isEqualTo(65);

        final long[] taken = {0};
        final InputStream endless =
                new InputStream() {
                    @Override
                    public int read() {
                        taken[0]++;
                        return 'A';
                    }
                };
        assertThat(check(endless)).as("a line longer than 16 KiB").isEqualTo(65);
        assertThat(taken[0])
                .as("no more is read than a line and its end")
                .isLessThanOrEqualTo(16 * 1024 + 2);
    }

    @Test
    void aMisspelledTaskOrLoginIsWrongUseAndAMissingFileIsNamed() throws Exception {
        final String line = issued("task:demo:fix", "porelo task fix by alice");

        assertThat(check(line, "--task", "Fix")).isEqualTo(64);
        assertThat(execute(line, "--task", "fix", "--login", "al ice", "--keys", keys.toString()))
                .isEqualTo(64);
        assertThat(execute(line, "--task", "fix", "--login", "porelo", "--keys", keys.toString()))
                .as("a name Porelo keeps for itself")
                .isEqualTo(64);
        assertThat(
                        execute(
                                line,
                                "--task",
                                "fix",
                                "--login",
                                "porelo-agent",
                                "--keys",
                                keys.toString()))
                .as(err::toString)
                .isEqualTo(64);
        assertThat(execute(line, "--task", "fix", "--login", "alice"))
                .as("no --keys")
                .isEqualTo(64);
        assertThat(execute(line, "--task", "fix", "--login", "alice", "--keys", "/nonexistent"))
                .isEqualTo(66);
        assertThat(check(line, "--krl", dir.resolve("none.krl").toString())).isEqualTo(66);
        Files.writeString(dir.resolve("junk.krl"), "junk\n");
        assertThat(check(line, "--krl", dir.resolve("junk.krl").toString()))
                .as("a list ssh-keygen cannot read")
                .isEqualTo(66);
    }

    /** As {@code porelo task start} issues it: from a minute ago, for seven days. */
    private String issued(final String principal, final String keyId) throws Exception {
        final Instant now = Instant.now();
        return sign(
                "person",
                principal,
                keyId,
                WHEN.format(now.minusSeconds(60))
                        + "Z:"
                        + WHEN.format(now.plus(TaskCertification.VALIDITY))
                        + "Z");
    }

    private int check(final String input, final String... more) {
        return check(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), more);
    }

    private int check(final InputStream input, final String... more) {
        final List<String> args = new ArrayList<>(List.of("--login", "alice"));
        args.addAll(List.of("--keys", keys.toString()));
        if (!List.of(more).contains("--task")) {
            args.addAll(List.of("--task", "fix"));
        }
        args.addAll(List.of(more));
        return execute(input, args.toArray(String[]::new));
    }

    private int execute(final String input, final String... args) {
        return execute(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), args);
    }

    private int execute(final InputStream input, final String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine cmd = PoreloCli.commandLine(context(input));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new ArrayList<>(List.of("task", "check-certificate"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(String[]::new));
    }

    private PoreloContext context(final InputStream input) {
        final Path home = dir.resolve("home");
        return new PoreloContext(
                        new ProcessCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, home), home.resolve("bin")),
                        args -> 0)
                .reading(new StandardInput(input, false));
    }

    private Path revocationList(final String name) throws Exception {
        final Path krl = dir.resolve(name + ".krl");
        Files.deleteIfExists(krl);
        run("ssh-keygen", "-q", "-k", "-f", krl.toString(), dir.resolve(name + ".pub").toString());
        return krl;
    }

    private String sign(
            final String ca,
            final String principal,
            final String id,
            final String validity,
            final String... options)
            throws Exception {
        final Path pub = dir.resolve("certified.pub");
        Files.copy(dir.resolve("task.pub"), pub, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "ssh-keygen",
                                "-q",
                                "-s",
                                dir.resolve(ca).toString(),
                                "-I",
                                id,
                                "-n",
                                principal,
                                "-V",
                                validity));
        command.addAll(List.of(options));
        command.add(pub.toString());
        run(command.toArray(String[]::new));
        final Path cert = dir.resolve("certified-cert.pub");
        final String line = Files.readString(cert).strip();
        Files.delete(cert);
        return line;
    }

    private String keygen(final String name) throws Exception {
        final Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name, "-f", file.toString());
        }
        final String[] fields = Files.readString(dir.resolve(name + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }

    private static void run(final String... command) throws Exception {
        assertThat(
                        new ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .as(String.join(" ", command))
                .isZero();
    }
}
