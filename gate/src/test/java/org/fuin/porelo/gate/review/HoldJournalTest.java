package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the journal of items held for the owner account's decision over a real directory: a
 * decision reaches only the content shown and is taken once, a new run at a place lapses what its
 * earlier runs left undecided there and nothing else, no entry is lost to a second writer or to a
 * write cut off, and only the owner account can read it.
 */
class HoldJournalTest {

    private static final String TASK = "shop-2";

    private static final String OTHER_TASK = "farm-1";

    private static final String PLACE = "shop/review-1";

    private static final String OTHER_PLACE = "https://forge.example/shop/app/pull/7";

    private static final String FIRST_HEAD = "1".repeat(40);

    private static final String SECOND_HEAD = "3".repeat(40);

    private static final String CONTAINER = "0123456789abcdef0123";

    private static final int WRITES = 25;

    /** The comment gate's rule an incoming item is withheld by. */
    private static final String RULE = "unknown-author";

    /** Later than every time the ticking clock gives in a test. */
    private static final Instant LATER = Instant.ofEpochSecond(1_900_000_000L);

    @TempDir Path dir;

    @Test
    void aReleaseNamingAnotherDigestIsRefusedAndChangesNothing() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held held = holds.hold(run, TASK, Direction.OUTGOING, comment("why"), null, null);
        final String other = sha256("something else");

