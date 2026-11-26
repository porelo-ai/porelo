package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A task's reply to a review thread, as {@link MessageDispatch} routes it: to {@link
 * ReviewReplies}, as the task whose mailbox it came through, and never to a transport.
 */
class ReviewReplyDispatchTest {

    /** The mailbox's own name, which the host keeps for the task {@code t} of project {@code p}. */
    private static final String CONTAINER = "porelo-p-t";

    /** The task {@code t} of project {@code p}, as the host recorded it for that mailbox. */
    private static final TaskIdentity T =
            new TaskIdentity(TaskIdentity.Format.LEGACY, "p", "t", CONTAINER, "c0ffee01");

    /** The host's record of whose mailbox is whose: only this mailbox belongs to a task. */
    private static final MessageDispatch.Tasks TASKS =
            container -> CONTAINER.equals(container) ? T : null;

    /** No message here is a submission; one taken as such fails the test. */
    private static final ReviewerComments NO_SUBMISSIONS =
            (task, document, unsigned) -> {
                throw new AssertionError("no submission here");
            };

    private final Mail mail =
            new Mail(List.of(new Mail.Peer("ops", "mail:ops@example.org", "external")));

    /** Records each reply it is given, and answers the refusal it was made with, or none. */
    private static final class StandIn implements ReviewReplies {

        private final List<List<Object>> taken = new ArrayList<>();

        private final Optional<String> answer;

        StandIn(final Optional<String> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<String> take(
                final TaskIdentity task, final String thread, final String text) {
            taken.add(List.of(task, thread, text));
            return answer;
        }
    }

    private static String reply(final String metadata, final String parts) {
        return "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\",\"parts\":"
                + parts
                + ",\"metadata\":"
                + metadata
                + "}";
    }

    private static final String TEXT = "[{\"text\":\"Fixed: the bound is checked first now.\"}]";

    private Mailbox accepted(final Path dir, final String json, final boolean flagged)
            throws IOException {
        return accepted(dir, CONTAINER, json, flagged);
    }

