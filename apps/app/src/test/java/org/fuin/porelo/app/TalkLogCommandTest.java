package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Test for {@link TalkLogCommand}: a task's conversation as it happened, in order, never what a
 * message says.
 */
class TalkLogCommandTest {

    private static final String TASK = "porelo-p-t";

    private final StringWriter out = new StringWriter();

    @Test
    void matchesTheRecordedIdWhenAMessageFileNameWasReused(@TempDir final Path dir)
            throws IOException {
        // Harm: a reused file name must not attribute an earlier message to a later audience.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        final Path old = mailbox.inboxCur().resolve("event.json");
        Files.writeString(
                old,
                """
                {"messageId":"old","metadata":{"to":["reviewer"],"via":"room"}}
                """);
        Files.writeString(
                mailbox.inboxNew().resolve("event.json"),
                """
                {"messageId":"new","metadata":{"to":["t"],"via":"direct"}}
                """);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.DELIVERED, "event.json", "old", "person", "");
        record.append(MessageRecord.DELIVERED, "event.json", "new", "person", "");

        assertThat(execute(context, TASK, "--json"))
                .as("both retained messages are readable")
                .isZero();

        assertThat(jsonLines())
                .as("each record keeps its own audience")
                .extracting(line -> List.of(line.get("id"), line.get("to"), line.get("via")))
                .containsExactly(
                        List.of("old", List.of("reviewer"), "room"),
                        List.of("new", List.of("t"), "direct"));
        Files.delete(old);
        out.getBuffer().setLength(0);

