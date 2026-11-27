package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Egress;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task that reads another task's work, as {@link MessageDispatch} and {@link MessageRelease}
 * treat its messages: to its review as any task's, to anyone else never.
 */
class ReadingTaskDispatchTest {

    private static final String CONTAINER = "porelo-p-t";

    private static final TaskIdentity T =
            new TaskIdentity(TaskIdentity.Format.LEGACY, "p", "t", CONTAINER, "c0ffee01");

    private static final MessageDispatch.Tasks TASKS =
            container -> CONTAINER.equals(container) ? T : null;

    /** Only this mailbox's task reads another task's work. */
    private static final MessageDispatch.Readers READER = CONTAINER::equals;

    private static final ReviewerComments NO_SUBMISSIONS =
            (task, document, unsigned) -> {
                throw new AssertionError("no submission here");
            };

    private final Mail peers =
            new Mail(
                    List.of(
                            new Mail.Peer(
                                    "reviewer",
                                    "local:/somewhere/mail/porelo-p-r/inbound",
                                    "vouched")));

    /** Records each reply it is given and takes it. */
    private static final class Review implements ReviewReplies {

        private final List<List<Object>> taken = new ArrayList<>();

        @Override
        public Optional<String> take(
                final TaskIdentity task, final String thread, final String text) {
            taken.add(List.of(task, thread, text));
            return Optional.empty();
        }
    }

