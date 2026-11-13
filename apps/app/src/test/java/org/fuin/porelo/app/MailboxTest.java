package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link Mailbox}. */
class MailboxTest {

    @TempDir private Path temp;

    @Test
    void creates_every_directory_a_task_needs() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));

        mailbox.create();

        assertThat(mailbox.inboxTmp()).isDirectory();
        assertThat(mailbox.inboxNew()).isDirectory();
        assertThat(mailbox.inboxCur()).isDirectory();
        assertThat(mailbox.outboxTmp()).isDirectory();
        assertThat(mailbox.outboxNew()).isDirectory();
        assertThat(mailbox.sentCopies()).isDirectory();
        assertThat(mailbox.incoming()).isDirectory();
        assertThat(mailbox.accepted()).isDirectory();
        assertThat(mailbox.feedback()).isDirectory();
        assertThat(mailbox.rejected()).isDirectory();
        assertThat(mailbox.error()).isDirectory();
        assertThat(mailbox.index()).isDirectory();
        assertThat(mailbox.hold()).isDirectory();
        assertThat(mailbox.inbound()).isDirectory();
        assertThat(mailbox.inboundTmp()).isDirectory();
        assertThat(mailbox.sent()).isDirectory();
        assertThat(mailbox.record()).isDirectory();
    }

    @Test
    void a_box_directory_the_task_replaced_with_a_link_opens_nothing_it_points_at()
            throws IOException {
        // Created again at every start, the box's permissions were set through the link: the
        // account's own directory it pointed at became writable by every account on the host.
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path own = Files.createDirectories(temp.resolve("own"));
        Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rwx------"));
        Files.delete(mailbox.outboxNew());
        Files.createSymbolicLink(mailbox.outboxNew(), own);

        org.assertj.core.api.Assertions.assertThatThrownBy(mailbox::create)
                .as("refused, naming the link")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(mailbox.outboxNew().toString());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(own)))
                .as("what the link points at is as it was")
                .isEqualTo("rwx------");
    }

    @Test
    void nothing_but_the_box_is_open_and_the_mailbox_around_it_shuts_every_other_account_out()
            throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));

        mailbox.create();

        for (final Path directory :
                new Path[] {
                    mailbox.root(),
                    mailbox.incoming(),
                    mailbox.accepted(),
                    mailbox.rejected(),
                    mailbox.error(),
                    mailbox.record(),
                    mailbox.hold(),
                    mailbox.inbound()
                }) {
            assertThat(mode(directory)).as(directory.toString()).isEqualTo("rwx------");
        }
    }

    /**
     * Found in walk 9 on 2026-10-04: the agent could not read its own mailbox. The account running
     * Porelo is root in the container, and the agent is another user there; every directory was the
     * account's alone.
     */
    @Test
    void the_agent_reads_its_inbox_moves_what_it_read_and_writes_its_outbox() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));

        mailbox.create();

        for (final Path directory :
                new Path[] {
                    mailbox.box(),
                    mailbox.box().resolve("inbox"),
                    mailbox.box().resolve("outbox"),
                    mailbox.inboxTmp(),
                    mailbox.sentCopies()
                }) {
            assertThat(mode(directory))
                    .as("passed and read, not written: " + directory)
                    .isEqualTo("rwxr-xr-x");
        }
        for (final Path directory :
                new Path[] {
                    mailbox.inboxNew(), mailbox.inboxCur(), mailbox.outboxTmp(), mailbox.outboxNew()
                }) {
            // Not sticky: the agent renames what the host put into inbox/new, and the host what the
            // agent wrote.
            assertThat(mode(directory))
                    .as("written by the agent: " + directory)
                    .isEqualTo("rwxrwxrwx");
        }
    }

    @Test
    void what_the_host_puts_into_the_inbox_the_agent_can_read() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path staged = mailbox.inboxTmp().resolve("m-1.json");
        Files.writeString(staged, "{}");
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));

        mailbox.intoInbox(staged);

        assertThat(staged).doesNotExist();
        assertThat(mode(mailbox.inboxNew().resolve("m-1.json"))).isEqualTo("rw-r--r--");
    }

    @Test
    void creating_it_again_opens_the_box_of_a_task_made_before() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        Files.setPosixFilePermissions(
                mailbox.outboxNew(), PosixFilePermissions.fromString("rwx------"));

        mailbox.create();

        assertThat(mode(mailbox.outboxNew())).isEqualTo("rwxrwxrwx");
    }

    private static String mode(final Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    /**
     * The mount hands the agent its own inbox and outbox. Anything else in the mailbox - the
     * refused originals above all - has to stay on the host's side of it, so this asserts on where
     * the directories are rather than on how the mount is spelt somewhere else.
     */
    @Test
    void what_the_container_sees_holds_no_refusal_and_no_record() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));

        mailbox.create();

        assertThat(mailbox.inboxNew()).startsWith(mailbox.box());
        assertThat(mailbox.outboxNew()).startsWith(mailbox.box());
        assertThat(mailbox.sentCopies()).startsWith(mailbox.box());
        assertThat(mailbox.rejected().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.error().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.record().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.incoming().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.hold().startsWith(mailbox.box())).isFalse();
    }

    @Test
    void creating_it_twice_keeps_what_is_in_it() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path message = mailbox.inboxNew().resolve("one.json");
        Files.writeString(message, "{}");

        mailbox.create();

        assertThat(message).exists();
    }

    @Test
    void deleting_it_takes_everything_with_it() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        Files.writeString(mailbox.rejected().resolve("secret.json"), "{}");

        mailbox.delete();

        assertThat(mailbox.exists()).isFalse();
        assertThat(mailbox.root()).doesNotExist();
    }

    @Test
    void deleting_a_mailbox_that_was_never_made_is_not_an_error() throws IOException {
        new Mailbox(temp.resolve("never")).delete();
    }

    @Test
    void a_file_written_into_it_while_it_is_deleted_goes_with_it() throws IOException {
        // The daemon's guide writer staged a temp file in the root between the delete's walk and
        // its last rmdir: the delete failed, and the removal it was part of left its name claimed.
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final java.util.concurrent.atomic.AtomicInteger walks =
                new java.util.concurrent.atomic.AtomicInteger();

        mailbox.delete(
                () -> {
                    if (walks.incrementAndGet() == 1) {
                        late(mailbox.root().resolve(".guide1.tmp"));
                    }
                });

        assertThat(mailbox.root()).as("the late file went too").doesNotExist();
    }

    @Test
    void a_writer_that_never_stops_fails_the_delete_after_a_few_tries() throws IOException {
        // Bounded: a removal is never held by a writer that keeps writing.
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final java.util.concurrent.atomic.AtomicInteger walks =
                new java.util.concurrent.atomic.AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                mailbox.delete(
                                        () ->
                                                late(
                                                        mailbox.root()
                                                                .resolve(
                                                                        ".guide"
                                                                                + walks
                                                                                        .incrementAndGet()
                                                                                + ".tmp"))))
                .as("a mailbox that does not empty is a failed delete")
                .isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
        assertThat(walks.get()).as("walked again, a bounded number of times").isBetween(2, 10);
    }

    @Test
    void a_message_the_host_delivered_counts_as_delivered() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path staged = Files.writeString(mailbox.inboxTmp().resolve("m.json"), "{}");

        final Path delivered = mailbox.intoInbox(staged);

        assertThat(mailbox.delivered(delivered))
                .as("renamed in by the account running Porelo, so its own")
                .isTrue();
    }

    @Test
    void a_file_of_another_account_in_the_inbox_is_not_delivered() throws IOException {
        // As the agent's user writes one inside the container: the account running Porelo is not
        // its owner.
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"), () -> "someone-else");
        mailbox.create();
        final Path planted = Files.writeString(mailbox.inboxNew().resolve("m.json"), "{}");

        assertThat(mailbox.delivered(planted))
                .as("a file the account does not own was not delivered by Porelo")
                .isFalse();
    }

    @Test
    void a_link_in_the_inbox_is_not_delivered_even_to_a_delivered_message() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path real =
                mailbox.intoInbox(Files.writeString(mailbox.inboxTmp().resolve("m.json"), "{}"));
        final Path link = Files.createSymbolicLink(mailbox.inboxCur().resolve("copy.json"), real);

        assertThat(mailbox.delivered(link)).as("a link is never followed").isFalse();
    }

    @Test
    void a_directory_or_a_missing_name_in_the_inbox_is_not_delivered() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path directory = Files.createDirectory(mailbox.inboxNew().resolve("d.json"));

        assertThat(mailbox.delivered(directory)).as("a directory is no message").isFalse();
        assertThat(mailbox.delivered(mailbox.inboxNew().resolve("gone.json")))
                .as("a name with nothing behind it is no message")
                .isFalse();
    }

    @Test
    void a_delivered_message_is_read_through_a_pin_that_leaves_nothing_behind() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path delivered =
                mailbox.intoInbox(Files.writeString(mailbox.inboxTmp().resolve("m.json"), "{}"));

        assertThat(mailbox.readDelivered(delivered, 1024))
                .as("its bytes")
                .isEqualTo("{}".getBytes());
        assertThat(mailbox.inboxTmp()).as("the pin is gone again").isEmptyDirectory();
    }

    @Test
    void a_link_a_pipe_or_another_accounts_file_in_the_inbox_is_never_read() throws Exception {
        // Harm: a pipe put in place of a message held the host's read and every mailbox's pass; a
        // link or another account's file would be read as Porelo's message.
        final Mailbox mailbox = new Mailbox(temp.resolve("porelo-p-t"));
        mailbox.create();
        final Path delivered =
                mailbox.intoInbox(Files.writeString(mailbox.inboxTmp().resolve("m.json"), "{}"));
        final Path link = Files.createSymbolicLink(mailbox.inboxCur().resolve("l.json"), delivered);
        final Path pipe = mailbox.inboxNew().resolve("p.json");
        assertThat(new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor())
                .as("mkfifo made the pipe")
                .isZero();

        assertThat(mailbox.readDelivered(link, 1024)).as("a link is not followed").isNull();
        assertThat(mailbox.readDelivered(pipe, 1024)).as("a pipe is never opened").isNull();
        assertThat(new Mailbox(mailbox.root(), () -> "someone-else").readDelivered(delivered, 1024))
                .as("another account's file is not read")
                .isNull();
        assertThat(mailbox.readDelivered(delivered, 1)).as("beyond the limit").isNull();
        assertThat(mailbox.inboxTmp()).as("no pin is left").isEmptyDirectory();
    }

    private static void late(final Path file) {
        try {
            Files.writeString(file, "late");
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
