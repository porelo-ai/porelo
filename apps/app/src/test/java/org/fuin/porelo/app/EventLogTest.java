package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the account's event log: a line changed or removed afterwards is named, by its chain
 * and against the hashes the journal holds, which the account can append to but not rewrite.
 */
class EventLogTest {

    @TempDir Path dir;

    /** A journal that keeps what was sent, as the user's journal does. */
    private static final class Kept implements EventLog.Anchor {

        private final List<EventLog.Anchored> hashes = new ArrayList<>();

        private boolean refusing;

        private boolean unreadable;

        /** Each time it was reached, whether the log's lock was held then. */
        private final List<Boolean> underLock = new ArrayList<>();

        private @Nullable Path lock;

        @Override
        public boolean send(final String hash, final String kind) {
            noteLock();
            if (refusing) {
                return false;
            }
            hashes.add(new EventLog.Anchored(hash, java.time.Instant.now()));
            return true;
        }

        @Override
        public @Nullable List<EventLog.Anchored> anchored() {
            noteLock();
            return unreadable ? null : List.copyOf(hashes);
        }

        private void noteLock() {
            if (lock == null) {
                return;
            }
            try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(
                            lock,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE)) {
                final java.nio.channels.FileLock taken = channel.tryLock();
                underLock.add(taken == null);
                if (taken != null) {
                    taken.release();
                }
            } catch (java.nio.channels.OverlappingFileLockException ex) {
                underLock.add(true);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    private EventLog threeEvents(final Kept journal) throws Exception {
        final EventLog log = new EventLog(dir.resolve("events.jsonl"), journal);
        log.append(EventLog.APPROVED, "uc", "porelo-uc-fix", "onto main at abc123");
        log.append(EventLog.CLEARANCE, "uc", "porelo-uc-fix", "allowed example.org");
        log.append(EventLog.HAND_IN, "uc", "porelo-uc-fix", "gave notes.txt");
        return log;
    }

    @Test
    void anUntouchedLogHoldsAndEveryLineIsAnchored() throws Exception {
        final EventLog.Verification verified = threeEvents(new Kept()).verify();

        assertThat(verified.holds()).isTrue();
        assertThat(verified.lines()).isEqualTo(3);
    }

    @Test
    void aLineEditedAfterwardsIsNamedByItsChain() throws Exception {
        final EventLog log = threeEvents(new Kept());
        final Path file = dir.resolve("events.jsonl");
        Files.writeString(
                file,
                Files.readString(file).replace("allowed example.org", "allowed nothing"),
                StandardCharsets.UTF_8);

        final EventLog.Verification verified = log.verify();

        assertThat(verified.holds()).isFalse();
        assertThat(verified.firstBroken()).isEqualTo(2);
    }

    @Test
    void aLineRewrittenWithItsChainRecomputedIsStillNamedByTheJournal() throws Exception {

        // The account can recompute every hash of its own file; the journal it cannot rewrite.
        final Kept journal = new Kept();
        threeEvents(journal);
        final Path file = dir.resolve("events.jsonl");
        Files.delete(file);
        final EventLog forged = new EventLog(file, new Kept());
        forged.append(EventLog.APPROVED, "uc", "porelo-uc-fix", "onto main at abc123");
        forged.append(EventLog.CLEARANCE, "uc", "porelo-uc-fix", "allowed nothing");

        final EventLog.Verification verified = new EventLog(file, journal).verify();

        assertThat(verified.firstBroken()).as("the forged chain holds in itself").isZero();
        assertThat(verified.missing())
                .as("the journal's hashes of the lines that were there")
                .hasSize(3);
        assertThat(verified.notAnchored())
                .as("each forged line, dated after the journal's oldest of this log")
                .containsExactly(1, 2);
        assertThat(verified.beforeAnchor()).isZero();
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void theLastLinesRemovedTogetherAreNamedByTheJournal() throws Exception {
        final Kept journal = new Kept();
        final EventLog log = threeEvents(journal);
        final Path file = dir.resolve("events.jsonl");
        Files.write(file, Files.readAllLines(file).subList(0, 1), StandardCharsets.UTF_8);

        final EventLog.Verification verified = log.verify();

        assertThat(verified.firstBroken()).isZero();
        assertThat(verified.missing()).hasSize(2);
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void anEventThatCouldNotBeAnchoredIsNamedAndTheActGoesOn() throws Exception {
        final Kept journal = new Kept();
        final EventLog log = threeEvents(journal);
        new EventLog(dir.resolve("events.jsonl"), new Kept())
                .append(EventLog.REJECTED, "uc", "porelo-uc-fix", "not now");

        final EventLog.Verification verified = log.verify();

        assertThat(verified.notAnchored()).containsExactly(4);
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void theJournalIsWrittenWithTheHashAsAFieldAndReadBackByIt() throws Exception {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final EventLog.Journal journal = new EventLog.Journal(runner, dir.resolve("events.jsonl"));
        runner.answering(
                "journalctl",
                "{\"PORELO_EVENT_HASH\":\"aa11\",\"__REALTIME_TIMESTAMP\":\"1793156300896974\"}\n"
                        + "{\"MESSAGE\":\"other\"}\n"
                        + "{\"PORELO_EVENT_HASH\":\"bb22\",\"__REALTIME_TIMESTAMP\":\"1793156300996974\"}\n");

        assertThat(journal.send("cc33", EventLog.APPROVED)).isTrue();
        final Command sent = runner.only("logger");
        assertThat(sent.arguments()).containsExactly("logger", "--journald");
        assertThat(sent.input())
                .contains("PORELO_EVENT_HASH=cc33\n")
                .contains("SYSLOG_IDENTIFIER=porelo-events\n")
                .as("the log it belongs to, so another log's hashes are never this one's")
                .contains(
                        "PORELO_EVENT_LOG=" + dir.resolve("events.jsonl").toAbsolutePath() + "\n");
        assertThat(journal.anchored())
                .extracting(EventLog.Anchored::hash)
                .containsExactly("aa11", "bb22");
        assertThat(runner.only("journalctl").arguments())
                .contains(
                        "--user",
                        "SYSLOG_IDENTIFIER=porelo-events",
                        "PORELO_EVENT_LOG=" + dir.resolve("events.jsonl").toAbsolutePath());
    }

    @Test
    void aLogTheJournalHoldsNothingOfDoesNotHoldEvenFromItsFirstLine() throws Exception {

        // Rotated away or never anchored, a log the journal knows nothing of could be anything.
        final Kept journal = new Kept();
        journal.refusing = true;
        final EventLog log = threeEvents(journal);

        final EventLog.Verification verified = log.verify();

        assertThat(verified.notAnchored()).containsExactly(1, 2, 3);
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void aLineOlderThanTheJournalsOldestOfThisLogIsVouchedForByTheChainAlone() throws Exception {

        // The journal drops its oldest entries first: such a line is older than what it keeps.
        final Path file = dir.resolve("events.jsonl");
        final Kept journal = new Kept();
        journal.refusing = true;
        new EventLog(file, journal).append(EventLog.APPROVED, "uc", "a", "long ago");
        Thread.sleep(5);
        journal.refusing = false;
        final EventLog log = new EventLog(file, journal);
        log.append(EventLog.REJECTED, "uc", "b", "later");

        final EventLog.Verification verified = log.verify();

        assertThat(verified.beforeAnchor()).isEqualTo(1);
        assertThat(verified.notAnchored()).isEmpty();
        assertThat(verified.holds()).isTrue();
    }

    @Test
    void theJournalIsWrittenAndReadOnlyUnderTheLogsLock() throws Exception {

        // Else a verification between a line and its anchor names a line nobody changed.
        final Kept journal = new Kept();
        journal.lock = dir.resolve(".events.lock");
        final EventLog log = threeEvents(journal);

        log.verify();

        assertThat(journal.underLock).hasSize(4).containsOnly(true);
    }

    @Test
    void aJournalThatCannotBeReadIsNeverTakenForOneThatHolds() throws Exception {
        final Kept journal = new Kept();
        final EventLog log = threeEvents(journal);
        journal.unreadable = true;

        final EventLog.Verification verified = log.verify();

        assertThat(verified.anchorRead()).isFalse();
        assertThat(verified.firstBroken()).isZero();
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void aLineAppendedLaterWithAnOldDateIsNamedAllTheSame() throws Exception {

        // The account writes its own file: it can date a line it adds before anything the journal
        // still holds, and chain it on. Only lines ahead of the first anchored one may be older.
        final Path file = dir.resolve("events.jsonl");
        final Kept journal = new Kept();
        journal.refusing = true;
        new EventLog(file, journal).append(EventLog.APPROVED, "uc", "a", "long ago");
        Thread.sleep(5);
        journal.refusing = false;
        final EventLog log = new EventLog(file, journal);
        log.append(EventLog.REJECTED, "uc", "b", "later");
        final List<String> lines = Files.readAllLines(file);
        final String last = (String) ((java.util.Map<?, ?>) Json.parse(lines.get(1))).get("hash");
        final String at = "2000-01-01T00:00:00Z";
        final String hash =
                HashChain.link(last, List.of(at, EventLog.APPROVED, "uc", "c", "forged"));
        Files.writeString(
                file,
                Json.write(
                                new java.util.LinkedHashMap<String, Object>(
                                        java.util.Map.of(
                                                "at",
                                                at,
                                                "kind",
                                                EventLog.APPROVED,
                                                "project",
                                                "uc",
                                                "task",
                                                "c",
                                                "detail",
                                                "forged",
                                                "previous",
                                                last,
                                                "hash",
                                                hash)))
                        + "\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        final EventLog.Verification verified = log.verify();

        assertThat(verified.firstBroken()).isZero();
        assertThat(verified.beforeAnchor()).isEqualTo(1);
        assertThat(verified.notAnchored()).containsExactly(3);
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void aLogReplacedByOneDatedBeforeTheJournalIsNamedLineByLine() throws Exception {
        final Path file = dir.resolve("events.jsonl");
        final Kept journal = new Kept();
        threeEvents(journal);
        Files.delete(file);
        final Kept none = new Kept();
        none.refusing = true;
        final EventLog forged = new EventLog(file, none);
        forged.append(EventLog.APPROVED, "uc", "a", "one");
        forged.append(EventLog.APPROVED, "uc", "a", "two");
        Files.writeString(
                file,
                Files.readString(file)
                        .replaceAll("\"at\":\"[^\"]*\"", "\"at\":\"2000-01-01T00:00:00Z\""),
                StandardCharsets.UTF_8);

        final EventLog.Verification verified = new EventLog(file, journal).verify();

        assertThat(verified.beforeAnchor()).isZero();
        assertThat(verified.notAnchored()).containsExactly(1, 2);
        assertThat(verified.holds()).isFalse();
    }

    @Test
    void aLineWithoutAHashAndAKindIsUnreadableAndClaimsNoDate() throws Exception {
        final EventLog log = threeEvents(new Kept());
        final Path file = dir.resolve("events.jsonl");
        Files.writeString(
                file,
                Files.readString(file)
                        + "{\"at\":\"2000-01-01T00:00:00Z\",\"detail\":\"corrupt\"}\n"
                        + "{\"hash\":null,\"kind\":\"\"}\n",
                StandardCharsets.UTF_8);

        final List<java.util.Map<String, Object>> shown =
                log.entries(java.time.Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(shown).hasSize(5);
        assertThat(shown.subList(3, 5))
                .allSatisfy(
                        entry ->
                                assertThat(entry)
                                        .containsEntry("kind", EventLog.UNREADABLE)
                                        .containsEntry("at", "")
                                        .containsEntry("detail", ""));
    }

    @Test
    void anUnreadableLineIsShownAsSuchAndNeverStopsTheListing() throws Exception {
        final EventLog log = threeEvents(new Kept());
        final Path file = dir.resolve("events.jsonl");
        Files.writeString(
                file, Files.readString(file) + "not a line of the log\n", StandardCharsets.UTF_8);

        assertThat(log.entries(java.time.Instant.parse("2000-01-01T00:00:00Z")))
                .extracting(entry -> entry.get("kind"))
                .containsExactly(
                        EventLog.APPROVED,
                        EventLog.CLEARANCE,
                        EventLog.HAND_IN,
                        EventLog.UNREADABLE);
        assertThat(log.verify().firstBroken()).isEqualTo(4);
    }
}