    private Mailbox accepted(
            final Path dir, final String container, final String json, final boolean flagged)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve(container));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve("m-1.json"), json);
        Files.writeString(
                mailbox.accepted().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        // The filter's receipt, as it answers every message it checked.
        Files.writeString(
                mailbox.feedback().resolve("receipt-m-1.json"),
                "{\"messageId\":\"r-m-1\",\"metadata\":{\"inReplyToMessageId\":\"m-1\"},"
                        + "\"parts\":[{\"data\":{\"decision\":\"approved\",\"wouldReject\":"
                        + flagged
                        + (flagged ? ",\"decidingRule\":\"ENCODING/HEX\"" : "")
                        + "}}]}");
        return mailbox;
    }

    private static Moderation undecided(final Mailbox mailbox) {
        return new Moderation(mailbox.root().resolveSibling("moderation-p.json"));
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

    /** The answer's data part, its keys as text. */
    private static Map<String, Object> verdict(final Map<?, ?> bounce) {
        final Map<String, Object> data = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) ((Map<?, ?>) ((List<?>) bounce.get("parts")).get(1)).get("data"))
                .forEach((key, value) -> data.put(String.valueOf(key), value));
        return data;
    }

    @Test
    void aReplyReachesTheReviewAsTheTaskWhoseMailboxItCameThroughWhateverItsFromSays(
            @TempDir final Path dir) throws IOException {
        // The thread is the task's choice; who the task is never is. A message saying it came
        // from another task must not write into that task's threads.
        final Mailbox mailbox =
                accepted(
                        dir,
                        reply(
                                "{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-1\","
                                        + "\"from\":\"other\"}",
                                TEXT),
                        false);
        final StandIn review = new StandIn(Optional.empty());

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken)
                .containsExactly(List.of(T, "th-1", "Fixed: the bound is checked first now."));
        assertThat(outcome.queued()).as("no transport carries a reply").isEmpty();
        assertThat(outcome.held()).isEmpty();
        assertThat(mailbox.root().resolve("queue")).doesNotExist();
        assertThat(mailbox.accepted()).isEmptyDirectory();
        assertThat(bounces(mailbox)).as("a kept reply is not answered").isEmpty();
    }

    /**
     * The reply exactly as the message filter's own test passes it: its message id, a thread's UUID
     * and the metadata the guide tells the task to send. What the filter lets through must be what
     * the review takes, or every reply would stop between the two.
     */
    @Test
    void theReplyTheFilterIsTestedToPassIsTheOneTheReviewTakes(@TempDir final Path dir)
            throws IOException {
        final String thread = "6f1a2b3c-4d5e-4f60-8a1b-2c3d4e5f6a7b";
        final Mailbox mailbox =
                accepted(
                        dir,
                        "{\"messageId\":\"0b1c7a52-5d0e-4c43-9a55-3f0d2f6f1e11\",\"role\":\"ROLE_AGENT\","
                                + "\"parts\":[{\"text\":\"The bound is checked first now.\"}],"
                                + "\"metadata\":{\"to\":\"review\",\"kind\":\"comment\","
                                + "\"thread\":\""
                                + thread
                                + "\"}}",
                        false);
        // The filter's receipt answers the message by its own id.
        final Path receipt = mailbox.feedback().resolve("receipt-m-1.json");
        Files.writeString(
                receipt,
                Files.readString(receipt)
                        .replace(
                                "\"inReplyToMessageId\":\"m-1\"",
                                "\"inReplyToMessageId\":\"0b1c7a52-5d0e-4c43-9a55-3f0d2f6f1e11\""));
        final StandIn review = new StandIn(Optional.empty());

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken)
                .containsExactly(List.of(T, thread, "The bound is checked first now."));
        assertThat(outcome.held()).isEmpty();
        assertThat(mailbox.accepted()).isEmptyDirectory();
        assertThat(bounces(mailbox)).isEmpty();
    }

    @Test
    void tasksOfTheSameNameInTwoProjectsReachTheReviewAsTheIdentitiesTheirMailboxesWereRecordedWith(
            @TempDir final Path dir) throws IOException {
        // A task's name is unique only in its project: the review must be told the project and
        // the container recorded for the mailbox, or a reply of one project's "fix" could answer
        // a thread of the other's, or of a later task started under that name.
        final TaskIdentity alpha =
                new TaskIdentity(
                        TaskIdentity.Format.ENCODED, "alpha", "fix", "porelo-alpha-fix", "aa11");
        final TaskIdentity beta =
                new TaskIdentity(
                        TaskIdentity.Format.ENCODED, "beta", "fix", "porelo-beta-fix", "bb22");
        final MessageDispatch.Tasks tasks =
                container ->
                        switch (container) {
                            case "porelo-alpha-fix" -> alpha;
                            case "porelo-beta-fix" -> beta;
                            default -> null;
                        };
        final String json =
                reply("{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-1\"}", TEXT);
        final Mailbox first = accepted(dir.resolve("a"), "porelo-alpha-fix", json, false);
        final Mailbox second = accepted(dir.resolve("b"), "porelo-beta-fix", json, false);
        final StandIn review = new StandIn(Optional.empty());

        new MessageDispatch(review, NO_SUBMISSIONS, tasks).dispatch(first, mail, undecided(first));
        new MessageDispatch(review, NO_SUBMISSIONS, tasks)
                .dispatch(second, mail, undecided(second));

        assertThat(review.taken).extracting(taken -> taken.get(0)).containsExactly(alpha, beta);
        assertThat(review.taken)
                .extracting(taken -> (TaskIdentity) taken.get(0))
                .extracting(TaskIdentity::project, TaskIdentity::recordedId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("alpha", "aa11"),
                        org.assertj.core.groups.Tuple.tuple("beta", "bb22"));
    }

    @Test
    void aReplyTheReportingFilterFlaggedWaitsForAPersonAndReachesNoReview(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        reply("{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-1\"}", TEXT),
                        true);
        final StandIn review = new StandIn(Optional.empty());

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken).isEmpty();
        assertThat(outcome.held())
                .singleElement()
                .satisfies(
                        held ->
                                assertThat(held.reason())
                                        .contains(
                                                "the filter would have refused it: ENCODING/HEX"));
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
    }

    @Test
    void aReplyTheReviewRefusesBouncesWithTheRefusalsWordsAndNeverItsText(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        reply("{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-9\"}", TEXT),
                        false);
        final StandIn review =
                new StandIn(Optional.of("NoSuchThread: this task's push has no thread 'th-9'"));

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken).hasSize(1);
        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).as("a refusal is final, nothing a person releases").isEmpty();
        assertThat(mailbox.hold().resolve("m-1.json")).doesNotExist();
        assertThat(mailbox.accepted()).isEmptyDirectory();
        assertThat(bounces(mailbox))
                .singleElement()
                .satisfies(
                        bounce -> {
                            assertThat(verdict(bounce))
                                    .containsEntry("verdict", "refused")
                                    .containsEntry("rejectedMessageId", "m-1");
                            assertThat((String) verdict(bounce).get("reason"))
                                    .contains("NoSuchThread: this task's push has no thread 'th-9'")
                                    .doesNotContain("bound is checked");
                        });
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of(
                        "no thread",
                        reply("{\"to\":\"review\",\"kind\":\"comment\"}", TEXT),
                        "metadata.thread"),
                Arguments.of(
                        "another kind",
                        reply(
                                "{\"to\":\"review\",\"kind\":\"question\",\"thread\":\"th-1\"}",
                                TEXT),
                        "metadata.kind"),
                Arguments.of(
                        "a data part",
                        reply(
                                "{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-1\"}",
                                "[{\"text\":\"See the report.\"},{\"data\":{\"version\":\"2.1.0\"}}]"),
                        "no metadata.thread and exactly one part, a data part"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aReplyOfAnotherShapeBouncesWithItsReasonAndReachesNoReview(
            final String what, final String json, final String reason, @TempDir final Path dir)
            throws IOException {
        // A reply is words alone, a submission a document alone; a reply without its thread
        // answers nothing.
        final Mailbox mailbox = accepted(dir, json, false);
        final StandIn review = new StandIn(Optional.empty());

        final MessageDispatch.Outcome outcome =
                new MessageDispatch(review, NO_SUBMISSIONS, TASKS)
                        .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken).as(what).isEmpty();
        assertThat(outcome.queued()).isEmpty();
        assertThat(mailbox.accepted()).isEmptyDirectory();
        assertThat(bounces(mailbox))
                .singleElement()
                .satisfies(
                        bounce -> {
                            assertThat(verdict(bounce)).containsEntry("verdict", "refused");
                            assertThat((String) verdict(bounce).get("reason")).contains(reason);
                        });
    }

    @Test
    void aReplyFromAMailboxNoTaskOwnsBouncesAndReachesNoReview(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox =
                accepted(
                        dir,
                        reply("{\"to\":\"review\",\"kind\":\"comment\",\"thread\":\"th-1\"}", TEXT),
                        false);
        final StandIn review = new StandIn(Optional.empty());

        new MessageDispatch(review, NO_SUBMISSIONS, container -> null)
                .dispatch(mailbox, mail, undecided(mailbox));

        assertThat(review.taken).isEmpty();
        assertThat(bounces(mailbox))
                .singleElement()
                .satisfies(
                        bounce -> assertThat(verdict(bounce)).containsEntry("verdict", "refused"));
    }

    @Test
    void aProjectCannotNameAPeerReview() {
        // A peer of that name would take the replies to the review to wherever it points.
        assertThatThrownBy(() -> new Mail.Peer(Mail.REVIEW, "mail:ops@example.org", "external"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("review");
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                """
                                                project:
                                                  name: "p"
                                                  security_class: "guarded"
                                                image:
                                                  from: "ubuntu:24.04"
                                                mail:
                                                  peers:
                                                    review:
                                                      address: "mail:ops@example.org"
                                                      trust: "external"
                                                """),
                                        "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("mail.peers.review");
    }

    @Test
    void noTaskCanBeNamedReview() {
        // A task of that name would have its messages taken for replies to the review.
        assertThat(ContainerName.reserved(Mail.REVIEW)).isTrue();
        assertThat(ContainerName.refusal("p", Mail.REVIEW)).isPresent();
        assertThatThrownBy(() -> new Mail.Peer("task:other:review", "matrix:", "vouched"))
                .as("another project's task of that name")
                .isInstanceOf(ProjectException.class);
    }
}
