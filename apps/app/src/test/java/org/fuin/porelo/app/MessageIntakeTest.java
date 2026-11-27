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

/** Test for {@link MessageIntake}. */
class MessageIntakeTest {

    private final SigningKey key = SigningKey.generate("host");

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void takes_a_finished_message_and_signs_the_bytes_as_they_are(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.outboxNew().resolve("m-1.json"), json);

        final List<String> taken = new MessageIntake(() -> key).take(mailbox);

        assertThat(taken).containsExactly("m-1.json");
        assertThat(mailbox.outboxNew().resolve("m-1.json")).doesNotExist();
        assertThat(mailbox.incoming().resolve("m-1.json")).exists();
        final String signature = Files.readString(mailbox.incoming().resolve("m-1.json.sig"));
        assertThat(
                        SshSignature.verify(
                                json.getBytes(StandardCharsets.UTF_8),
                                signature,
                                key.keyBlob(),
                                MessageIntake.NAMESPACE))
                .isTrue();
    }

    @Test
    void holds_what_a_task_without_a_key_of_its_own_wrote_and_tells_it(@TempDir final Path dir)
            throws IOException {
        // Unsigned, it would reach no receiver that believes it; the agent is told rather than
        // left waiting.
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");

        final List<String> taken =
                new MessageIntake(
                                () -> {
                                    throw new MessageSigners.Unsigned("no key of its own", false);
                                })
                        .take(mailbox);

        assertThat(taken).isEmpty();
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
        assertThat(mailbox.incoming().resolve("m-1.json")).doesNotExist();
        try (var answers = Files.list(mailbox.feedback())) {
            assertThat(answers.toList())
                    .singleElement()
                    .satisfies(
                            answer ->
                                    assertThat(Files.readString(answer))
                                            .contains("it was not sent: no key of its own"));
        }
    }

    // Harm: a reading task without a key of its own had its message to a peer held, where a
    // person sees it offered for release, instead of refused for good as the rule says.
    @Test
    void refuses_for_good_what_an_unsigned_reading_task_addresses_to_anyone_but_its_review(
            @TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(
                mailbox.outboxNew().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"outside\"}}");
        Files.writeString(
                mailbox.outboxNew().resolve("m-2.json"),
                "{\"messageId\":\"m-2\",\"metadata\":{\"to\":\"review\"}}");
        final List<MessageDelivery.Held> told = new java.util.ArrayList<>();

        new MessageIntake(
                        () -> {
                            throw new MessageSigners.Unsigned("no key of its own", false);
                        },
                        told::add)
                .reading(true)
                .take(mailbox);

        assertThat(mailbox.hold().resolve("m-1.json"))
                .as("nothing of a reading task's message to a peer is kept for a release")
                .doesNotExist();
        assertThat(mailbox.rejected().resolve("m-1.json"))
                .as("nor kept among the refused")
                .doesNotExist();
        assertThat(mailbox.hold().resolve("m-2.json"))
                .as("a message to its review alone goes the way an unsigned one goes today")
                .exists();
        assertThat(told)
                .as("each verdict is recorded as it is given")
                .extracting(MessageDelivery.Held::message, MessageDelivery.Held::reason)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "m-1.json", MessageDispatch.READER_REFUSED));
        try (var answers = Files.list(mailbox.feedback())) {
            assertThat(answers.map(MessageIntakeTest::read).toList())
                    .as("the task is told by the rule, about its own message")
                    .anySatisfy(
                            answer ->
                                    assertThat(answer)
                                            .contains(ReadingTasks.SENDS_TO_REVIEW_ONLY)
                                            .contains("\"rejectedMessageId\":\"m-1\""));
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void leaves_what_waits_for_the_vault_where_the_agent_put_it(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");

        final List<String> taken =
                new MessageIntake(
                                () -> {
                                    throw new MessageSigners.Unsigned("the vault is locked", true);
                                })
                        .take(mailbox);

        assertThat(taken).isEmpty();
        assertThat(mailbox.outboxNew().resolve("m-1.json")).as("taken once it opens").exists();
        assertThat(mailbox.hold()).isEmptyDirectory();
    }

    @Test
    void leaves_what_the_agent_is_still_writing(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxTmp().resolve("half.json"), "{\"messageId\"");

        assertThat(new MessageIntake(() -> key).take(mailbox)).isEmpty();
        assertThat(mailbox.outboxTmp().resolve("half.json")).exists();
    }

    @Test
    void ignores_what_is_not_a_message(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve(".keep"), "");
        Files.writeString(mailbox.outboxNew().resolve("notes.txt"), "hello");

        assertThat(new MessageIntake(() -> key).take(mailbox)).isEmpty();
        assertThat(mailbox.outboxNew().resolve("notes.txt")).exists();
    }

    /**
     * The signature has to be there before the message is, because a message with no signature
     * cannot be told from one whose signature was never written.
     */
    @Test
    void a_message_never_arrives_without_its_signature(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        for (int i = 0; i < 5; i++) {
            Files.writeString(
                    mailbox.outboxNew().resolve("m-" + i + ".json"),
                    "{\"messageId\":\"m-" + i + "\"}");
        }

        new MessageIntake(() -> key).take(mailbox);

        try (var entries = Files.list(mailbox.incoming())) {
            entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(
                            message ->
                                    assertThat(
                                                    message.resolveSibling(
                                                            message.getFileName() + ".sig"))
                                            .exists());
        }
    }

    @Test
    void whatIsTakenIsTheHostsOwnCopyWhichTheAgentCannotChangeAfterwards(@TempDir final Path dir)
            throws IOException {

        // Renamed onward, the file stayed the agent's inode: through a hard link it kept in its
        // outbox it could change a message after it was signed, after the filter and after a person
        // read it.
        final Mailbox mailbox = mailbox(dir);
        final Path message = mailbox.outboxNew().resolve("m-1.json");
        Files.writeString(message, "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\"}");
        final Path kept = mailbox.outboxTmp().resolve("kept");
        Files.createLink(kept, message);

        new MessageIntake(() -> key).take(mailbox);
        Files.writeString(kept, "{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\"}");

        assertThat(mailbox.incoming().resolve("m-1.json")).content().contains("ROLE_AGENT");
    }

    @Test
    void aLinkTheAgentPlacedIsNotFollowed(@TempDir final Path dir) throws IOException {

        // Followed, the host read whatever file its account can and signed it as the agent's
        // message.
        final Mailbox mailbox = mailbox(dir);
        final Path secret =
                Files.writeString(
                        dir.resolve("secret.json"), "{\"messageId\":\"s\",\"key\":\"x\"}");
        Files.createSymbolicLink(mailbox.outboxNew().resolve("m-1.json"), secret);

        assertThat(new MessageIntake(() -> key).take(mailbox)).isEmpty();
        assertThat(mailbox.incoming().resolve("m-1.json")).doesNotExist();
    }

    @Test
    void aFileBeyondTheLimitIsNotReadAndStopsNothing(@TempDir final Path dir) throws IOException {

        // Read whole, a sparse file of gigabytes threw OutOfMemoryError and ended messaging on the
        // machine.
        final Mailbox mailbox = mailbox(dir);
        try (java.io.RandomAccessFile huge =
                new java.io.RandomAccessFile(
                        mailbox.outboxNew().resolve("a.json").toFile(), "rw")) {
            huge.setLength(3L * 1024 * 1024 * 1024);
        }
        Files.writeString(mailbox.outboxNew().resolve("b.json"), "{\"messageId\":\"b\"}");

        assertThat(new MessageIntake(() -> key).take(mailbox)).containsExactly("b.json");
        assertThat(mailbox.outboxNew().resolve("a.json")).doesNotExist();
    }

    @Test
    void aNameTooLongToSignBesideItIsHeldAndTheOthersAreStillTaken(@TempDir final Path dir)
            throws IOException {
        // 255 bytes is as long as a file name may be: neither a staging name nor the signature
        // beside it fits, and a task that wrote one must not stop the intake of the others.
        final Mailbox mailbox = mailbox(dir);
        final String longest = "a".repeat(250) + ".json";
        Files.writeString(mailbox.outboxNew().resolve(longest), "{\"messageId\":\"a\"}");
        Files.writeString(mailbox.outboxNew().resolve("b.json"), "{\"messageId\":\"b\"}");

        final MessageIntake intake = new MessageIntake(() -> key);

        assertThat(intake.take(mailbox)).containsExactly("b.json");
        assertThat(mailbox.outboxNew().resolve(longest)).doesNotExist();
        assertThat(mailbox.hold().resolve(longest)).exists();
        assertThat(mailbox.incoming().resolve("b.json.sig")).exists();
    }

    @Test
    void anOutboxTheAgentReplacedWithALinkIsNotReadAndWhatItPointsAtIsLeftAlone(
            @TempDir final Path dir) throws IOException {

        // The box is the task's: replaced by a link, its outbox led the host to read, sign and
        // delete files anywhere its account can.
        final Mailbox mailbox = mailbox(dir);
        final Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("precious.json"), "{\"messageId\":\"p\"}");
        Files.delete(mailbox.outboxNew());
        Files.createSymbolicLink(mailbox.outboxNew(), elsewhere);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new MessageIntake(() -> key).take(mailbox))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link");
        assertThat(elsewhere.resolve("precious.json")).exists();
    }

    /** A profile 2 submission to the review, as a reviewer's run writes it. */
    private static final String SUBMISSION =
            "{\"messageId\":\"m-20\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"data\":"
                    + "{\"version\":\"2.1.0\",\"runs\":[]}}],"
                    + "\"metadata\":{\"to\":\"review\",\"kind\":\"comment\"}}";

    private static MessageIntake uncertified() {
        return new MessageIntake(
                () -> {
                    throw new MessageSigners.Unsigned("no key of its own", false);
                });
    }

    private static List<Path> answers(final Mailbox mailbox) throws IOException {
        try (var answers = Files.list(mailbox.feedback())) {
            return answers.toList();
        }
    }

    @Test
    void anUncertifiedTasksSubmissionToTheReviewGoesOnUnsignedAndIsNeitherHeldNorAnswered(
            @TempDir final Path dir) throws IOException {
        // Its items wait in the gate's holds for the owner account, which is the decision a
        // person takes on an unsigned review; held here, they never reached that decision.
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve("m-20.json"), SUBMISSION);
        final List<MessageDelivery.Held> holds = new java.util.ArrayList<>();
        final MessageIntake intake =
                new MessageIntake(
                        () -> {
                            throw new MessageSigners.Unsigned("no key of its own", false);
                        },
                        holds::add);

        final List<String> taken = intake.take(mailbox);

        assertThat(taken).as("passed on to the filter").containsExactly("m-20.json");
        assertThat(mailbox.incoming().resolve("m-20.json"))
                .as("the host's copy, as the task wrote it")
                .hasContent(SUBMISSION);
        assertThat(mailbox.incoming().resolve("m-20.json.sig"))
                .as("no signature, which no key made")
                .doesNotExist();
        assertThat(mailbox.hold()).as("nothing held in the mailbox").isEmptyDirectory();
        assertThat(holds).as("no hold recorded").isEmpty();
        assertThat(intake.heldUnsigned()).as("nothing held unsigned").isEmpty();
        assertThat(intake.takenUnsigned()).as("taken unsigned").containsExactly("m-20.json");
        assertThat(answers(mailbox)).as("the task is not told it was held").isEmpty();
    }

    @Test
    void anUncertifiedTasksMessageOfAnyOtherKindIsStillHeldAndAnswered(@TempDir final Path dir)
            throws IOException {
        // Only a submission goes on to a decision of the owner account; anything else unsigned
        // would reach a receiver that cannot believe it.
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(
                mailbox.outboxNew().resolve("a.json"),
                SUBMISSION.replace("\"to\":\"review\"", "\"to\":\"alice\""));
        Files.writeString(
                mailbox.outboxNew().resolve("b.json"),
                "{\"messageId\":\"b\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"ok\"}],"
                        + "\"metadata\":{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"t-1\"}}");
        Files.writeString(
                mailbox.outboxNew().resolve("c.json"),
                SUBMISSION.replace("\"to\":\"review\"", "\"to\":[\"review\",\"alice\"]"));
        final MessageIntake intake = uncertified();

        final List<String> taken = intake.take(mailbox);

        assertThat(taken).as("nothing passed on").isEmpty();
        assertThat(mailbox.incoming()).as("nothing for the filter").isEmptyDirectory();
        assertThat(intake.heldUnsigned())
                .as("each held, marked unsigned")
                .extracting(MessageDelivery.Held::message, MessageDelivery.Held::mark)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a.json", TrustMark.UNSIGNED),
                        org.assertj.core.groups.Tuple.tuple("b.json", TrustMark.UNSIGNED),
                        org.assertj.core.groups.Tuple.tuple("c.json", TrustMark.UNSIGNED));
        assertThat(answers(mailbox)).as("the task is told of each").hasSize(3);
    }

    @Test
    void aSignatureACrashLeftUnderTheSameNameNeverTravelsWithAnUnsignedSubmission(
            @TempDir final Path dir) throws IOException {
        // Carried along by the filter, it would make the unsigned item show signed.
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.incoming().resolve("m-20.json.sig"), "left by a crash");
        Files.writeString(mailbox.outboxNew().resolve("m-20.json"), SUBMISSION);

        uncertified().take(mailbox);

        assertThat(mailbox.incoming().resolve("m-20.json")).as("passed on").exists();
        assertThat(mailbox.incoming().resolve("m-20.json.sig"))
                .as("no signature beside it")
                .doesNotExist();
    }

    @Test
    void aSubmissionOfATaskWhoseVaultIsLockedStillWaitsInItsOutbox(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve("m-20.json"), SUBMISSION);

        final List<String> taken =
                new MessageIntake(
                                () -> {
                                    throw new MessageSigners.Unsigned("the vault is locked", true);
                                })
                        .take(mailbox);

        assertThat(taken).as("nothing taken").isEmpty();
        assertThat(mailbox.outboxNew().resolve("m-20.json"))
                .as("taken signed once the vault opens")
                .exists();
        assertThat(mailbox.incoming()).as("nothing for the filter").isEmptyDirectory();
    }
}
