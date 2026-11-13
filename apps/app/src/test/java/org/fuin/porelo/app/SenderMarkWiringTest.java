package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a sending task's mark reaches what is really sent and recorded: the daemon's pass and
 * a pass by hand mark alike, by what the task recorded at its start, and a kept verdict is never
 * lost to a later line that carries none.
 */
class SenderMarkWiringTest {

    @TempDir Path dir;

    /** Each {@code --shown} line a transport was handed, read while it was there. */
    private final List<String> shown = new ArrayList<>();

    private final FakeCommandRunner runner =
            new FakeCommandRunner() {
                @Override
                public CommandResult run(final Command command) {
                    final int at = command.arguments().indexOf("--" + TransportSend.SHOWN);
                    if (at >= 0) {
                        try {
                            shown.add(
                                    Files.readString(
                                            Path.of(command.arguments().get(at + 1)),
                                            StandardCharsets.UTF_8));
                        } catch (final IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }
                    return super.run(command);
                }
            };

    private PoreloContext context() {
        final var xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private static void record(
            final PoreloContext context,
            final String container,
            final String project,
            final String task)
            throws Exception {
        LegacyTaskFixture.record(context, container, project, "a".repeat(64));
        final Path sidecar = context.paths().tasks().taskRecord(container).resolve("sidecar.json");
        Files.writeString(
                sidecar, Files.readString(sidecar).replace("{", "{\"task\":\"" + task + "\","));
    }

    /** One {@code podman ps} line, with the commit the task's start labelled it with. */
    private static String running(final String container, final String task, final String commit) {
        return container
                + "\tUp 1 minute\t\t\tweb\tguarded\tr\t"
                + commit
                + "\t"
                + "a".repeat(64)
                + "\t"
                + task
                + "\n";
    }

    private static void executable(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void theDaemonMarksASenderByWhatItsTaskRecordedNotByTheProjectNow() throws Exception {
        // Nothing on this machine verifies 'web' now; one task's start recorded the commit its file
        // was verified at, the other's recorded none.
        final PoreloContext context = context();
        record(context, "porelo-web_x", "web", "x");
        record(context, "porelo-web_y", "web", "y");
        runner.answering(
                "podman ps",
                running("porelo-web_x", "x", "c0ffee") + running("porelo-web_y", "y", ""));

        final TransportSend.Marks marks = new MessageWatch(context, Duration.ZERO).pass().marks();

        assertThat(marks.of("porelo-web_x"))
                .as("verified at its start, whatever the project's source says today")
                .isEmpty();
        assertThat(marks.of("porelo-web_y"))
                .as("nothing verified its start")
                .isEqualTo(TrustMark.UNVERIFIED);
    }

    @Test
    void aPassByHandMarksTheLineItSendsAsTheDaemonDoes() throws Exception {
        final PoreloContext context = context();
        record(context, "porelo-web_x", "web", "x");
        runner.answering("podman ps", running("porelo-web_x", "x", ""));
        final Path project = dir.resolve("web.yml");
        Files.writeString(
                project,
                "project:\n  name: web\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n"
                        + "mail:\n  peers:\n    reviewer: { address: \"local:peer\", trust: vouched }\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("web"), project + "\n");
        executable(
                context.paths().xdg().data().resolve("filter").resolve(PoreloPaths.MESSAGE_FILTER));
        executable(
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "local"));
        runner.answering("describe", "{\"scheme\":\"local\",\"shown\":true}");
        runner.answering(" send ", "{}");
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("porelo-web_x"));
        mailbox.create();
        final Path active = mailbox.queueActive("local");
        Files.createDirectories(active);
        Files.writeString(
                active.resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"parts\":[{\"text\":\"hello\"}]}");
        Files.writeString(active.resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(active.resolve("m-1.json.to"), "peer");

        final var cli = PoreloCli.commandLine(context);
        final var err = new StringWriter();
        cli.setErr(new PrintWriter(err));
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.execute("talk", "pass", "porelo-web_x");

        assertThat(shown).as(err.toString()).singleElement().asString().startsWith("[unverified] ");
    }

    @Test
    void aMessageHeldAgainOnItsWayOutKeepsTheVerdictItWasFirstHeldWith() throws IOException {
        // Intake held it unsigned; a person released it; dispatch held it again for a peer nobody
        // knows, on a line that says nothing of a mark.
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-web_x"));
        mailbox.create();
        Files.writeString(mailbox.hold().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(
                MessageRecord.HELD,
                "m-1.json",
                "",
                "",
                "it was not sent: no key of its own",
                MessageRecord.OUT,
                TrustMark.UNSIGNED);
        record.append(
                MessageRecord.HELD,
                "m-1.json",
                "",
                "",
                "this project may not address 'stranger'",
                MessageRecord.OUT);

        assertThat(new MessageRead().read(mailbox, "m-1").mark()).isEqualTo(TrustMark.UNSIGNED);
        assertThat(new MessageRead().list(mailbox))
                .extracting(MessageRead.Held::mark)
                .containsExactly(TrustMark.UNSIGNED);
    }

    @Test
    void whatAPassHoldsOrSendsOnItsWayOutIsRecordedWithTheSendersMark() throws IOException {
        final Path filter = dir.resolve("filter");
        executable(filter);
        final Path transports = dir.resolve("transports");
        executable(transports.resolve(TransportDirectory.PREFIX + "local"));
        runner.answering("describe", "{\"scheme\":\"local\"}");
        runner.answering(" send ", "{}");
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-web_x"));
        mailbox.create();
        Files.createDirectories(mailbox.accepted());
        for (final Map.Entry<String, String> each :
                Map.of("m-1", "stranger", "m-2", "reviewer").entrySet()) {
            Files.writeString(
                    mailbox.accepted().resolve(each.getKey() + ".json"),
                    "{\"messageId\":\""
                            + each.getKey()
                            + "\",\"role\":\"ROLE_AGENT\",\"metadata\":{\"to\":\""
                            + each.getValue()
                            + "\"}}");
            Files.writeString(
                    mailbox.accepted().resolve(each.getKey() + ".json.sig"),
                    "-----BEGIN SSH SIGNATURE-----");
        }
        // Released by a person: a peer nobody decided about waits for one.
        Files.writeString(
                mailbox.accepted().resolve("m-2.json" + MessageDispatch.RELEASED_SUFFIX), "");
        final MessagePass pass =
                new MessagePass(runner, filter, new TransportDirectory(List.of(transports)));
        pass.marking(container -> TrustMark.UNVERIFIED);

        pass.run(
                mailbox,
                new Mail(List.of(new Mail.Peer("reviewer", "local:peer", Mail.Peer.VOUCHED))),
                () -> {
                    throw new MessageSigners.Unsigned("nothing to sign here", false);
                },
                PeerKeys.NONE,
                new Moderation(dir.resolve("moderation").resolve("web.json")));

        final List<Map<String, Object>> lines = new MessageRecord(mailbox).entries();
        assertThat(lines)
                .filteredOn(line -> "m-1.json".equals(line.get("message")))
                .filteredOn(line -> MessageRecord.HELD.equals(line.get("event")))
                .singleElement()
                .satisfies(line -> assertThat(line.get("mark")).isEqualTo(TrustMark.UNVERIFIED));
        assertThat(lines)
                .as("the record: %s", lines)
                .filteredOn(line -> "m-2.json".equals(line.get("message")))
                .filteredOn(line -> MessageRecord.SENT.equals(line.get("event")))
                .singleElement()
                .satisfies(line -> assertThat(line.get("mark")).isEqualTo(TrustMark.UNVERIFIED));
        assertThat(new MessageRecord(mailbox).firstBrokenLine()).isZero();
    }

    @Test
    void aMessageHeldAgainOnItsWayOutCarriesItsKeptVerdictOnTheNewLine() throws IOException {
        // The live stream reads each line as it is written, so the line itself must say it: a
        // message intake held unsigned, released by a person, held again for a peer nobody knows.
        final Path filter = dir.resolve("filter");
        executable(filter);
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-web_x"));
        mailbox.create();
        new MessageRecord(mailbox)
                .append(
                        MessageRecord.HELD,
                        "m-1.json",
                        "",
                        "",
                        "it was not sent: no key of its own",
                        MessageRecord.OUT,
                        TrustMark.UNSIGNED);
        Files.createDirectories(mailbox.accepted());
        Files.writeString(
                mailbox.accepted().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\",\"metadata\":{\"to\":\"stranger\"}}");
        Files.writeString(
                mailbox.accepted().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(
                mailbox.accepted().resolve("m-1.json" + MessageDispatch.RELEASED_SUFFIX), "");
        final MessagePass pass =
                new MessagePass(
                        runner, filter, new TransportDirectory(List.of(dir.resolve("none"))));

        pass.run(
                mailbox,
                new Mail(List.of()),
                () -> {
                    throw new MessageSigners.Unsigned("no key of its own", false);
                },
                PeerKeys.NONE,
                new Moderation(dir.resolve("moderation").resolve("web.json")));

        final List<Map<String, Object>> held =
                new MessageRecord(mailbox)
                        .entries().stream()
                                .filter(line -> MessageRecord.HELD.equals(line.get("event")))
                                .toList();
        assertThat(held).hasSize(2);
        assertThat(held.get(1).get("mark")).as("the new line itself").isEqualTo(TrustMark.UNSIGNED);
    }

    @Test
    void anUnsignedTaskClaimingAPersonsRoleIsStillHeldUnsigned() throws IOException {
        // Changing a field of its own message must not take the verdict away.
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-web_x"));
        mailbox.create();
        Files.writeString(
                mailbox.outboxNew().resolve("m.json"),
                "{\"messageId\":\"m\",\"role\":\"ROLE_USER\"}");

        new MessagePass(runner, null, new TransportDirectory(List.of(dir.resolve("none"))))
                .run(
                        mailbox,
                        new Mail(List.of()),
                        () -> {
                            throw new MessageSigners.Unsigned("no key of its own", false);
                        },
                        PeerKeys.NONE,
                        new Moderation(dir.resolve("moderation").resolve("web.json")));

        assertThat(mailbox.hold().resolve("m.json")).exists();
        assertThat(new MessageRead().read(mailbox, "m").mark()).isEqualTo(TrustMark.UNSIGNED);
    }

    @Test
    void aRecordWrittenBeforeMarksStillVerifiesByTheHashesItWasWrittenWith() throws IOException {
        // Hashes computed outside Java, from the material before marks: no direction slot on a
        // line without one, the direction alone on a line with one.
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-web_x"));
        mailbox.create();
        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                {"at":"2026-10-01T10:00:00Z","event":"taken","message":"m-0.json","id":"","peer":"","detail":"","previous":"","hash":"29cb9e2d0483340ec647bf5b5a3aac3902227aa164720a00699a8a6d839836c9"}
                {"at":"2026-10-01T10:00:01Z","event":"held","message":"m-1.json","id":"m-1","peer":"","detail":"it arrived without a signature","direction":"in","previous":"29cb9e2d0483340ec647bf5b5a3aac3902227aa164720a00699a8a6d839836c9","hash":"f619169f7aee63e561793cbc9495247bcaa2360c0235e96be69256525c983289"}
                {"at":"2026-10-01T10:00:02Z","event":"held","message":"m-1.json","id":"m-1","peer":"","detail":"this project may not address 'stranger'","direction":"out","previous":"f619169f7aee63e561793cbc9495247bcaa2360c0235e96be69256525c983289","hash":"eed01d5ad2673cbb1fc9555d4b0d87c7aa254d07cec46bcece74439890f31278"}
                """,
                StandardCharsets.UTF_8);
        final MessageRecord record = new MessageRecord(mailbox);

        assertThat(record.firstBrokenLine()).as("an old record verifies as it was").isZero();

        record.append(
                MessageRecord.HELD,
                "m-2.json",
                "",
                "",
                "it was not sent",
                MessageRecord.OUT,
                TrustMark.UNVERIFIED);
        assertThat(record.firstBrokenLine()).as("and goes on with a marked line").isZero();
    }
}
