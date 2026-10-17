package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link MessageVerify}. */
class MessageVerifyTest {

    private final SigningKey peerKey = SigningKey.generate("peer");

    private final List<MessageDelivery.Peer> peers =
            List.of(new MessageDelivery.Peer("reviewer", List.of(peerKey.keyBlob())));

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

        final MessageVerify.Report report = new MessageVerify().check(mailbox, peers);

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

        assertThat(new MessageVerify().check(mailbox, peers).faults())
                .singleElement()
                .satisfies(fault -> assertThat(fault.detail()).contains("no longer matches"));
    }

    @Test
    void names_the_record_line_that_was_edited(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");
        final Path log = mailbox.record().resolve(MessageRecord.FILE);
        Files.writeString(log, Files.readString(log).replace("\"m-1.json\"", "\"m-9.json\""));

        assertThat(new MessageVerify().check(mailbox, peers).faults())
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

        assertThat(new MessageVerify().check(mailbox, peers).faults())
                .singleElement()
                .satisfies(fault -> assertThat(fault.detail()).contains("no longer matches"));
    }

    /** An agent may delete what it has read, and that must not read as tampering. */
    @Test
    void a_message_the_agent_deleted_is_not_a_fault(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = delivered(dir, "{\"messageId\":\"m-1\"}");

        Files.delete(mailbox.inboxNew().resolve("m-1.json"));

        final MessageVerify.Report report = new MessageVerify().check(mailbox, peers);
        assertThat(report.faults()).isEmpty();
        assertThat(report.signatures()).isZero();
    }
}
