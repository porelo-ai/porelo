package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.fuin.porelo.core.project.Mail;
import org.junit.jupiter.api.Test;

/** Tests for {@link MessageSay}: a person's words go only where they can arrive. */
class MessageSayTest {

    @Test
    void aProjectWithNoConversationRefusesANameItDoesNotKnow() {

        // It answered "written", and the message went nowhere: the project had no conversation and
        // no such peer.
        assertThat(MessageSay.refusal(Mail.none(), "b"))
                .contains("no peer 'b'")
                .contains("no conversation");
    }

    @Test
    void aProjectWithAConversationTakesAnyNameATaskElsewhereMayHave() {

        // A task of the project may run on another machine, so in a conversation a name is not
        // refused here.
        assertThat(
                        MessageSay.refusal(
                                new Mail(java.util.List.of(), Map.of("matrix", Map.of())), "b"))
                .isNull();
    }

    @Test
    void wordsForATaskRemovedSinceItWasFoundMakeNoMailboxAgain(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
            throws java.io.IOException {
        // 'talk say' looked at the mailbox, then wrote: a removal in between got the mailbox back
        // as the person's directory, and the task's retired name stayed taken.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        mailbox.delete();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new MessageSay().write(mailbox, "b", "question", null, "hello"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(mailbox.root()).as("no mailbox made again").doesNotExist();
    }
}
