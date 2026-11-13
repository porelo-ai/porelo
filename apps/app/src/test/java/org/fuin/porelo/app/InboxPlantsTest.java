package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link InboxPlants}. */
class InboxPlantsTest {

    @TempDir private Path dir;

    @Test
    void eachFileInTheInboxPoreloDidNotDeliverIsRecordedOnceAndNoDeliveredOne() throws IOException {
        // Harm: a person must learn that something in the task writes its inbox, and the record
        // must not grow with every pass.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        final Path delivered =
                mailbox.intoInbox(Files.writeString(mailbox.inboxTmp().resolve("real.json"), "{}"));
        Files.createSymbolicLink(mailbox.inboxNew().resolve("link.json"), delivered);
        Files.writeString(mailbox.inboxCur().resolve("cur.json"), "{}");
        final Mailbox seenByOther = new Mailbox(mailbox.root(), () -> "someone-else");

        assertThat(InboxPlants.note(mailbox))
                .as("the account's own files are delivered; the link is not")
                .containsExactly("link.json");
        assertThat(InboxPlants.note(seenByOther))
                .as("every file of another account is planted, the link only once")
                .containsExactlyInAnyOrder("real.json", "cur.json");
        assertThat(InboxPlants.note(seenByOther)).as("named once").isEmpty();
        assertThat(InboxPlants.planted(new MessageRecord(mailbox)))
                .as("the record names each once")
                .containsExactlyInAnyOrder("link.json", "real.json", "cur.json");
    }

    @Test
    void aProcessThatPlantsWithoutEndFillsTheRecordOnlyUpToItsBound() throws IOException {
        // Harm: a record that grows without bound fills the account's disk.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"), () -> "someone-else");
        mailbox.create();
        for (int i = 0; i < InboxPlants.MOST + 5; i++) {
            Files.writeString(mailbox.inboxNew().resolve(i + ".json"), "{}");
        }

        InboxPlants.note(mailbox);
        InboxPlants.note(mailbox);

        assertThat(InboxPlants.planted(new MessageRecord(mailbox)))
                .as("bounded")
                .hasSize(InboxPlants.MOST);
    }
}
