package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link PersonNote}. */
class PersonNoteTest {

    @TempDir private Path dir;

    @Test
    void aNoteForATaskRemovedSinceItWasFoundMakesNoMailboxAgain() throws IOException {
        // A refresh or a rejected push looked at the mailbox, then wrote the note: a removal in
        // between got the mailbox back as its inbox, and the task's retired name stayed taken.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        mailbox.delete();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PersonNote().moved(mailbox, Map.of("main", "abc123")))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(mailbox.root()).as("no mailbox made again").doesNotExist();
    }
}