        assertThatThrownBy(() -> holds.decide(TASK, held.id(), other, Decision.RELEASE))
                .as("a release naming another digest")
                .isInstanceOf(HoldRefused.class)
                .satisfies(e -> assertRefused(e, HoldRefused.Reason.DIGEST_CHANGED, held.id()));
        assertThat(holds.find(TASK, held.id()))
                .as("the item after the refused release")
                .contains(held);
        assertThat(holds.due(LATER)).as("released items after the refused release").isEmpty();
        assertThat(holds.count(TASK)).as("undecided items after the refused release").isEqualTo(1);
    }

    @Test
    void aNewStartAtTheSamePlaceLapsesAnUndecidedItemAndKeepsAReleasedOne() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run first = run(holds, PLACE, FIRST_HEAD);
        final Held undecided =
                holds.hold(first, TASK, Direction.OUTGOING, comment("open"), null, null);
        final Held released =
                holds.hold(first, TASK, Direction.OUTGOING, comment("go"), null, null);
        final Held incoming = heldIncoming(holds, first, "c-1", reply("t-1", "see"), "alice");
        holds.decide(TASK, released.id(), released.digest(), Decision.RELEASE);

        final int second = holds.startRun(TASK, PLACE);

        assertThat(second).as("the second run's number at the place").isEqualTo(2);
        assertThat(holds.find(TASK, undecided.id()))
                .as("the outgoing item left undecided by the first run")
                .contains(undecided.in(HeldState.LAPSED, null, null));
        assertThat(holds.find(TASK, incoming.id()))
                .as("the incoming item left undecided by the first run")
                .contains(incoming.in(HeldState.LAPSED, RULE, null));
        assertThat(holds.due(LATER))
                .as("the released item, still to be delivered against its own commit")
                .containsExactly(released.in(HeldState.RELEASED, null, null));
        assertThat(holds.due(LATER).getFirst().commit())
                .as("the commit the released item is delivered against")
                .isEqualTo(FIRST_HEAD);
        assertThat(holds.undecided(TASK)).as("undecided items after the new start").isEmpty();
        assertThatThrownBy(
                        () ->
                                holds.decide(
                                        TASK, undecided.id(), undecided.digest(), Decision.RELEASE))
                .as("a decision on a lapsed item")
                .satisfies(e -> assertRefused(e, HoldRefused.Reason.LAPSED, undecided.id()));

        holds.sending(TASK, released.id());
        holds.mark(TASK, released.id(), new WriteOutcome.Delivered("t-1"), LATER);

        assertThat(holds.due(LATER)).as("released items once delivered").isEmpty();
        assertThat(holds.find(TASK, released.id()).map(Held::state))
                .as("the delivered item's state")
                .contains(HeldState.DELIVERED);
    }

    @Test
    void anItemHeldForARunAlreadyFollowedByAnotherLapsesAtOnce() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run first = run(holds, PLACE, FIRST_HEAD);
        holds.startRun(TASK, PLACE);

        final Held late = holds.hold(first, TASK, Direction.OUTGOING, comment("late"), null, null);

        assertThat(late.state())
                .as("an item of a run already followed")
                .isEqualTo(HeldState.LAPSED);
        assertThat(holds.count(TASK)).as("undecided items").isZero();
    }

    @Test
    void aStartAtAnotherPlaceOrOfAnotherTaskLapsesNothing() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held held = holds.hold(run, TASK, Direction.OUTGOING, comment("stay"), null, null);

        assertThat(holds.startRun(TASK, OTHER_PLACE))
                .as("the first run at another place")
                .isEqualTo(1);
        assertThat(holds.startRun(OTHER_TASK, PLACE))
                .as("another task's first run at the same place")
                .isEqualTo(1);

        assertThat(holds.undecided(TASK)).as("the item after both starts").containsExactly(held);
    }

    @Test
    void aDecisionIsTakenOnceAndOnlyOnAnItemOfThatTaskThatFitsItsDirection() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held out = holds.hold(run, TASK, Direction.OUTGOING, comment("out"), null, null);
        final Held in = heldIncoming(holds, run, "c-1", reply("t-1", "in"), "bob");

        assertThatThrownBy(() -> holds.decide(TASK, out.id(), out.digest(), Decision.PASS))
                .as("pass on an outgoing item")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.decide(TASK, in.id(), in.digest(), Decision.RELEASE))
                .as("release on an incoming item")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.decide(OTHER_TASK, out.id(), out.digest(), Decision.RELEASE))
                .as("a decision under another task")
                .satisfies(e -> assertRefused(e, HoldRefused.Reason.NO_SUCH_ITEM, out.id()));
        assertThatThrownBy(() -> holds.decide(TASK, "nothing", out.digest(), Decision.RELEASE))
                .as("a decision on an unknown id")
                .satisfies(e -> assertRefused(e, HoldRefused.Reason.NO_SUCH_ITEM, "nothing"));

        assertThat(holds.decide(TASK, out.id(), out.digest(), Decision.REFUSE))
                .as("the refused item")
                .isEqualTo(out.in(HeldState.REFUSED, null, null));
        assertThat(holds.decide(TASK, in.id(), in.digest(), Decision.DISMISS))
                .as("the dismissed item")
                .isEqualTo(in.in(HeldState.DISMISSED, RULE, null));
        assertThatThrownBy(() -> holds.decide(TASK, out.id(), out.digest(), Decision.RELEASE))
                .as("a second decision")
                .satisfies(e -> assertRefused(e, HoldRefused.Reason.ALREADY_DECIDED, out.id()));
        assertThat(holds.due(LATER)).as("released items after a refusal").isEmpty();
    }

    @Test
    void theDigestIsTheSha256OfTheItemsCanonicalForm() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final ReviewComment comment =
                new ReviewComment(
                        new LineAnchor("src/Main.java", Side.NEW, 3, 4),
                        new Body("Use ä\nhere"),
                        new Suggestion("x"));
        final ReviewComment bare =
                new ReviewComment(
                        new LineAnchor("src/Main.java", Side.OLD, 3, 3), new Body("why"), null);

        // A length counts UTF-8 bytes: the text's ten characters are eleven bytes.
        assertThat(holds.hold(run, TASK, Direction.OUTGOING, comment, null, null).digest())
                .as("a comment with a suggestion")
                .isEqualTo(
                        sha256(
                                "7:comment\n13:src/Main.java\n3:new\n1:3\n1:4\n"
                                        + "11:Use ä\nhere\n1:x\n"));
        assertThat(holds.hold(run, TASK, Direction.OUTGOING, bare, null, null).digest())
                .as("a comment without a suggestion")
                .isEqualTo(sha256("7:comment\n13:src/Main.java\n3:old\n1:3\n1:3\n3:why\n"));
        assertThat(heldIncoming(holds, run, "c-9", reply("t-9", "ok"), "c").digest())
                .as("a reply in one of Porelo's threads")
                .isEqualTo(sha256("5:reply\n3:t-9\n2:ok\n"));
        assertThat(
                        holds.hold(
                                        run,
                                        TASK,
                                        Direction.OUTGOING,
                                        new ForgeReply("PRRT_1", new Body("ok")),
                                        null,
                                        null)
                                .digest())
                .as("a reply in a forge's thread")
                .isEqualTo(sha256("11:forge reply\n6:PRRT_1\n2:ok\n"));
    }

    @Test
    void theCountAndTheUndecidedListFollowDecisionsOldestFirst() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held one = holds.hold(run, TASK, Direction.OUTGOING, comment("one"), null, null);
        final Run theirs = new Run(PLACE, Role.REVIEWER, new Pinned(push(FIRST_HEAD), null), 1);
        holds.startRun(OTHER_TASK, PLACE);
        final Held other =
                holds.hold(theirs, OTHER_TASK, Direction.OUTGOING, comment("o"), null, null);
        final Held two = holds.hold(run, TASK, Direction.OUTGOING, comment("two"), null, null);

        assertThat(holds.count(TASK)).as("undecided items of the task").isEqualTo(2);
        assertThat(holds.undecided(null))
                .as("undecided items of every task, oldest first")
                .containsExactly(one, other, two);

        holds.decide(TASK, one.id(), one.digest(), Decision.RELEASE);

        assertThat(holds.count(TASK)).as("undecided items after one release").isEqualTo(1);
        assertThat(holds.undecided(TASK)).as("the task's undecided items").containsExactly(two);
        assertThat(holds.count("nobody")).as("a task that holds nothing").isZero();
    }

    @Test
    void aNewInstanceOnTheSameDirectoryKeepsEveryItemInItsState() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held released = holds.hold(run, TASK, Direction.OUTGOING, comment("go"), null, null);
        final Held delivered =
                holds.hold(run, TASK, Direction.OUTGOING, comment("gone"), null, null);
        final Held passed = heldIncoming(holds, run, "c-1", reply("t", "p"), "dora");
        final Held waiting = holds.hold(run, TASK, Direction.OUTGOING, comment("wait"), null, null);
        holds.decide(TASK, released.id(), released.digest(), Decision.RELEASE);
        holds.decide(TASK, delivered.id(), delivered.digest(), Decision.RELEASE);
        holds.mark(TASK, delivered.id(), new WriteOutcome.Refused("too long"), LATER);
        holds.decide(TASK, passed.id(), passed.digest(), Decision.PASS);

        final HoldJournal again = new HoldJournal(root);

        assertThat(again.find(TASK, released.id()))
                .as("the released item")
                .contains(released.in(HeldState.RELEASED, null, null));
        assertThat(again.find(TASK, delivered.id()))
                .as("the item marked not delivered")
                .contains(delivered.in(HeldState.NOT_DELIVERED, "too long", null));
        assertThat(again.find(TASK, passed.id()))
                .as("the passed item")
                .contains(passed.in(HeldState.PASSED, RULE, null));
        assertThat(again.undecided(TASK)).as("the undecided item").containsExactly(waiting);
        assertThat(again.startRun(TASK, PLACE)).as("the next run's number").isEqualTo(2);
    }

    @Test
    void twoWritersInTwoThreadsLoseNoEntry() throws Exception {
        final Path root = dir.resolve("holds");
        // Two instances, as the daemon and the command line hold one each.
        writeAtOnce(new HoldJournal(root), new HoldJournal(root), root);
    }

    @Test
    void twoWritersGivenTheStoreUnderTwoSpellingsShareOneLock() throws Exception {
        final Path real = Files.createDirectory(dir.resolve("real"));
        final Path alias = Files.createSymbolicLink(dir.resolve("alias"), real);
        writeAtOnce(
                new HoldJournal(real.resolve("holds")),
                new HoldJournal(alias.resolve("holds")),
                real.resolve("holds"));
    }

    @Test
    void twoProcessesLoseNoEntry() throws Exception {
        final Path root = dir.resolve("holds");
        new HoldJournal(root).startRun(TASK, PLACE);
        final Path barrier = dir.resolve("go");
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final List<Process> writers = new ArrayList<>();
        for (final String label : List.of("left", "right")) {
            writers.add(
                    new ProcessBuilder(
                                    java,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    HoldJournalWriter.class.getName(),
                                    root.toString(),
                                    TASK,
                                    PLACE,
                                    Integer.toString(WRITES),
                                    label,
                                    barrier.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(dir.resolve(label + ".log").toFile())
                            .start());
        }
        // Both wait for this file, so neither runs ahead while the other JVM still starts.
        Files.createFile(barrier);
        for (int i = 0; i < writers.size(); i++) {
            final Process writer = writers.get(i);
            assertThat(writer.waitFor(2, TimeUnit.MINUTES)).as("writer %d ended", i).isTrue();
            assertThat(writer.exitValue())
                    .as(Files.readString(dir.resolve((i == 0 ? "left" : "right") + ".log")))
                    .isZero();
        }

        final HoldJournal reader = new HoldJournal(root);
        assertThat(reader.count(TASK))
                .as("undecided items after both processes")
                .isEqualTo(2 * (WRITES / 2));
        assertThat(reader.due(LATER))
                .as("released items after both processes")
                .hasSize(2 * (WRITES - WRITES / 2));
    }

    @Test
    void aTornLastLineIsIgnoredAndTheJournalStaysReadable() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held kept = holds.hold(run, TASK, Direction.OUTGOING, comment("kept"), null, null);
        final Path journal = root.resolve(TASK).resolve("holds");
        final String whole = Files.readString(journal);
        // A write cut off in its middle: the line has no end, and a multibyte character is split.
        final byte[] cut = "{\"entry\":\"decide\",\"id\":\"ä".getBytes(StandardCharsets.UTF_8);
        Files.write(
                journal, java.util.Arrays.copyOf(cut, cut.length - 1), StandardOpenOption.APPEND);

        final HoldJournal after = new HoldJournal(root, ticking());

        assertThat(after.undecided(TASK)).as("the items before the cut").containsExactly(kept);
        final Held next = after.hold(run, TASK, Direction.OUTGOING, comment("next"), null, null);
        assertThat(new HoldJournal(root).undecided(TASK))
                .as("the items after a write that followed the cut")
                .containsExactly(kept, next);
        assertThat(Files.readString(journal))
                .as("the journal after the repair")
                .startsWith(whole)
                .endsWith("\n")
                .doesNotContain("\"ä");
    }

    @Test
    void everyTasksUndecidedItemsAreListedPastAnUnreadableJournalAndAStrayEntry() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        holds.hold(run, TASK, Direction.OUTGOING, comment("broken"), null, null);
        final Run theirs = new Run(PLACE, Role.REVIEWER, new Pinned(push(FIRST_HEAD), null), 1);
        holds.startRun(OTHER_TASK, PLACE);
        final Held healthy =
                holds.hold(theirs, OTHER_TASK, Direction.OUTGOING, comment("o"), null, null);
        Files.writeString(root.resolve(TASK).resolve("holds"), "not json\n");
        Files.createDirectory(root.resolve(".stray"), ownerOnly());

        final ReviewHolds.Undecided found = holds.everyUndecided();

        assertThat(found.items()).as("the readable task's items").containsExactly(healthy);
        assertThat(found.unreadable())
                .as("the task whose journal cannot be read and the entry that is no task's")
                .containsExactly(".stray", TASK);
        assertThatThrownBy(() -> holds.undecided(null))
                .as("the plain listing of every task still refuses")
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void itemsHeldInTheSameSecondKeepTheOrderTheyWereHeldIn() throws Exception {
        final Instant[] now = {Instant.ofEpochSecond(1_800_000_000L)};
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), settable(now));
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final List<Held> held = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            held.add(holds.hold(run, TASK, Direction.OUTGOING, comment("n" + i), null, null));
            assertThat(holds.everyUndecided().items())
                    .as("after %d items held in one second, each earlier one in its place", i + 1)
                    .containsExactlyElementsOf(held);
        }
    }

    @Test
    void aBrokenOrNewerJournalIsRefusedNeverReadAsEmpty() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        holds.hold(run, TASK, Direction.OUTGOING, comment("one"), null, null);
        final Path journal = root.resolve(TASK).resolve("holds");
        final String whole = Files.readString(journal);

        final String newer = whole.replaceFirst("\"v\":1", "\"v\":2");
        final String unparsable = "not json\n" + whole.substring(whole.indexOf('\n') + 1);
        final String altered = whole.replace("\"body\":\"one\"", "\"body\":\"two\"");
        final String holdLine =
                whole.lines().filter(l -> l.contains("\"hold\"")).findFirst().orElseThrow();
        final String blankPlace = whole + "{\"entry\":\"run\",\"place\":\" \",\"number\":1}\n";
        final String otherTask =
                whole.replace("\"task\":\"" + TASK + "\"", "\"task\":\"" + OTHER_TASK + "\"");
        final String duplicate = whole + holdLine + "\n";
        final String outOfOrder =
                whole + "{\"entry\":\"run\",\"place\":\"" + PLACE + "\",\"number\":5}\n";
        final String notStarted =
                whole.replace(holdLine, holdLine.replace("\"run\":1", "\"run\":2"));
        for (final String broken :
                List.of(
                        newer,
                        unparsable,
                        altered,
                        blankPlace,
                        otherTask,
                        duplicate,
                        outOfOrder,
                        notStarted)) {
            Files.writeString(journal, broken);

            assertThatThrownBy(() -> holds.undecided(TASK))
                    .as("reading %s", broken)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("journal");
            assertThatThrownBy(() -> holds.startRun(TASK, PLACE))
                    .as("starting a run on %s", broken)
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(Files.readString(journal)).as("the journal left").isEqualTo(broken);
        }

        Files.writeString(journal, whole);
        try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            file.setLength(HoldJournal.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> holds.undecided(TASK))
                .as("reading a journal over its limit")
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void aJournalAnotherAccountCanReadIsRefused() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        run(holds, PLACE, FIRST_HEAD);
        final Path journal = root.resolve(TASK).resolve("holds");
        Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rw-r-----"));

        assertThatThrownBy(() -> holds.count(TASK))
                .as("reading a journal another account can read")
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("journal");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(journal)))
                .as("the journal's mode, never narrowed or widened")
                .isEqualTo("rw-r-----");
    }

    @Test
    void aHoldForARunNotStartedAtItsPlaceIsRefused() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run first = run(holds, PLACE, FIRST_HEAD);
        final Run ahead = new Run(PLACE, Role.REVIEWER, new Pinned(push(FIRST_HEAD), null), 2);
        final Run elsewhere =
                new Run(OTHER_PLACE, Role.REVIEWER, new Pinned(push(FIRST_HEAD), null), 1);

        assertThatThrownBy(
                        () -> holds.hold(ahead, TASK, Direction.OUTGOING, comment("a"), null, null))
                .as("a hold for a run above the last started")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                holds.hold(
                                        elsewhere,
                                        TASK,
                                        Direction.OUTGOING,
                                        comment("b"),
                                        null,
                                        null))
                .as("a hold at a place no run started at")
                .isInstanceOf(IllegalStateException.class);
        assertThat(holds.hold(first, TASK, Direction.OUTGOING, comment("c"), null, null).state())
                .as("a hold for the run started")
                .isEqualTo(HeldState.UNDECIDED);
        assertThat(holds.undecided(TASK)).as("undecided items").hasSize(1);
    }

    @Test
    void aCorruptNewerOrOversizedJournalIsRemovedWithoutBeingRead() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Path journal = root.resolve(TASK).resolve("holds");
        for (final String broken : List.of("not json\n", "{\"v\":2,\"entry\":\"holds\"}\n", "")) {
            run(holds, PLACE, FIRST_HEAD);
            if (broken.isEmpty()) {
                try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
                    file.setLength(HoldJournal.MAX_BYTES + 1);
                }
            } else {
                Files.writeString(journal, broken);
            }

            holds.remove(TASK);

            assertThat(journal).as("the journal after removing %s", broken).doesNotExist();
            assertThat(holds.startRun(TASK, PLACE))
                    .as("the first run after the removal")
                    .isEqualTo(1);
            holds.remove(TASK);
        }
    }

    @Test
    void aRateLimitsRetryTimeIsRoundedUpToAWholeSecondAndMovedByASecondLimit() throws Exception {
        final Instant[] now = {Instant.parse("2026-11-25T12:00:00.800Z")};
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), settable(now));
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held minute = released(holds, run, "minute");
        final Held half = released(holds, run, "half");
        holds.sending(TASK, minute.id());
        holds.sending(TASK, half.id());

        holds.mark(TASK, minute.id(), new WriteOutcome.RateLimited(Duration.ofSeconds(60)), now[0]);
        holds.mark(TASK, half.id(), new WriteOutcome.RateLimited(Duration.ofMillis(500)), now[0]);

        assertThat(holds.find(TASK, minute.id()).map(Held::retryAt))
                .as("the retry time after 60 s from .800")
                .contains(Instant.parse("2026-11-25T12:01:01Z"));
        assertThat(holds.find(TASK, half.id()).map(Held::retryAt))
                .as("the retry time after 500 ms from .800")
                .contains(Instant.parse("2026-11-25T12:00:02Z"));

        now[0] = Instant.parse("2026-11-25T12:00:02Z");
        holds.sending(TASK, half.id());
        holds.mark(TASK, half.id(), new WriteOutcome.RateLimited(Duration.ofSeconds(30)), now[0]);

        assertThat(holds.find(TASK, half.id()).map(Held::retryAt))
                .as("the retry time a second limit named")
                .contains(Instant.parse("2026-11-25T12:00:32Z"));
        assertThat(holds.due(now[0])).as("due before either retry time").isEmpty();
    }

    @Test
    void anItemBeingSentIsNeverDueAgainAndOnlyADueItemIsSent() throws Exception {
        final Instant[] now = {LATER};
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, settable(now));
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held left = released(holds, run, "left");
        final Held waiting = released(holds, run, "waiting");
        final Held undecided =
                holds.hold(run, TASK, Direction.OUTGOING, comment("open"), null, null);
        holds.sending(TASK, waiting.id());
        holds.mark(
                TASK, waiting.id(), new WriteOutcome.RateLimited(Duration.ofSeconds(60)), now[0]);

        assertThat(holds.sending(TASK, left.id()))
                .as("the item handed over")
                .isEqualTo(left.in(HeldState.SENDING, null, null));
        assertThatThrownBy(() -> holds.sending(TASK, left.id()))
                .as("sending an item being sent again")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> holds.sending(TASK, waiting.id()))
                .as("sending a retry before its time")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> holds.sending(TASK, undecided.id()))
                .as("sending an undecided item")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> holds.sending(TASK, "nothing"))
                .as("sending an unknown id")
                .isInstanceOf(IllegalStateException.class);

        now[0] = LATER.plus(Duration.ofDays(1));
        final HoldJournal again = new HoldJournal(root, settable(now));
        assertThat(again.find(TASK, left.id()).map(Held::state))
                .as("the item left being sent, read by another instance")
                .contains(HeldState.SENDING);
        assertThat(again.due(now[0]))
                .as("due a day later: the retry, never the item left being sent")
                .extracting(Held::id)
                .containsExactly(waiting.id());
        assertThat(again.sending(TASK, waiting.id()).state())
                .as("the retry handed over once its time passed")
                .isEqualTo(HeldState.SENDING);
    }

    @Test
    void theJournalIsOwnerOnlyAndNeverFollowedThroughALink() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        run(holds, PLACE, FIRST_HEAD);
        final Path task = root.resolve(TASK);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(task)))
                .as("the task's directory")
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(task.resolve("holds"))))
                .as("the journal")
                .isEqualTo("rw-------");

        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        final Path target = Files.writeString(elsewhere.resolve("target"), "untouched");
        Files.createDirectory(root.resolve(OTHER_TASK), ownerOnly());
        Files.createSymbolicLink(root.resolve(OTHER_TASK).resolve("holds"), target);
        assertThatThrownBy(() -> holds.startRun(OTHER_TASK, PLACE))
                .as("a journal that is a link")
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Files.readString(target)).as("the link's target").isEqualTo("untouched");
        assertThat(Files.isSymbolicLink(root.resolve(OTHER_TASK).resolve("holds")))
                .as("the link left")
                .isTrue();

        Files.setPosixFilePermissions(task, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> holds.count(TASK))
                .as("a directory another account can reach")
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void removingATaskDropsItsHoldWholeAndNoOtherTasks() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held held = holds.hold(run, TASK, Direction.OUTGOING, comment("go"), null, null);
        holds.decide(TASK, held.id(), held.digest(), Decision.RELEASE);
        holds.startRun(OTHER_TASK, PLACE);
        final Held theirs =
                holds.hold(
                        new Run(PLACE, Role.AUTHOR, new Pinned(push(SECOND_HEAD), null), 1),
                        OTHER_TASK,
                        Direction.OUTGOING,
                        comment("theirs"),
                        null,
                        null);

        holds.remove(TASK);

        assertThat(holds.find(TASK, held.id())).as("the removed task's item").isEmpty();
        assertThat(holds.due(LATER)).as("released items after the removal").isEmpty();
        assertThat(holds.undecided(null)).as("every undecided item").containsExactly(theirs);
        assertThat(holds.startRun(TASK, PLACE))
                .as("the first run of a task started again under the name")
                .isEqualTo(1);
    }

    @Test
    void aTaskNameThatIsNoneIsRefused() {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"));

        for (final String bad : List.of("..", "a/b", "", "Upper")) {
            assertThatThrownBy(() -> holds.startRun(bad, PLACE))
                    .as("the task name %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(dir.resolve("holds"), LinkOption.NOFOLLOW_LINKS))
                .as("the store after refused names")
                .isFalse();
    }

    @Test
    void anIncomingHoldKeepsWhatItsPlaceGaveWhole() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final ReviewComment item = comment("from the forge");
        final Kept received = received("c-7", item, "erin");

        final Held held = holds.hold(run, TASK, Direction.INCOMING, item, received, RULE);

        final Held again = new HoldJournal(root).find(TASK, held.id()).orElseThrow();
        assertThat(again).as("the incoming item read by another instance").isEqualTo(held);
        assertThat(again.received()).as("what its place gave").isEqualTo(received);
        assertThat(again.from()).as("who wrote it").isEqualTo("erin");
    }

    @Test
    void anIncomingHoldWithoutWhatItsPlaceGaveOrAnOutgoingOneWithItIsRefused() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final ReviewComment item = comment("x");

        assertThatThrownBy(() -> holds.hold(run, TASK, Direction.INCOMING, item, null, null))
                .as("an incoming item without what its place gave")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                holds.hold(
                                        run,
                                        TASK,
                                        Direction.OUTGOING,
                                        item,
                                        received("c-1", item, "erin"),
                                        null))
                .as("an outgoing item with what a place gave")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(holds.undecided(TASK)).as("undecided items after both refusals").isEmpty();
    }

    @Test
    void anIncomingItemIsFoundByItsIdAtThePlaceInItsNewestHold() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run first = run(holds, PLACE, FIRST_HEAD);
        final Held passed = heldIncoming(holds, first, "c-1", reply("t-1", "a"), "erin");
        holds.decide(TASK, passed.id(), passed.digest(), Decision.PASS);
        final Held left = heldIncoming(holds, first, "c-2", reply("t-1", "b"), "erin");
        final Run second = run(holds, PLACE, SECOND_HEAD);
        final Held again = heldIncoming(holds, second, "c-1", reply("t-1", "a"), "erin");

        assertThat(holds.incoming(TASK, PLACE, "c-1"))
                .as("the item held in both runs")
                .contains(again);
        assertThat(holds.incoming(TASK, PLACE, "c-2"))
                .as("the item the second start lapsed")
                .contains(left.in(HeldState.LAPSED, RULE, null));
        assertThat(holds.incoming(TASK, OTHER_PLACE, "c-1")).as("at another place").isEmpty();
        assertThat(holds.incoming(OTHER_TASK, PLACE, "c-1")).as("of another task").isEmpty();
        assertThat(holds.incoming(TASK, PLACE, "c-3")).as("an id never held").isEmpty();
    }

    @Test
    void eachWriteOutcomeIsMarkedAndOnlyARetryWhoseTimePassedComesBack() throws Exception {
        final Path root = dir.resolve("holds");
        final Instant[] clock = {LATER};
        final HoldJournal holds = new HoldJournal(root, settable(clock));
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final List<Held> released = new ArrayList<>();
        for (final String text : List.of("delivered", "refused", "limited", "unknown")) {
            final Held held = holds.hold(run, TASK, Direction.OUTGOING, comment(text), null, null);
            released.add(holds.decide(TASK, held.id(), held.digest(), Decision.RELEASE));
        }
        final Held delivered = released.get(0);
        final Held refused = released.get(1);
        final Held limited = released.get(2);
        final Held unknown = released.get(3);
        final Instant now = LATER;

        assertThat(holds.due(now)).as("due before any write").containsExactlyElementsOf(released);

        holds.sending(TASK, delivered.id());
        holds.mark(TASK, delivered.id(), new WriteOutcome.Delivered("t-9"), now);
        // A refusal before any write, as for a place no longer found, needs no hand-over.
        holds.mark(TASK, refused.id(), new WriteOutcome.Refused("line not in the diff"), now);
        holds.sending(TASK, limited.id());
        holds.mark(TASK, limited.id(), new WriteOutcome.RateLimited(Duration.ofSeconds(60)), now);
        holds.sending(TASK, unknown.id());
        holds.mark(TASK, unknown.id(), new WriteOutcome.OutcomeUnknown(), now);

        clock[0] = now.plusSeconds(60);
        final HoldJournal again = new HoldJournal(root, settable(clock));
        assertThat(again.find(TASK, delivered.id()))
                .as("the delivered item")
                .contains(delivered.in(HeldState.DELIVERED, null, null));
        assertThat(again.find(TASK, refused.id()))
                .as("the item its place refused, with the reason")
                .contains(refused.in(HeldState.NOT_DELIVERED, "line not in the diff", null));
        assertThat(again.find(TASK, limited.id()))
                .as("the rate-limited item, with its retry time")
                .contains(limited.in(HeldState.RETRY, null, now.plusSeconds(60)));
        assertThat(again.find(TASK, unknown.id()))
                .as("the item of unknown outcome")
                .contains(unknown.in(HeldState.OUTCOME_UNKNOWN, null, null));
        assertThat(again.due(now.plusSeconds(59))).as("due before the retry time").isEmpty();
        assertThat(again.due(now.plusSeconds(60)))
                .as("due once the retry time passed")
                .containsExactly(limited.in(HeldState.RETRY, null, now.plusSeconds(60)));

        again.sending(TASK, limited.id());
        again.mark(TASK, limited.id(), new WriteOutcome.Delivered("t-10"), now.plusSeconds(60));

        assertThat(again.due(now.plusSeconds(3600)))
                .as("due after the retry was written")
                .isEmpty();
        assertThat(again.find(TASK, limited.id()).map(Held::state))
                .as("the retried item's state")
                .contains(HeldState.DELIVERED);
    }

    @Test
    void onlyAnItemBeingSentTakesAWritesAnswerAndANeverSentOneOnlyARefusal() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final Held undecided =
                holds.hold(run, TASK, Direction.OUTGOING, comment("wait"), null, null);
        final Held refused = decided(holds, run, "no", Decision.REFUSE);
        final Held delivered = released(holds, run, "gone");
        holds.sending(TASK, delivered.id());
        holds.mark(TASK, delivered.id(), new WriteOutcome.Delivered("t-1"), LATER);
        final Held notDelivered = released(holds, run, "refused");
        holds.mark(TASK, notDelivered.id(), new WriteOutcome.Refused("TooLong"), LATER);
        final Held unknown = released(holds, run, "sent");
        holds.sending(TASK, unknown.id());
        holds.mark(TASK, unknown.id(), new WriteOutcome.OutcomeUnknown(), LATER);
        final Held never = released(holds, run, "never");
        final Held passed = heldIncoming(holds, run, "c-1", reply("t-1", "p"), "erin");
        holds.decide(TASK, passed.id(), passed.digest(), Decision.PASS);
        final Held dismissed = heldIncoming(holds, run, "c-2", reply("t-1", "d"), "erin");
        holds.decide(TASK, dismissed.id(), dismissed.digest(), Decision.DISMISS);
        final Held lapsed = holds.hold(run, TASK, Direction.OUTGOING, comment("old"), null, null);
        holds.startRun(TASK, PLACE);
        final WriteOutcome written = new WriteOutcome.Delivered("t-1");

        for (final Held held :
                List.of(
                        undecided,
                        refused,
                        delivered,
                        notDelivered,
                        unknown,
                        passed,
                        dismissed,
                        lapsed)) {
            final HeldState before = holds.find(TASK, held.id()).orElseThrow().state();
            assertThatThrownBy(() -> holds.mark(TASK, held.id(), written, LATER))
                    .as("marking an item that is %s", before)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(holds.find(TASK, held.id()).map(Held::state))
                    .as("the state of an item that was %s", before)
                    .contains(before);
        }
        assertThatThrownBy(() -> holds.mark(TASK, never.id(), written, LATER))
                .as("a write's answer for a released item never handed over")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> holds.mark(TASK, "nothing", written, LATER))
                .as("marking an unknown id")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> holds.mark(OTHER_TASK, unknown.id(), written, LATER))
                .as("marking under a task that holds nothing")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anIncomingHoldKeepsItsRuleThroughEveryStateAndInAnotherInstance() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root, ticking());
        final Run first = run(holds, PLACE, FIRST_HEAD);
        final Held passed = heldIncoming(holds, first, "c-1", reply("t-1", "a"), "erin");
        final Held dismissed = heldIncoming(holds, first, "c-2", reply("t-1", "b"), "erin");
        final Held lapsed = heldIncoming(holds, first, "c-3", reply("t-1", "c"), "erin");
        holds.decide(TASK, passed.id(), passed.digest(), Decision.PASS);
        holds.decide(TASK, dismissed.id(), dismissed.digest(), Decision.DISMISS);
        holds.startRun(TASK, PLACE);

        final HoldJournal again = new HoldJournal(root);

        assertThat(passed.reason()).as("the rule as held").isEqualTo(RULE);
        for (final Held held : List.of(passed, dismissed, lapsed)) {
            assertThat(again.find(TASK, held.id()).map(Held::reason))
                    .as("the rule of %s, read by another instance", held.item())
                    .contains(RULE);
        }
    }

    @Test
    void anIncomingHoldWithoutItsRuleOrAnOutgoingOneWithARuleIsRefused() throws Exception {
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final Run run = run(holds, PLACE, FIRST_HEAD);
        final ReviewReply item = reply("t-1", "x");
        final Kept received = received("c-1", item, "erin");

        assertThatThrownBy(() -> holds.hold(run, TASK, Direction.INCOMING, item, received, null))
                .as("an incoming item without its rule")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.hold(run, TASK, Direction.INCOMING, item, received, " "))
                .as("an incoming item with a blank rule")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> holds.hold(run, TASK, Direction.OUTGOING, comment("y"), null, RULE))
                .as("an outgoing item with a rule")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(holds.undecided(TASK)).as("undecided items after the refusals").isEmpty();
    }

    // Two writers in two threads, each holding WRITES items and releasing every other one.
    private static void writeAtOnce(
            final HoldJournal left, final HoldJournal right, final Path root) throws Exception {
        final Run run = run(left, PLACE, FIRST_HEAD);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final List<Future<List<Held>>> futures = new ArrayList<>();
            for (final HoldJournal writer : List.of(left, right)) {
                final Callable<List<Held>> writes =
                        () -> {
                            start.await();
                            final List<Held> mine = new ArrayList<>();
                            for (int i = 0; i < WRITES; i++) {
                                final Held held =
                                        writer.hold(
                                                run,
                                                TASK,
                                                Direction.OUTGOING,
                                                comment(Integer.toString(i)),
                                                null,
                                                null);
                                mine.add(held);
                                if (i % 2 == 0) {
                                    writer.decide(TASK, held.id(), held.digest(), Decision.RELEASE);
                                }
                            }
                            return mine;
                        };
                futures.add(pool.submit(writes));
            }
            start.countDown();
            final List<String> ids = new ArrayList<>();
            for (final Future<List<Held>> future : futures) {
                future.get(1, TimeUnit.MINUTES).forEach(h -> ids.add(h.id()));
            }

            final HoldJournal reader = new HoldJournal(root);
            assertThat(reader.count(TASK))
                    .as("undecided items after both writers")
                    .isEqualTo(2 * (WRITES / 2));
            assertThat(reader.due(LATER))
                    .as("released items after both writers")
                    .hasSize(2 * (WRITES - WRITES / 2));
            assertThat(ids)
                    .as("every item written is found")
                    .allSatisfy(
                            id ->
                                    assertThat(reader.find(TASK, id))
                                            .as("the item %s", id)
                                            .isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTaskNamedByItsContainerNameIsHeldAndFound() throws Exception {
        final String container = "porelo-shop_review-2";
        final HoldJournal holds = new HoldJournal(dir.resolve("holds"), ticking());
        final int number = holds.startRun(container, PLACE);
        final Run run = new Run(PLACE, Role.REVIEWER, new Pinned(push(FIRST_HEAD), null), number);

        final Held held = holds.hold(run, container, Direction.OUTGOING, comment("x"), null, null);

        assertThat(new HoldJournal(dir.resolve("holds")).find(container, held.id()))
                .as("the item of a task named by its container name")
                .contains(held);
        assertThat(holds.undecided(null))
                .as("every undecided item, the container's directory listed")
                .containsExactly(held);
    }

    // Helpers -----------------------------------------------------------------------------------

    private static Run run(final HoldJournal holds, final String place, final String head) {
        final int number = holds.startRun(TASK, place);
        return new Run(place, Role.REVIEWER, new Pinned(push(head), null), number);
    }

    private static Held released(final HoldJournal holds, final Run run, final String text)
            throws HoldRefused {
        return decided(holds, run, text, Decision.RELEASE);
    }

    private static Held decided(
            final HoldJournal holds, final Run run, final String text, final Decision decision)
            throws HoldRefused {
        final Held held = holds.hold(run, TASK, Direction.OUTGOING, comment(text), null, null);
        return holds.decide(TASK, held.id(), held.digest(), decision);
    }

    // A clock that reads the instant the test set last.
    private static Clock settable(final Instant[] now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
    }

    private static Held heldIncoming(
            final HoldJournal holds,
            final Run run,
            final String guid,
            final ReviewItem item,
            final String name) {
        return holds.hold(run, TASK, Direction.INCOMING, item, received(guid, item, name), RULE);
    }

    private static Kept received(final String guid, final ReviewItem item, final String name) {
        final String thread =
                switch (item) {
                    case ReviewReply reply -> reply.thread();
                    case ForgeReply reply -> reply.thread();
                    case ReviewComment comment -> "t-" + guid;
                };
        return new Kept(
                guid,
                thread,
                Origin.of(new ForgeAccount("forge.example", "id-" + name, name)),
                item,
                Instant.ofEpochSecond(1_700_000_000L));
    }

    private static Push push(final String head) {
        return new Push("shop", "app", "review-1", head, null, CONTAINER);
    }

    private static ReviewComment comment(final String text) {
        return new ReviewComment(
                new LineAnchor("src/Main.java", Side.NEW, 1, 1), new Body(text), null);
    }

    private static ReviewReply reply(final String thread, final String text) {
        return new ReviewReply(thread, new Body(text));
    }

    private static String sha256(final String text) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static java.nio.file.attribute.FileAttribute<?> ownerOnly() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    private static void assertRefused(
            final Throwable thrown, final HoldRefused.Reason reason, final String id) {
        assertThat(thrown).as("the refusal").isInstanceOf(HoldRefused.class);
        final HoldRefused refused = (HoldRefused) thrown;
        assertThat(refused.reason()).as("the refusal's reason").isEqualTo(reason);
        assertThat(refused.id()).as("the refusal's id").isEqualTo(id);
    }

    // A clock one second further on each reading, so oldest first is an order the test can name.
    private static Clock ticking() {
        final AtomicLong seconds = new AtomicLong(1_800_000_000L);
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(seconds.getAndIncrement());
            }
        };
    }
}
