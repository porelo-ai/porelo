package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link MessageVerify}. */
class MessageVerifyTest {

    private final SigningKey peerKey = SigningKey.generate("peer");

    private final MessageDelivery.Signers peers = PeerKeys.of("reviewer", peerKey);

    private final java.util.function.Predicate<byte[]> certified =
            key -> java.util.Arrays.equals(key, peerKey.keyBlob());

    /** Delivers one message the honest way, so what is verified is what the pipeline produced. */
    private Mailbox delivered(final Path dir, final String json) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(
                mailbox.inbound().resolve("m-1.json.sig"),
                SshSignature.sign(
                        peerKey, json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        new MessageRecord(mailbox).append(MessageRecord.DELIVERED, "m-1.json", "m-1", "");
        new MessageDelivery().deliver(mailbox, peers);
        return mailbox;
    }

    @Test
    void an_untouched_mailbox_is_intact(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");

        final MessageVerify.Report report = new MessageVerify().check(mailbox, certified);

        assertThat(report.faults()).isEmpty();
        assertThat(report.signatures()).isEqualTo(1);
        assertThat(report.lines()).isEqualTo(1);
    }

    @Test
    void names_a_message_that_was_changed_after_it_arrived(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");

        Files.writeString(
                mailbox.inboxNew().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"kind\":\"handover\"}}");

        assertThat(new MessageVerify().check(mailbox, certified).faults())
                .singleElement()
                .satisfies(fault -> assertThat(fault.detail()).contains("no longer matches"));
    }

    @Test
    void names_the_record_line_that_was_edited(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");
        final Path log = mailbox.record().resolve(MessageRecord.FILE);
        Files.writeString(log, Files.readString(log).replace("\"m-1.json\"", "\"m-9.json\""));

        assertThat(new MessageVerify().check(mailbox, certified).faults())
                .singleElement()
                .satisfies(fault -> assertThat(fault.what()).isEqualTo("line 1"));
    }

    @Test
    void a_file_beyond_a_messages_size_in_its_place_is_named_and_not_read_whole(
            @TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");
        // The task writes its inbox: a sparse file of gigabytes there, read whole, ended the check
        // with an OutOfMemoryError instead of an answer.
        try (java.io.RandomAccessFile file =
                new java.io.RandomAccessFile(
                        mailbox.inboxNew().resolve("m-1.json").toFile(), "rw")) {
            file.setLength(3L * 1024 * 1024 * 1024);
        }

        assertThat(new MessageVerify().check(mailbox, certified).faults())
                .singleElement()
                .satisfies(fault -> assertThat(fault.detail()).contains("no longer matches"));
    }

    /** An agent may delete what it has read, and that must not read as tampering. */
    @Test
    void a_message_the_agent_deleted_is_not_a_fault(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");

        Files.delete(mailbox.inboxNew().resolve("m-1.json"));

        final MessageVerify.Report report = new MessageVerify().check(mailbox, certified);
        assertThat(report.faults()).isEmpty();
        assertThat(report.signatures()).isZero();
    }

    @Test
    void names_a_message_that_porelo_did_not_deliver(@TempDir final Path dir) throws IOException {
        // Harm: a file the container put under a recorded name must not pass for that message.
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");

        assertThat(
                        new MessageVerify()
                                .check(new Mailbox(mailbox.root(), () -> "someone-else"), certified)
                                .faults())
                .singleElement()
                .satisfies(
                        fault ->
                                assertThat(fault.detail())
                                        .isEqualTo("it was not delivered by Porelo"));
    }

    @Test
    void a_file_planted_under_the_name_of_a_read_message_does_not_hide_it(@TempDir final Path dir)
            throws IOException {
        // Harm: a link in new under a recorded name kept the real message in cur from being
        // checked.
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");
        Files.move(mailbox.inboxNew().resolve("m-1.json"), mailbox.inboxCur().resolve("m-1.json"));
        Files.createSymbolicLink(
                mailbox.inboxNew().resolve("m-1.json"), mailbox.inboxCur().resolve("m-1.json"));

        final MessageVerify.Report report = new MessageVerify().check(mailbox, certified);

        assertThat(report.faults()).as("the delivered one in cur is checked and holds").isEmpty();
        assertThat(report.signatures()).isEqualTo(1);
    }

    @Test
    void a_delivered_message_that_cannot_be_read_is_no_tamper_alarm(@TempDir final Path dir)
            throws IOException {
        // Harm: a full disk or a missing pin directory read as a changed message is a false alarm.
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");
        Files.delete(mailbox.inboxTmp());

        assertThat(new MessageVerify().check(mailbox, certified).faults())
                .singleElement()
                .satisfies(
                        fault ->
                                assertThat(fault.detail())
                                        .startsWith("it could not be read to check it"));
    }
}
