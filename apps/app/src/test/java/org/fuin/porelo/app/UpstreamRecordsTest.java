package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.fuin.porelo.gate.UpstreamDistance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link UpstreamRecords}.
 *
 * <p>Written from a timer while every project listing reads it, so what matters is that a reader
 * never sees half a record and never sees a number it should not trust.
 */
class UpstreamRecordsTest {

    @Test
    void remembersANumberAndWhenItWasTaken(@TempDir Path dir) {

        final Instant when = Instant.parse("2026-09-07T17:44:30Z");
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put(
                "uc",
                new UpstreamDistance.Distance(3, when, UpstreamDistance.Reason.MEASURED, null));

        final UpstreamDistance.Distance back = records.get("uc");
        assertThat(back.behind()).isEqualTo(3);
        assertThat(back.measured()).isEqualTo(when);
        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.MEASURED);
    }

    @Test
    void aProjectNothingHasLookedAtSaysSoRatherThanZero(@TempDir Path dir) {

        // Zero and never-checked are the same number and different sentences. An interface that
        // read the number alone would say "up to date" about something nobody has ever measured.
        final UpstreamDistance.Distance back = new UpstreamRecords(dir).get("never-seen");

        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.NEVER_CHECKED);
        assertThat(back.measured()).isNull();
    }

    @Test
    void keepsTheReasonAndTheDetailOfAFailure(@TempDir Path dir) {

        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put(
                "uc",
                new UpstreamDistance.Distance(
                        0,
                        null,
                        UpstreamDistance.Reason.FAILED,
                        "could not read from remote repository"));

        final UpstreamDistance.Distance back = records.get("uc");
        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.FAILED);
        assertThat(back.detail()).isEqualTo("could not read from remote repository");
        assertThat(back.measured()).isNull();
    }

    @Test
    void aDetailWithATabInItDoesNotSplitTheRecord(@TempDir Path dir) {

        // git's own output is what goes in here, and it contains whatever it contains. A record
        // that could be split by its own content would come back as a different reason entirely.
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put(
                "uc",
                new UpstreamDistance.Distance(
                        0,
                        null,
                        UpstreamDistance.Reason.FAILED,
                        "fatal:\tcould not read\tfrom remote"));

        assertThat(new UpstreamRecords(dir).get("uc").reason())
                .isEqualTo(UpstreamDistance.Reason.FAILED);
        assertThat(new UpstreamRecords(dir).get("uc").detail())
                .isEqualTo("fatal: could not read from remote");
    }

    @Test
    void anUnreadableRecordReadsAsNeverCheckedRatherThanThrowing(@TempDir Path dir)
            throws Exception {

        // The one answer that is true whatever the file said. A listing must not fail because a
        // record was truncated, hand-edited, or written by a newer build.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("uc"), "WHAT\tIS\tTHIS\n", StandardCharsets.UTF_8);

        assertThat(new UpstreamRecords(dir).get("uc").reason())
                .isEqualTo(UpstreamDistance.Reason.NEVER_CHECKED);
    }

    @Test
    void oneProjectPerFileSoConcurrentWritersCannotLoseEachOther(@TempDir Path dir)
            throws Exception {

        // The shape the project registry had to be changed to, measured there: a single shared
        // document lost entries under concurrent writers. This is written from a timer while
        // listings read it, so it is the same race and gets the same answer.
        final UpstreamRecords records = new UpstreamRecords(dir);
        final int writers = 24;
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int index = i;
            threads.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        records.put(
                                                "project-" + index,
                                                new UpstreamDistance.Distance(
                                                        index,
                                                        Instant.now(),
                                                        UpstreamDistance.Reason.MEASURED,
                                                        null));
                                    }));
        }
        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        for (int i = 0; i < writers; i++) {
            assertThat(records.get("project-" + i).behind()).as("project-%d", i).isEqualTo(i);
        }
    }

    @Test
    void keepsHowFarAheadBesideHowFarBehind(@TempDir Path dir) {
        // Lost on the way, a client reads a branch with work not yet at central as up to date.
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put(
                "uc",
                new UpstreamDistance.Distance(
                        2,
                        Instant.parse("2026-11-09T10:00:00Z"),
                        UpstreamDistance.Reason.MEASURED,
                        null,
                        java.util.List.of(),
                        new UpstreamDistance.Central(
                                "ahead", 1, Instant.parse("2026-11-09T10:05:00Z"), null)));

        final UpstreamDistance.Distance back = new UpstreamRecords(dir).get("uc");
        assertThat(back.ahead()).as("the sixth column, read back").isEqualTo(1);
        assertThat(back.central())
                .as("the host's answer, read back whole")
                .isEqualTo(
                        new UpstreamDistance.Central(
                                "ahead", 1, Instant.parse("2026-11-09T10:05:00Z"), null));
        assertThat(back.behind()).as("the second column, unmoved by the sixth").isEqualTo(2);
    }

    @Test
    void aRecordOfFiveColumnsMeasuredNoAheadRatherThanNoneAhead(@TempDir Path dir)
            throws Exception {
        // Read as 0, a record written before the column existed would say nothing waits for
        // central.
        Files.writeString(
                dir.resolve("uc"),
                "MEASURED\t0\t2026-11-09T10:00:00Z\t\tfix-1\n",
                StandardCharsets.UTF_8);

        final UpstreamDistance.Distance back = new UpstreamRecords(dir).get("uc");
        assertThat(back.reason())
                .as("the record itself still reads")
                .isEqualTo(UpstreamDistance.Reason.MEASURED);
        assertThat(back.ahead()).as("not measured, which is not 0").isNull();
    }

    @Test
    void aMeasurementOfNoAheadLeavesTheColumnUnmeasured(@TempDir Path dir) {
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put(
                "uc",
                new UpstreamDistance.Distance(
                        0, Instant.now(), UpstreamDistance.Reason.MEASURED, null));

        assertThat(new UpstreamRecords(dir).get("uc").ahead())
                .as("a measure that did not look ahead is written as not measured, never 0")
                .isNull();
    }

    @Test
    void aMeasureOfTheMirrorKeepsWhatTheHostSaidOfCentral(@TempDir Path dir) {
        // The gate's own measure knows nothing of central; written whole, it would erase the
        // host's answer every fifteen minutes.
        final UpstreamRecords records = new UpstreamRecords(dir);
        final UpstreamDistance.Central said =
                new UpstreamDistance.Central(
                        "ahead", 3, Instant.parse("2026-11-09T10:05:00Z"), null);
        records.putCentral("uc", said, dir);

        records.putMeasured(
                "uc",
                new UpstreamDistance.Distance(
                        4, Instant.now(), UpstreamDistance.Reason.MEASURED, null),
                dir);

        assertThat(records.get("uc").central()).as("the host's answer stays").isEqualTo(said);
        assertThat(records.get("uc").behind()).as("the gate's own measure is written").isEqualTo(4);
    }

    @Test
    void theHostsAnswerKeepsWhatTheGatesOwnMeasureFound(@TempDir Path dir) {
        // Written whole, central's answer would reset how far behind the mirror is to never
        // checked.
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.putMeasured(
                "uc",
                new UpstreamDistance.Distance(
                        4,
                        Instant.parse("2026-11-09T10:00:00Z"),
                        UpstreamDistance.Reason.MEASURED,
                        null,
                        java.util.List.of("fix-1"),
                        null),
                dir);

        records.putCentral(
                "uc", new UpstreamDistance.Central("up-to-date", 0, Instant.now(), null), dir);

        assertThat(records.get("uc").behind()).as("behind stays the gate's").isEqualTo(4);
        assertThat(records.get("uc").reason()).isEqualTo(UpstreamDistance.Reason.MEASURED);
        assertThat(records.get("uc").alreadyUpstream())
                .as("what the gate found upstream already stays")
                .containsExactly("fix-1");
        assertThat(records.get("uc").ahead()).as("the host's number is written").isZero();
    }

    @Test
    void theHostsAnswerWrittenWhileTheMirrorIsMeasuredIsNotLost(@TempDir Path dir)
            throws Exception {
        // The timer and SyncUpstream write the two halves of one record from two threads.
        final UpstreamRecords records = new UpstreamRecords(dir);
        final UpstreamDistance.Distance measured =
                new UpstreamDistance.Distance(
                        4,
                        Instant.parse("2026-11-09T10:00:00Z"),
                        UpstreamDistance.Reason.MEASURED,
                        null,
                        java.util.List.of(),
                        null);
        for (int round = 0; round < 50; round++) {
            final String key = "uc-" + round;
            final java.util.concurrent.atomic.AtomicBoolean answered =
                    new java.util.concurrent.atomic.AtomicBoolean();
            final Thread mirror =
                    new Thread(
                            () -> {
                                for (int i = 0; i < 200 || !answered.get(); i++) {
                                    records.putMeasured(key, measured, dir);
                                }
                                records.putMeasured(key, measured, dir);
                            });
            mirror.start();
            records.putCentral(
                    key, new UpstreamDistance.Central("ahead", 2, Instant.now(), null), dir);
            answered.set(true);
            mirror.join();

            assertThat(records.get(key).ahead())
                    .as("the host's answer outlives a measure written at the same moment")
                    .isEqualTo(2);
        }
    }
}
