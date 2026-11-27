package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link BounceDelivery}. */
class BounceDeliveryTest {

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void an_answer_arrives_where_the_agent_already_reads(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(
                mailbox.feedback().resolve("20260918T0713Z--msg-a.json"),
                "{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"refused\"},{\"data\":{}}]}");

        final var delivered = new BounceDelivery().deliver(mailbox);

        assertThat(delivered).containsExactly("20260918T0713Z--msg-a.json");
        assertThat(mailbox.inboxNew().resolve("20260918T0713Z--msg-a.json")).exists();
        assertThat(mailbox.feedback()).isEmptyDirectory();
        assertThat(mailbox.inboxTmp()).isEmptyDirectory();
    }

    /**
     * A bounce carries a data part, which the narrowed schema refuses for anything arriving from
     * outside. It is delivered without a signature and without being filtered because it never left
     * this machine - and the check that would refuse it is the one that wrote it.
     */
    @Test
    void a_data_part_is_not_a_reason_to_refuse_the_filters_own_answer(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(
                mailbox.feedback().resolve("answer.json"),
                "{\"parts\":[{\"data\":{\"decision\":\"rejected\"}}]}");

        assertThat(new BounceDelivery().deliver(mailbox)).containsExactly("answer.json");
        assertThat(mailbox.inboxNew().resolve("answer.json")).exists();
        assertThat(mailbox.hold()).isEmptyDirectory();
    }

    /**
     * Harm: on an SELinux host an answer renamed in from the host's own half kept that half's
     * label, and the agent, confined to the box's label, could not even read that it was there.
     */
    @Test
    void an_answer_is_written_anew_in_the_box_never_renamed_into_it(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final Path answer = mailbox.feedback().resolve("answer.json");
        Files.writeString(answer, "{\"parts\":[{\"text\":\"refused\"}]}");
        final Object written = Files.readAttributes(answer, BasicFileAttributes.class).fileKey();

        new BounceDelivery().deliver(mailbox);

        final Path delivered = mailbox.inboxNew().resolve("answer.json");
        assertThat(delivered).hasContent("{\"parts\":[{\"text\":\"refused\"}]}");
        assertThat(Files.readAttributes(delivered, BasicFileAttributes.class).fileKey())
                .as("a file of the box's own, labelled as the box is")
                .isNotNull()
                .isNotEqualTo(written);
        assertThat(mailbox.feedback()).isEmptyDirectory();
    }

    @Test
    void nothing_to_answer_is_not_an_error(@TempDir final Path dir) throws IOException {
        assertThat(new BounceDelivery().deliver(mailbox(dir))).isEmpty();
    }
}