        assertThat(execute(context, TASK, "--json"))
                .as("a gone earlier message keeps its record")
                .isZero();
        assertThat(jsonLines().getFirst())
                .as("the newer file cannot substitute for the gone message")
                .containsEntry("id", "old")
                .containsEntry("messageMissing", true)
                .containsEntry("to", null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"via\":{\"text\":\"private words\"}}",
                "{\"via\":[\"private words\"]}",
                "{\"via\":\"private words\"}",
                "{\"to\":[\"t\",{\"text\":\"private words\"}],\"via\":\"room\"}",
                "{\"via\":\"room\",\"all\":\"private words\"}"
            })
    void refusesMalformedAudienceWithoutShowingItsContent(
            final String metadata, @TempDir final Path dir) throws IOException {
        // Harm: malformed audience fields must not leak content or invent a recipient or all flag.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        Files.writeString(
                mailbox.rejected().resolve("refused.json"),
                "{\"messageId\":\"refused\",\"metadata\":" + metadata + "}");
        new MessageRecord(mailbox)
                .append(MessageRecord.REFUSED, "refused.json", "refused", "person", "");

        final int exit = execute(context, TASK, "--json");

        assertThat(out.toString())
                .as("a malformed audience never exposes its content")
                .doesNotContain("private words");
        assertThat(exit)
                .as("malformed audience evidence refuses instead of silently changing its meaning")
                .isNotZero();
    }

    @Test
    void marksAGoneMessageWithoutInventingItsAudience(@TempDir final Path dir) throws IOException {
        // Harm: missing evidence must not become a claim about whom a person addressed.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        new MessageRecord(mailbox)
                .append(MessageRecord.DELIVERED, "gone.json", "gone", "person", "");
        Files.writeString(mailbox.record().resolve(AgentWake.ANNOUNCED), "gone.json\n");

        assertThat(execute(context, TASK, "--json"))
                .as("missing content keeps the record readable")
                .isZero();

        assertThat(jsonLines())
                .as("the record reports missing evidence explicitly")
                .singleElement()
                .satisfies(
                        line ->
                                assertThat(line)
                                        .as("the wake record survives independently of the message")
                                        .containsEntry("messageMissing", true)
                                        .containsEntry("to", null)
                                        .containsEntry("via", null)
                                        .containsEntry("all", null)
                                        .containsEntry("woken", true));
    }

    @Test
    void doesNotInferAWakeFromADirectMessageOrWriteIntoTheMailbox(@TempDir final Path dir)
            throws IOException {
        // Harm: an eligible message is not proof of a wake, and reading must not manufacture
        // evidence.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        Files.writeString(
                mailbox.inboxNew().resolve("direct.json"),
                """
                {"messageId":"direct","role":"ROLE_USER","metadata":{"to":["t"],"via":"direct"}}
                """);
        new MessageRecord(mailbox)
                .append(MessageRecord.DELIVERED, "direct.json", "direct", "person", "");
        final Map<Path, String> before = snapshot(mailbox);

        assertThat(execute(context, TASK, "--json"))
                .as("a direct delivery is readable as JSON")
                .isZero();

        assertThat(jsonLines())
                .as("eligibility does not substitute for wake-announced")
                .singleElement()
                .satisfies(
                        line ->
                                assertThat(line)
                                        .as("only a recorded wake counts")
                                        .containsEntry("to", List.of("t"))
                                        .containsEntry("via", "direct")
                                        .containsEntry("all", false)
                                        .containsEntry("messageMissing", false)
                                        .containsEntry("woken", false));
        assertThat(snapshot(mailbox))
                .as("reading changes no mailbox file and creates none")
                .isEqualTo(before);
    }

    @Test
    void reportsTheNamedRecipientWithoutClaimingAnotherTaskWasWoken(@TempDir final Path dir)
            throws IOException {
        // Harm: a room delivery must not be mistaken for a request that woke every task.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        Files.writeString(
                mailbox.inboxNew().resolve("room-event.json"),
                """
                {"messageId":"room-event","role":"ROLE_USER","parts":[{"text":"private words"}],
                 "metadata":{"to":["reviewer"],"via":"room","all":false}}
                """);
        Files.writeString(mailbox.record().resolve(AgentWake.ANNOUNCED), "other-event.json\n");
        new MessageRecord(mailbox)
                .append(MessageRecord.DELIVERED, "room-event.json", "room-event", "person", "");

        assertThat(execute(context, TASK, "--json")).as("the log is available as JSON").isZero();

        assertThat(jsonLines())
                .as("one object identifies the delivered event, its recipient and actual wake")
                .singleElement()
                .satisfies(
                        line ->
                                assertThat(line)
                                        .as("delivery to this inbox does not imply a wake")
                                        .containsEntry("id", "room-event")
                                        .containsEntry("message", "room-event.json")
                                        .containsEntry("event", "delivered")
                                        .containsEntry("peer", "person")
                                        .containsEntry("detail", "")
                                        .containsEntry("to", List.of("reviewer"))
                                        .containsEntry("via", "room")
                                        .containsEntry("all", false)
                                        .containsEntry("woken", false));
        assertThat(out.toString())
                .as("the log never exposes message text")
                .doesNotContain("private words");
    }

    @Test
    void reportsAnAllLineAndItsRecordedWakeInEachTask(@TempDir final Path dir) throws IOException {
        // Harm: a rehearsal must be able to tell that every task was told of an all-agents line.
        final PoreloContext context = context(dir);
        for (final String task : List.of(TASK, "porelo-p-reviewer")) {
            final Mailbox mailbox = mailbox(context, task);
            Files.writeString(
                    mailbox.inboxCur().resolve("all-event.json"),
                    """
                    {"messageId":"all-event","role":"ROLE_USER","parts":[{"text":"private words"}],
                     "metadata":{"to":[],"via":"room","all":true}}
                    """);
            Files.writeString(mailbox.record().resolve(AgentWake.ANNOUNCED), "all-event.json\n");
            final MessageRecord record = new MessageRecord(mailbox);
            record.append(MessageRecord.DELIVERED, "all-event.json", "all-event", "person", "");
            record.append(MessageRecord.DUPLICATE, "all-event.json", "all-event", "person", "");
            out.getBuffer().setLength(0);

            assertThat(execute(context, task, "--json")).as("JSON log of %s", task).isZero();

            final List<Map<String, Object>> lines = jsonLines();
            assertThat(lines)
                    .as("every record line remains in order in %s", task)
                    .extracting(line -> line.get("event"))
                    .containsExactly("delivered", "duplicate");
            assertThat(lines)
                    .as(
                            "the read message keeps its original audience and recorded wake in %s",
                            task)
                    .allSatisfy(
                            line ->
                                    assertThat(line)
                                            .as("the wake is recorded for this task")
                                            .containsEntry("id", "all-event")
                                            .containsEntry("to", List.of())
                                            .containsEntry("via", "room")
                                            .containsEntry("all", true)
                                            .containsEntry("woken", true));
            assertThat(out.toString())
                    .as("the log never exposes message text")
                    .doesNotContain("private words");
        }
    }

    @Test
    void keepsTheTableExactlyAsItWas(@TempDir final Path dir) throws IOException {
        // Harm: adding a machine-readable log must preserve the existing terminal command's output.
        final PoreloContext context = context(dir);
        final Mailbox mailbox = mailbox(context, TASK);
        Files.writeString(
                mailbox.record().resolve(MessageRecord.FILE),
                """
                {"at":"2026-11-09T10:00:00Z","event":"delivered","message":"room-event.json","id":"room-event","peer":"person","detail":""}
                """);
        Files.writeString(
                mailbox.inboxNew().resolve("room-event.json"),
                """
                {"metadata":{"to":["reviewer"],"via":"room","all":true},"parts":[{"text":"private words"}]}
                """);
        Files.writeString(mailbox.record().resolve(AgentWake.ANNOUNCED), "room-event.json\n");

        assertThat(execute(context)).as("the original table command still succeeds").isZero();

        assertThat(out.toString())
                .as("the table's columns, padding and empty-value rendering stay unchanged")
                .isEqualTo(
                        "%-24s %-12s %-20s %-36s %s%n%-24s %-12s %-20s %-36s %s%n"
                                .formatted(
                                        "AT",
                                        "EVENT",
                                        "PEER",
                                        "MESSAGE",
                                        "DETAIL",
                                        "2026-11-09T10:00:00Z",
                                        "delivered",
                                        "person",
                                        "room-event.json",
                                        "-"));
    }

    @Test
    void showsWhatHappenedToEachMessageInOrderAndNeverWhatItSays(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(TASK));
        mailbox.create();
        Files.writeString(
                mailbox.hold().resolve("m-1.json"),
                """
                {"messageId":"m-1","role":"ROLE_AGENT","parts":[{"text":"the secret plan"}],"metadata":{"to":"reviewer"}}
                """);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(
                MessageRecord.HELD, "m-1.json", "m-1", "rev\u001b[2Jiewer", "held for a person");
        record.append(MessageRecord.SENT, "m-1.json", "m-1", "reviewer", "matrix");

        assertThat(execute(context)).isEqualTo(0);

        final String said = out.toString();
        assertThat(said).contains("EVENT").contains("held").contains("sent");
        assertThat(said.indexOf("held   ")).isLessThan(said.indexOf("sent   "));
        assertThat(said)
                .contains("rev\\x1b[2Jiewer")
                .doesNotContain("\u001b")
                .doesNotContain("the secret plan");
    }

    @Test
    void saysSoWhenThereIsNoMessageYet(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox(TASK)).create();

        assertThat(execute(context)).isEqualTo(0);
        assertThat(out.toString()).contains("no message yet");
    }

    private int execute(final PoreloContext context) {
        return execute(context, TASK);
    }

    private int execute(final PoreloContext context, final String task, final String... options) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        final List<String> arguments = new ArrayList<>(List.of("talk", "log", task));
        arguments.addAll(List.of(options));
        return cmd.execute(arguments.toArray(String[]::new));
    }

    private List<Map<String, Object>> jsonLines() {
        final List<Map<String, Object>> lines = new ArrayList<>();
        out.toString()
                .lines()
                .forEach(
                        line -> {
                            final Map<String, Object> entry = new LinkedHashMap<>();
                            ((Map<?, ?>) Json.parse(line))
                                    .forEach((key, value) -> entry.put(String.valueOf(key), value));
                            lines.add(entry);
                        });
        return lines;
    }

    private static Mailbox mailbox(final PoreloContext context, final String task)
            throws IOException {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(task));
        mailbox.create();
        return mailbox;
    }

    private static Map<Path, String> snapshot(final Mailbox mailbox) throws IOException {
        final Map<Path, String> files = new LinkedHashMap<>();
        try (var paths = Files.walk(mailbox.root())) {
            for (final Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                files.put(mailbox.root().relativize(path), Files.readString(path));
            }
        }
        return files;
    }

    private static PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
