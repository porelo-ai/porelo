package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Egress;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test for {@link MessageDispatch}. */
class MessageDispatchTest {

    /** No message here addresses the review. */
    private static final ReviewReplies NO_REVIEW =
            (task, thread, text) -> java.util.Optional.of("no review here");

    private static final MessageDispatch.Tasks NO_TASKS = container -> null;

    /** No message here is a submission; one taken as such fails the test. */
    private static final ReviewerComments NO_SUBMISSIONS =
            (task, document) -> {
                throw new AssertionError("no submission here");
            };

    private final Mail mail =
            new Mail(
                    List.of(
                            new Mail.Peer(
                                    "reviewer",
                                    "local:/somewhere/mail/porelo-p-r/inbound",
                                    "vouched"),
                            new Mail.Peer("ops", "mail:ops@example.org", "external")));

    /** A project whose file gives every peer it names this mode. */
    private Project sending(final String mode) {
        return new Project(
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
                        mail.peers(), java.util.Map.of(), false, new Mail.Rules(null, null, mode)));
    }

    /**
     * Sending at all needs the project's file to allow it, so every queueing test starts with one
     * that does.
     */
    private Moderation allowing(final Mailbox mailbox) {
        return new Moderation(
                mailbox.root().resolveSibling("moderation-p.json"), sending(Mail.ALLOW));
    }

    private Mailbox accepted(final Path dir, final String name, final String json)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve(name), json);
        Files.writeString(
                mailbox.accepted().resolve(name + ".sig"), "-----BEGIN SSH SIGNATURE-----");
        // The filter's clean receipt, as it answers every message it checked.
        final java.util.regex.Matcher id =
                java.util.regex.Pattern.compile("\"messageId\":\"([^\"]+)\"").matcher(json);
        if (id.find()) {
            Files.writeString(
                    mailbox.feedback().resolve("receipt-" + name),
                    "{\"messageId\":\"r-"
                            + id.group(1)
                            + "\",\"metadata\":{\"inReplyToMessageId\":\""
                            + id.group(1)
                            + "\"},\"parts\":[{\"data\":"
                            + "{\"decision\":\"approved\",\"wouldReject\":false}}]}");
        }
        return mailbox;
    }

    @Test
    void queues_a_message_for_the_transport_its_peer_uses(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-1.json",
                        "{\"messageId\":\"m-1\",\"metadata\":{\"kind\":\"question\",\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, allowing(mailbox));

        assertThat(outcome.queued()).containsEntry("m-1.json", "local");
        assertThat(mailbox.queueActive("local").resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.sig")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.to"))
                .hasContent("/somewhere/mail/porelo-p-r/inbound");
        assertThat(mailbox.accepted()).isEmptyDirectory();
    }

    /** The default, and the largest thing a person controls: nothing they have not read leaves. */
    @Test
    void holds_a_message_for_a_peer_nobody_has_decided_about(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-5.json",
                        "{\"messageId\":\"m-5\",\"metadata\":{\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("waits for a person"));
        assertThat(mailbox.hold().resolve("m-5.json")).exists();
    }

    @Test
    void holds_everything_for_a_peer_a_person_held(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-6.json",
                        "{\"messageId\":\"m-6\",\"metadata\":{\"to\":\"reviewer\"}}");
        final Moderation moderation = allowing(mailbox);
        moderation.set("reviewer", true, null, sending(Mail.ALLOW));

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, moderation);

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains("held until a person releases it"));
    }

    @Test
    void holds_a_message_for_a_peer_this_project_may_not_address(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-2.json",
                        "{\"messageId\":\"m-2\",\"metadata\":{\"to\":\"somebody-else\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains("may not address 'somebody-else'"));
        assertThat(mailbox.hold().resolve("m-2.json")).exists();
    }

    @Test
    void holds_a_message_that_addresses_nobody(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, undecided(mailbox))
                                .held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("addresses nobody"));
    }

    /**
     * Sending to several peers is a copy and a receipt per copy. Refusing it in words beats
     * delivering to the first name and saying nothing about the rest.
     */
    @Test
    void refuses_to_guess_when_a_message_addresses_two_peers(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-4.json",
                        "{\"messageId\":\"m-4\",\"metadata\":{\"to\":[\"reviewer\",\"ops\"]}}");

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, undecided(mailbox))
                                .held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("not built"));
        assertThat(mailbox.queueActive("local")).doesNotExist();
    }

    /** The project's decisions, beside the mailbox and outside it, as the host keeps them. */
    @Test
    void
            a_message_a_person_released_goes_although_the_peer_is_undecided_but_never_to_a_refused_peer(
                    @TempDir final Path dir) throws IOException {

        // Found: released, then held again by the same mode at the next pass, for ever.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-9.json",
                        "{\"messageId\":\"m-9\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                mailbox.accepted().resolve("m-9.json" + MessageDispatch.RELEASED_SUFFIX), "");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).containsEntry("m-9.json", "local");
        assertThat(mailbox.accepted()).as("the mark goes with the release").isEmptyDirectory();

        final Mailbox refusing =
                accepted(
                        dir.resolve("other"),
                        "m-10.json",
                        "{\"messageId\":\"m-10\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                refusing.accepted().resolve("m-10.json" + MessageDispatch.RELEASED_SUFFIX), "");
        final Moderation denying =
                new Moderation(
                        refusing.root().resolveSibling("moderation-p.json"), sending(Mail.DENY));
        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(refusing, mail, denying)
                                .queued())
                .isEmpty();
    }

    /**
     * The operator in walk 10, 2026-10-04: a release is needed only when the filter flags something
     * as a problem.
     */
    @Test
    void aMessageTheReportingFilterWouldHaveRefusedWaitsForAPersonWhateverItsPeersMode(
            @TempDir final Path dir) throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-7.json",
                        "{\"messageId\":\"m-7\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                mailbox.feedback().resolve("answer-m-7.json"),
                "{\"messageId\":\"a-7\","
                        + "\"metadata\":{\"inReplyToMessageId\":\"m-7\"},\"parts\":[{\"text\":\"approved\"},"
                        + "{\"data\":{\"decision\":\"approved\",\"wouldReject\":true,\"decidingRule\":\"ENCODING/HEX\","
                        + "\"findings\":[{\"redactedExcerpt\":\"2c8a****\"}]}}]}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                        .dispatch(mailbox, mail, allowing(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains(
                                                "the filter would have refused it: ENCODING/HEX"));
    }

    @Test
    void aMessageAPersonReleasedGoesThoughTheReportingFilterFlaggedIt(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-8.json",
                        "{\"messageId\":\"m-8\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                mailbox.feedback().resolve("answer-m-8.json"),
                "{\"messageId\":\"a-8\","
                        + "\"metadata\":{\"inReplyToMessageId\":\"m-8\"},\"parts\":[{\"data\":{\"wouldReject\":true}}]}");
        Files.writeString(
                mailbox.accepted().resolve("m-8.json" + MessageDispatch.RELEASED_SUFFIX), "");

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, allowing(mailbox))
                                .queued())
                .containsKey("m-8.json");
    }

    @Test
    void aMessageWhoseAnswerFromTheFilterIsMissingWaitsForAPerson(@TempDir final Path dir)
            throws IOException {

        // Fail closed (2026-10-04): a lost answer must never read as clean.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-11.json",
                        "{\"messageId\":\"m-11\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.delete(mailbox.feedback().resolve("receipt-m-11.json"));

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, allowing(mailbox))
                                .held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("answer to it is missing"));
    }

    @Test
    void anAnswerTheAgentRemovedFromItsBoxStillHoldsWhatTheFilterFlagged(@TempDir final Path dir)
            throws IOException {

        // 2026-10-04: the agent can empty its own inbox, so only the host's copy is asked.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-12.json",
                        "{\"messageId\":\"m-12\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(
                mailbox.feedback().resolve("receipt-m-12.json"),
                "{\"messageId\":\"r-12\","
                        + "\"metadata\":{\"inReplyToMessageId\":\"m-12\"},\"parts\":[{\"data\":{\"wouldReject\":true}}]}");
        new BounceDelivery().deliver(mailbox);
        try (var delivered = Files.list(mailbox.inboxNew())) {
            for (final Path each : delivered.toList()) {
                Files.delete(each);
            }
        }

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, allowing(mailbox))
                                .held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("would have refused"));
    }

    @Test
    void aMessageWhoseIdCannotBeReadWaitsForAPerson(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-13.json", "{\"metadata\":{\"to\":\"reviewer\"}}");

        assertThat(
                        new MessageDispatch(NO_REVIEW, NO_SUBMISSIONS, NO_TASKS)
                                .dispatch(mailbox, mail, allowing(mailbox))
                                .held())
                .singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("id cannot be read"));
    }

    /** The task {@code t} of project {@code p}, as the host recorded it for its mailbox. */
    private static final MessageDispatch.Tasks OWNED =
            container ->
                    "porelo-p-t".equals(container)
                            ? new TaskIdentity(
                                    TaskIdentity.Format.LEGACY, "p", "t", container, "c0ffee01")
                            : null;

    /** A profile 2 submission, written compact so its bytes can be compared as handed on. */
    private static final String SUBMISSION =
            "{\"version\":\"2.1.0\",\"properties\":{\"porelo/profileVersion\":\"2\","
                    + "\"porelo/documentKind\":\"submission\"},\"runs\":[{\"results\":["
                    + "{\"message\":{\"text\":\"Refuse a blank password before the lookup.\"},"
                    + "\"locations\":[{\"physicalLocation\":{\"region\":"
                    + "{\"startLine\":27,\"endLine\":28}}}]}]}]}";

    private static String toReview(final String metadata, final String parts) {
        return "{\"messageId\":\"m-20\",\"role\":\"ROLE_AGENT\",\"parts\":"
                + parts
                + ",\"metadata\":{\"to\":\"review\",\"kind\":\"comment\""
                + metadata
                + "}}";
    }

    /** Records each submission it is given, and refuses with the rule it was made with, if any. */
    private static final class Submissions implements ReviewerComments {
        final List<String> tasks = new ArrayList<>();
        final List<String> documents = new ArrayList<>();
        private final @Nullable String rule;

        Submissions(final @Nullable String rule) {
            this.rule = rule;
        }

        @Override
        public void take(final String task, final byte[] document) throws ProfileRefused {
            tasks.add(task);
            documents.add(new String(document, StandardCharsets.UTF_8));
            if (rule != null) {
                throw new ProfileRefused(rule);
            }
        }
    }

    /** What the host answered the task, apart from the filter's receipt, as text. */
    private static List<String> bounces(final Mailbox mailbox) throws IOException {
        try (Stream<Path> files = Files.list(mailbox.feedback())) {
            final List<String> found = new ArrayList<>();
            for (final Path file :
                    files.filter(path -> !path.getFileName().toString().startsWith("receipt-"))
                            .filter(path -> !path.getFileName().toString().startsWith("answer-"))
                            .toList()) {
                found.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            return found;
        }
    }

    @Test
    void aSubmissionReachesTheReviewAsTheMailboxsTaskWhateverItsFromSays(@TempDir final Path dir)
            throws IOException {
        // A task never names a producer: the review is told the mailbox, never metadata.from.
        final Mailbox mailbox =
                accepted(
                        dir,
                        "m-20.json",
                        toReview(
                                ",\"from\":\"porelo-p-other\"", "[{\"data\":" + SUBMISSION + "}]"));
        final Submissions review = new Submissions(null);

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, review, OWNED)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.tasks).as("the mailbox's task").containsExactly("porelo-p-t");
        assertThat(review.documents)
                .as("the data part's document, compact, its numbers as written")
                .containsExactly(SUBMISSION);
        assertThat(outcome.queued()).as("no transport carries it").isEmpty();
        assertThat(outcome.held()).as("nothing held").isEmpty();
        assertThat(outcome.refused()).as("nothing refused").isEmpty();
        assertThat(mailbox.accepted()).as("taken from the mailbox").isEmptyDirectory();
        assertThat(bounces(mailbox)).as("a taken submission is not answered").isEmpty();
    }

    @Test
    void aSubmissionTheFilterFlaggedIsHeldAndNeverReachesTheReview(@TempDir final Path dir)
            throws IOException {
        // What the filter only reported waits for a person, a submission as any message.
        final Mailbox mailbox =
                accepted(dir, "m-20.json", toReview("", "[{\"data\":" + SUBMISSION + "}]"));
        Files.writeString(
                mailbox.feedback().resolve("answer-m-20.json"),
                "{\"messageId\":\"a-20\",\"metadata\":{\"inReplyToMessageId\":\"m-20\"},"
                        + "\"parts\":[{\"data\":{\"decision\":\"approved\",\"wouldReject\":true,"
                        + "\"decidingRule\":\"ENCODING/HEX\"}}]}");
        final Submissions review = new Submissions(null);

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, review, OWNED)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.tasks).as("never handed to the review").isEmpty();
        assertThat(outcome.held())
                .as("held for a person")
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .as("the filter's rule")
                                        .contains("ENCODING/HEX"));
        assertThat(mailbox.hold().resolve("m-20.json")).as("in the hold").exists();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misshapen")
    void aCommentToTheReviewOfAnyOtherShapeBouncesAndReachesNoReview(
            final String shape, final String message, @TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-20.json", message);
        final Submissions review = new Submissions(null);

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, review, OWNED)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.tasks).as("never handed to the review").isEmpty();
        assertThat(outcome.refused()).as("refused for good").hasSize(1);
        assertThat(outcome.queued()).as("no transport carries it").isEmpty();
        assertThat(mailbox.rejected().resolve("m-20.json")).as("with the rejected").exists();
        assertThat(bounces(mailbox))
                .as("the task is told")
                .singleElement()
                .satisfies(
                        bounce ->
                                assertThat(bounce)
                                        .as("a refusal")
                                        .contains("\"verdict\":\"refused\""));
    }

    static Stream<Arguments> misshapen() {
        final String data = "{\"data\":" + SUBMISSION + "}";
        return Stream.of(
                Arguments.of(
                        "no data part and no thread",
                        toReview("", "[{\"text\":\"Refuse a blank password.\"}]")),
                Arguments.of(
                        "a data part and metadata.thread",
                        toReview(",\"thread\":\"th-1\"", "[" + data + "]")),
                Arguments.of("two data parts", toReview("", "[" + data + "," + data + "]")),
                Arguments.of(
                        "a text part and a data part",
                        toReview("", "[{\"text\":\"See the data.\"}," + data + "]")),
                Arguments.of(
                        "text and data in one part",
                        toReview("", "[{\"text\":\"See the data.\",\"data\":" + SUBMISSION + "}]")),
                Arguments.of(
                        "a data part that is no document",
                        toReview("", "[{\"data\":\"a string\"}]")));
    }

    @Test
    void aSubmissionTheReviewRefusesBouncesNamingItsRule(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(dir, "m-20.json", toReview("", "[{\"data\":" + SUBMISSION + "}]"));
        final Submissions review = new Submissions("run: none for the task");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(NO_REVIEW, review, OWNED)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.tasks).as("handed to the review").containsExactly("porelo-p-t");
        assertThat(outcome.refused())
                .as("refused for good, naming the rule")
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .as("the rule")
                                        .endsWith("run: none for the task"));
        assertThat(mailbox.rejected().resolve("m-20.json")).as("with the rejected").exists();
        assertThat(bounces(mailbox))
                .as("the task is told the rule")
                .singleElement()
                .satisfies(
                        bounce ->
                                assertThat(bounce)
                                        .as("the rule in the answer")
                                        .contains("run: none for the task"));
    }

    private static Moderation undecided(final Mailbox mailbox) {
        return new Moderation(mailbox.root().resolveSibling("moderation-p.json"));
    }
}