    private Mailbox accepted(final Path dir, final String json) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve(CONTAINER));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve("m-1.json"), json);
        Files.writeString(
                mailbox.accepted().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(
                mailbox.feedback().resolve("receipt-m-1.json"),
                "{\"messageId\":\"r-m-1\",\"metadata\":{\"inReplyToMessageId\":\"m-1\"},"
                        + "\"parts\":[{\"data\":{\"decision\":\"approved\",\"wouldReject\":false}}]}");
        return mailbox;
    }

    /** The project's file lets every peer it names be sent to unwatched. */
    private Moderation allowing(final Mailbox mailbox) {
        return new Moderation(
                mailbox.root().resolveSibling("moderation-p.json"),
                new Project(
                        "p",
                        "a project whose agents may talk unwatched",
                        SecurityClass.GUARDED,
                        "ubuntu:24.04",
                        null,
                        null,
                        Limits.defaults(),
                        Egress.none(),
                        Project.DEFAULT_PACKAGE_SOURCES,
                        new Mail(
                                peers.peers(),
                                Map.of(),
                                false,
                                new Mail.Rules(null, null, Mail.ALLOW))));
    }

    /** The answers the host wrote into the task's feedback, apart from the filter's receipt. */
    private static List<Map<?, ?>> bounces(final Mailbox mailbox) throws IOException {
        try (Stream<Path> files = Files.list(mailbox.feedback())) {
            final List<Map<?, ?>> found = new ArrayList<>();
            for (final Path file :
                    files.filter(path -> !path.getFileName().toString().startsWith("receipt-"))
                            .toList()) {
                found.add((Map<?, ?>) Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            }
            return found;
        }
    }

    /** What waits in any transport's queue of the mailbox. */
    private static List<Path> queued(final Mailbox mailbox) throws IOException {
        final Path queues = mailbox.root().resolve("queue");
        return Files.exists(queues) ? everythingUnder(queues) : List.of();
    }

    private static List<Path> everythingUnder(final Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aReadersSignedMessageToAConfiguredPeerIsRefusedAndThePeersQueueStaysEmpty(
            @TempDir final Path dir) throws IOException {
        // Allowed by the project, signed and passed by the filter, a reader's message carried
        // another task's unreviewed work to a transport that leaves the machine.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "{\"messageId\":\"m-1\",\"parts\":[{\"text\":\"the secret diff\"}],"
                                + "\"metadata\":{\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(new Review(), NO_SUBMISSIONS, TASKS, READER)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(outcome.queued()).as("nothing queued").isEmpty();
        assertThat(outcome.held()).as("nothing held for a person to send on").isEmpty();
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
        assertThat(outcome.refused())
                .singleElement()
                .satisfies(
                        refused ->
                                assertThat(refused.reason())
                                        .as("the rule it was refused by")
                                        .contains("readsAnothersWork", "review"));
        assertThat(bounces(mailbox))
                .as("the task is told, as a refused message's task is")
                .singleElement()
                .satisfies(
                        bounce -> {
                            assertThat(Json.write(bounce))
                                    .contains("readsAnothersWork", "refused", "m-1")
                                    .as("and nothing of the message is quoted")
                                    .doesNotContain("the secret diff");
                        });
        assertThat(everythingUnder(mailbox.root()))
                .as("nothing of the message is kept for a later sending")
                .noneMatch(
                        file ->
                                file.getFileName().toString().startsWith("m-1.json")
                                        && !file.startsWith(mailbox.feedback()));
    }

    @Test
    void aReadersReplyStillReachesItsReview(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\","
                                + "\"parts\":[{\"text\":\"Checked: the bound holds.\"}],"
                                + "\"metadata\":{\"to\":\"review\",\"kind\":\"comment\","
                                + "\"thread\":\"th-1\"}}");
        final Review review = new Review();

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS, READER)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(review.taken).containsExactly(List.of(T, "th-1", "Checked: the bound holds."));
        assertThat(outcome.refused()).as("nothing refused").isEmpty();
        assertThat(bounces(mailbox)).as("nobody told of a refusal").isEmpty();
    }

    @Test
    void aTaskAtItsOwnWorkStillSendsToAPeer(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox =
                accepted(dir, "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(new Review(), NO_SUBMISSIONS, TASKS, container -> false)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(outcome.queued()).containsEntry("m-1.json", "local");
        assertThat(mailbox.queueActive("local").resolve("m-1.json")).exists();
    }

    @Test
    void aReadersMessageHeldBeforeAndReleasedByAPersonIsStillRefused(@TempDir final Path dir)
            throws IOException {
        // Held while the task was at its own work, then released: a release lets a message past
        // a hold, never past the rule of a task that reads another task's work.
        final Mailbox mailbox = new Mailbox(dir.resolve(CONTAINER));
        mailbox.create();
        Files.createDirectories(mailbox.hold());
        Files.writeString(
                mailbox.hold().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.hold().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        assertThat(
                        new MessageRelease()
                                .reading(true)
                                .decide(mailbox, "m-1", false, peers)
                                .outcome())
                .as("a person released it")
                .isEqualTo(MessageRelease.Outcome.RELEASED);

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(new Review(), NO_SUBMISSIONS, TASKS, READER)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(outcome.queued()).as("nothing queued").isEmpty();
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
        assertThat(outcome.refused())
                .singleElement()
                .satisfies(refused -> assertThat(refused.reason()).contains("readsAnothersWork"));
    }

    @Test
    void aReadersMessageToItsReviewAndAPeerIsRefused(@TempDir final Path dir) throws IOException {
        // Its review among the addressees took none of the rest off the machine.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "{\"messageId\":\"m-1\",\"parts\":[{\"text\":\"the secret diff\"}],"
                                + "\"metadata\":{\"to\":[\"review\",\"reviewer\"]}}");
        final Review review = new Review();

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS, READER)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(outcome.queued()).as("nothing queued").isEmpty();
        assertThat(outcome.held()).as("nothing held for a person to send on").isEmpty();
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
        assertThat(review.taken).as("nor taken by its review").isEmpty();
        assertThat(outcome.refused())
                .singleElement()
                .satisfies(refused -> assertThat(refused.reason()).contains("readsAnothersWork"));
    }

    @Test
    void aDispatchNotToldWhoReadsTakesEverySenderAsAReader(@TempDir final Path dir)
            throws IOException {
        // Told nothing, a dispatch took every task for one at its own work, and a reader's message
        // left the machine.
        final Mailbox mailbox =
                accepted(dir, "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(new Review(), NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, peers, allowing(mailbox));

        assertThat(outcome.queued()).as("nothing queued").isEmpty();
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
        assertThat(outcome.refused())
                .singleElement()
                .satisfies(refused -> assertThat(refused.reason()).contains("readsAnothersWork"));
    }

    @Test
    void aPassNotToldWhoReadsTakesEverySenderAsAReader(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox =
                accepted(dir, "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");
        final org.fuin.porelo.testing.FakeCommandRunner runner =
                new org.fuin.porelo.testing.FakeCommandRunner();
        final Path filter = dir.resolve(PoreloPaths.MESSAGE_FILTER);
        runner.answering(PoreloPaths.MESSAGE_FILTER, "");
        final org.fuin.porelo.vault.SigningKey key =
                org.fuin.porelo.vault.SigningKey.generate("host");

        final MessagePass.Report report =
                new MessagePass(
                                runner,
                                filter,
                                new TransportDirectory(List.of(dir.resolve("none"))),
                                org.fuin.porelo.wire.LimitFactors.DEFAULT)
                        .run(mailbox, peers, () -> key, PeerKeys.NONE, allowing(mailbox));

        assertThat(report.filtered().ran()).as("the filter ran").isTrue();
        assertThat(report.dispatched().queued()).as("nothing queued").isEmpty();
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
        assertThat(report.dispatched().refused())
                .singleElement()
                .satisfies(refused -> assertThat(refused.reason()).contains("readsAnothersWork"));
    }

    @Test
    void aMessageTheFilterRefusedIsNotDeliveredToAPeerForAReaderWhoeverDecides(
            @TempDir final Path dir) throws IOException {
        // Sent after all by a person, the message went straight to the transport's queue,
        // past the dispatch that refuses it.
        final Mailbox mailbox = new Mailbox(dir.resolve(CONTAINER));
        mailbox.create();
        Files.createDirectories(mailbox.rejected());
        Files.writeString(
                mailbox.rejected().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                mailbox.rejected().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");

        final MessageRelease.Result result =
                new MessageRelease().reading(true).decide(mailbox, "m-1", false, peers);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.NOT_DELIVERABLE);
        assertThat(result.detail()).contains("readsAnothersWork");
        assertThat(queued(mailbox)).as("the peer's queue").isEmpty();
    }
}
