package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a message's mark is the receiving machine's own verdict on its sender, kept in its
 * record: a mark taken from the message would be the sender's word, and a sender that lies leaves
 * it out.
 */
class MessageMarkTest {

    /** What a lying sender writes about itself; nothing may read it. */
    private static final String CLAIMING_CERTIFIED =
            "{\"messageId\":\"%s\",\"metadata\":{\"mark\":\"\",\"certified\":true}}";

    @TempDir Path dir;

    private Mailbox mailbox() throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("porelo-p-review"));
        mailbox.create();
        return mailbox;
    }

    private static String json(final String id) {
        return CLAIMING_CERTIFIED.formatted(id);
    }

    private static void arrive(final Mailbox mailbox, final SigningKey key, final String id)
            throws IOException {
        final String json = json(id);
        Files.writeString(mailbox.inbound().resolve(id + ".json"), json);
        Files.writeString(
                mailbox.inbound().resolve(id + ".json.sig"),
                SshSignature.sign(
                        key, json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
    }

    @Test
    void aMessageWhoseSignatureCannotBeBelievedIsHeldUnsignedWhateverItSays() throws IOException {
        final Mailbox mailbox = mailbox();
        Files.writeString(mailbox.inbound().resolve("bare.json"), json("bare"));
        arrive(mailbox, SigningKey.generate("stranger"), "stranger");

        final MessageDelivery.Outcome outcome =
                new MessageDelivery()
                        .deliver(mailbox, PeerKeys.of("reviewer", SigningKey.generate("peer")));

        assertThat(outcome.held())
                .extracting(MessageDelivery.Held::message, MessageDelivery.Held::mark)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("bare.json", TrustMark.UNSIGNED),
                        org.assertj.core.groups.Tuple.tuple("stranger.json", TrustMark.UNSIGNED));
    }

    @Test
    void aBelievedMessageIsMarkedByWhetherThisMachineVerifiedTheSendingProject()
            throws IOException {
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir);
        final SigningKey own = keys.task("p", "review");
        final SigningKey sender = keys.task("p", "build");
        final Mailbox mailbox = mailbox();
        arrive(mailbox, sender, "m-1");

        final MessageSigners nothingVerified =
                new MessageSigners(
                        () -> own,
                        keys.certificates(),
                        "p",
                        () -> List.of(keys.certifier()),
                        project -> false);
        assertThat(nothingVerified.trace(sender.keyBlob(), Instant.now()).mark())
                .isEqualTo(TrustMark.UNVERIFIED);
        final MessageDelivery.Outcome outcome =
                new MessageDelivery().deliver(mailbox, nothingVerified);

        assertThat(outcome.delivered())
                .extracting(MessageDelivery.Delivered::mark)
                .containsExactly(TrustMark.UNVERIFIED);

        final MessageSigners verified =
                new MessageSigners(
                        () -> own,
                        keys.certificates(),
                        "p",
                        () -> List.of(keys.certifier()),
                        "p"::equals);
        assertThat(verified.trace(sender.keyBlob(), Instant.now()).mark())
                .as("a certified sender of a verified project carries no mark")
                .isEmpty();
    }

    @Test
    void theRecordKeepsTheMarkInItsChainAndAHeldMessageShowsIt() throws IOException {
        final Mailbox mailbox = mailbox();
        Files.writeString(mailbox.hold().resolve("m-1.json"), json("m-1"));
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.TAKEN, "m-0.json", "", "");
        record.append(
                MessageRecord.HELD,
                "m-1.json",
                "m-1",
                "",
                "it arrived without a signature",
                MessageRecord.IN,
                TrustMark.UNSIGNED);

        assertThat(record.firstBrokenLine()).as("a marked line verifies").isZero();
        assertThat(new MessageRead().read(mailbox, "m-1").mark()).isEqualTo(TrustMark.UNSIGNED);
        assertThat(new MessageRead().list(mailbox))
                .extracting(MessageRead.Held::mark)
                .containsExactly(TrustMark.UNSIGNED);

        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        Files.writeString(
                file,
                Files.readString(file).replace(",\"mark\":\"unsigned\"", ""),
                StandardCharsets.UTF_8);
        assertThat(record.firstBrokenLine())
                .as("a line that lost its mark no longer verifies")
                .isEqualTo(2);
    }

    @Test
    void aMessageItsOwnTaskHadNoKeyToSignIsRecordedHeldUnsigned() throws IOException {
        final Mailbox mailbox = mailbox();
        Files.writeString(
                mailbox.outboxNew().resolve("out-1.json"),
                "{\"messageId\":\"out-1\",\"role\":\"ROLE_AGENT\",\"metadata\":{\"to\":\"reviewer\"}}");

        new MessagePass(
                        new FakeCommandRunner(),
                        null,
                        new TransportDirectory(List.of(dir.resolve("none"))))
                .run(
                        mailbox,
                        new Mail(List.of()),
                        () -> {
                            throw new MessageSigners.Unsigned("no key of its own", false);
                        },
                        PeerKeys.NONE,
                        new Moderation(dir.resolve("moderation").resolve("p.json")));

        final List<Map<String, Object>> held =
                new MessageRecord(mailbox)
                        .entries().stream()
                                .filter(line -> MessageRecord.HELD.equals(line.get("event")))
                                .toList();
        assertThat(held)
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.get("message")).isEqualTo("out-1.json");
                            assertThat(line.get("mark")).isEqualTo(TrustMark.UNSIGNED);
                            assertThat(line.get("direction")).isEqualTo(MessageRecord.OUT);
                        });
        assertThat(new MessageRead().list(mailbox))
                .as("shown with its mark, for a person to release")
                .extracting(MessageRead.Held::message, MessageRead.Held::mark)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("out-1.json", "unsigned"));
    }

    @Test
    void theLineTheRoomShowsCarriesTheSendersMarkAndNothingElseChanges() {
        assertThat(TransportSend.marked(TrustMark.UNVERIFIED, "hello"))
                .isEqualTo("[unverified] hello");
        assertThat(TransportSend.marked("", "hello")).isEqualTo("hello");
    }
}
