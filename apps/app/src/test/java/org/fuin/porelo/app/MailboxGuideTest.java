package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Mail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link MailboxGuide}. */
class MailboxGuideTest {

    @Test
    void tellsTheAgentWhatTheHostAndTheFilterEnforce() {
        final String text = MailboxGuide.text();

        assertThat(text)
                .contains(Mailbox.MOUNT + "/inbox/new")
                .contains(Mailbox.MOUNT + "/outbox/tmp")
                .contains(Mailbox.MOUNT + "/outbox/new")
                .contains(Mailbox.CARD)
                .contains("\"role\": \"" + MessageIntake.AGENT + "\"")
                .contains("metadata.to")
                .contains("\"via\": \"room\"")
                .contains(Mail.PEOPLE)
                .contains("messageId")
                // The operator in walk 10: "Seid Ihr alle da?" to the room, and an agent filed it
                // unanswered.
                .contains("names nobody is meant for every agent in the room:")
                .contains("answer it in the room.");
    }

    @Test
    void sendingUsesTheNamesThePeerCardAllows() {
        // An agent following the guide must not choose an alias that dispatch will hold.
        assertThat(MailboxGuide.text())
                .as("outgoing recipients are the peer card's names")
                .contains("Use only the names it lists in `metadata.to`");
    }

    @Test
    @Tag("documents")
    void anUnmatchedRoomMessageIsSilentBeforeSignatureChecks() throws IOException {
        // A false refusal promise leaves a person waiting for an answer that never comes.
        assertThat(Files.readString(Path.of("..", "..", "doc", "messages.md")))
                .as("unmatched task messages are dropped silently before delivery checks")
                .contains("A task's message naming no task here reaches none, silently")
                .doesNotContain("project's tasks; anything else is held");
    }

    @Test
    void isTheSameForEveryTaskSoAProvidersCacheOfThePromptHolds() {

        // 2026-10-04: the text ends the system prompt, and a prompt is cached by its beginning.
        assertThat(MailboxGuide.text()).isEqualTo(MailboxGuide.text()).doesNotContain("porelo-");
    }

    @Test
    void writesBothFilesIntoTheBoxForTheAgentToReadAndNamesNoAddress(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();

        MailboxGuide.write(
                mailbox,
                new Mail(
                        List.of(
                                new Mail.Peer("people", "matrix:", Mail.Peer.EXTERNAL),
                                new Mail.Peer("ops", "mail:ops@example.org", Mail.Peer.EXTERNAL),
                                new Mail.Peer("api", "matrix:", Mail.Peer.VOUCHED)),
                        Map.of()));

        assertThat(mailbox.guide()).hasContent(MailboxGuide.text());
        assertThat(mailbox.card())
                .content()
                .contains("\"people\"")
                .contains("\"ops\"")
                .contains("\"api\"")
                .contains("another task of your project")
                .doesNotContain("ops@example.org")
                .doesNotContain("matrix:");
        for (final Path file : List.of(mailbox.guide(), mailbox.card())) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-r--r--");
        }
        assertThat(mailbox.guide().getParent()).isEqualTo(mailbox.box());
    }

    @Test
    void leavesAFileThatDidNotChangeAlone(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        MailboxGuide.write(mailbox, Mail.none());
        final var before = Files.getLastModifiedTime(mailbox.card());
        Files.setLastModifiedTime(
                mailbox.card(), java.nio.file.attribute.FileTime.fromMillis(1000));

        MailboxGuide.write(mailbox, Mail.none());

        assertThat(Files.getLastModifiedTime(mailbox.card()).toMillis()).isEqualTo(1000);
        assertThat(before).isNotNull();
    }

    @Test
    void aWriteWhoseMoveFailsLeavesNoStagedFileBehind(@TempDir final Path dir) throws IOException {
        // A box deleted under the writer: every later pass added one more temp file, and the
        // mailbox could no longer be deleted.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        try (var tree = Files.walk(mailbox.box())) {
            for (final Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> MailboxGuide.write(mailbox, Mail.none()))
                .as("the guide has nowhere to go")
                .isInstanceOf(IOException.class);

        try (var left = Files.list(mailbox.root())) {
            assertThat(left.map(path -> path.getFileName().toString()))
                    .as("nothing staged is left in the mailbox's root")
                    .noneMatch(name -> name.startsWith(".guide"));
        }
    }

    @Test
    @Tag("documents")
    void theUserDocumentationShowsTheTextAgentsAreGivenWordForWord() throws IOException {

        // What a person reads about it is what the agent is told, so the two cannot drift apart.
        final String documented = Files.readString(Path.of("..", "..", "doc", "messages.md"));

        for (final String line : MailboxGuide.text().split("\n")) {
            assertThat(documented)
                    .as("doc/messages.md")
                    .contains(line.startsWith("#") ? "#" + line : line);
        }
    }
}
