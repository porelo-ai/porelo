package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link MessageRecord}. */
class MessageRecordTest {

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void an_empty_record_holds_together(@TempDir final Path dir) throws IOException {
        assertThat(new MessageRecord(mailbox(dir)).firstBrokenLine()).isZero();
    }

    @Test
    void remembers_what_was_delivered(@TempDir final Path dir) throws IOException {
        final MessageRecord record = new MessageRecord(mailbox(dir));

        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.DELIVERED, "m-2.json", "m-2", "");
        record.append(MessageRecord.HELD, "m-3.json", "", "it arrived without a signature");

        assertThat(record.delivered()).containsExactly("m-2");
        assertThat(record.size()).isEqualTo(3);
        assertThat(record.firstBrokenLine()).isZero();
    }

    @Test
    void holds_no_message_text(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);

        record.append(MessageRecord.HELD, "m-1.json", "m-1", "the filter refused it");

        assertThat(Files.readString(mailbox.record().resolve(MessageRecord.FILE)))
                .as("a record that quoted refused messages would copy what was refused")
                .doesNotContain("the secret")
                .contains("the filter refused it");
    }

    /** The point of chaining: a line changed after the fact is named, not silently accepted. */
    @Test
    void names_the_first_line_that_was_changed_afterwards(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.HELD, "m-2.json", "", "the filter refused it");
        record.append(MessageRecord.DELIVERED, "m-3.json", "m-3", "");

        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(1, lines.get(1).replace("the filter refused it", "nothing happened"));
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThat(record.firstBrokenLine()).isEqualTo(2);
    }

    @Test
    void names_a_line_that_was_removed(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.HELD, "m-2.json", "", "the filter refused it");
        record.append(MessageRecord.DELIVERED, "m-3.json", "m-3", "");

        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.write(file, List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);

        assertThat(record.firstBrokenLine())
                .as("the line after the gap no longer follows")
                .isEqualTo(2);
    }

    @org.junit.jupiter.api.Test
    void twoWritersAtOnceLeaveARecordThatStillChecksOut(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // The daemon's pass and a 'talk pass' or 'talk release' wrote at once: both read the same
        // last hash, two lines shared a 'previous', and 'talk verify' said the record had been
        // changed when nobody had.
        final Mailbox mailbox = new Mailbox(dir.resolve("porelo-p-t"));
        mailbox.create();
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> writers = new java.util.ArrayList<>();
        for (int w = 0; w < 4; w++) {
            final int writer = w;
            writers.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            go.await();
                                            final MessageRecord record = new MessageRecord(mailbox);
                                            for (int i = 0; i < 100; i++) {
                                                record.append(
                                                        MessageRecord.TAKEN,
                                                        "m-" + writer + "-" + i + ".json",
                                                        "",
                                                        "",
                                                        "");
                                            }
                                        } catch (final Exception ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    }));
        }
        go.countDown();
        for (final Thread writer : writers) {
            writer.join();
        }

        org.assertj.core.api.Assertions.assertThat(new MessageRecord(mailbox).firstBrokenLine())
                .isZero();
    }

    @Test
    void an_append_to_a_deleted_mailbox_makes_no_mailbox_again(@TempDir final Path dir)
            throws IOException {
        // The daemon's read pass wrote a line into a removed task's record: the mailbox came back
        // as its record, and the task's retired name stayed taken.
        final Mailbox mailbox = mailbox(dir);
        mailbox.delete();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new MessageRecord(mailbox)
                                        .append(MessageRecord.READ, "m-1.json", "m-1", "", "", ""))
                .as("nothing is recorded for a mailbox that is gone")
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(mailbox.root()).as("no mailbox made again").doesNotExist();
    }

    @Test
    void a_removal_between_the_check_and_the_lock_is_not_undone(@TempDir final Path dir)
            throws Exception {
        // The append found the mailbox there and went for its lock; the task was removed in
        // between, and taking the lock made mail/<container>/record again. Here the removal waits
        // in the lock's holder until the append stands at the lock, past its check.
        final Mailbox mailbox = mailbox(dir);
        final Path lock = mailbox.record().resolve(".record.lock");
        final java.util.concurrent.CountDownLatch holding =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Thread> appender =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> answer =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Thread remover =
                new Thread(
                        () -> {
                            try {
                                FileLocks.holding(
                                        lock,
                                        () -> {
                                            holding.countDown();
                                            Races.waitsAtTheLock(appender);
                                            mailbox.delete();
                                            return Boolean.TRUE;
                                        });
                            } catch (final IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });
        remover.start();
        holding.await();
        final Thread append =
                new Thread(
                        () -> {
                            try {
                                new MessageRecord(mailbox)
                                        .append(MessageRecord.READ, "m-1.json", "m-1", "", "", "");
                            } catch (final Throwable ex) {
                                answer.set(ex);
                            }
                        });
        appender.set(append);
        append.start();
        append.join(10_000);
        remover.join(10_000);

        assertThat(mailbox.root()).as("no mailbox made again").doesNotExist();
        assertThat(answer.get())
                .as("nothing is recorded for a mailbox that went")
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }
}
