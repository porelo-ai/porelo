package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the host's journal of a push's review threads over a real directory: no comment, reply or
 * thread state is lost or written twice, none reaches a task started later under the same name, a
 * broken journal is never read as an empty one, and nothing is followed through a symbolic link or
 * left readable by another account.
 */
class ReviewCommentsTest {

    private static final String CONTAINER = "0123456789abcdef0123";

    private static final String OTHER_CONTAINER = "fedcba9876543210fedc";

    private static final String FIRST_HEAD = "1".repeat(40);

    private static final String SECOND_HEAD = "3".repeat(40);

    private static final String BASE = "2".repeat(40);

    private static final Origin OWNER = Origin.of(new OwnerAccount());

    private static final Origin TASK = Origin.of(new Task("shop-2"));

    @TempDir Path dir;

    @Test
    void aCommentAndAReplyAreReadBackAsOneThreadByAnotherInstance() throws Exception {
        final Path root = dir.resolve("review");
        final Kept comment =
                new ReviewComments(root)
                        .keep(push(CONTAINER, FIRST_HEAD), comment("why"), OWNER, null);
        final Kept reply =
                new ReviewComments(root)
                        .keep(
                                push(CONTAINER, FIRST_HEAD),
                                new ReviewReply(comment.thread(), new Body("because")),
                                TASK,
                                null);

        final List<ReviewThread> threads =
                new ReviewComments(root).threads(push(CONTAINER, FIRST_HEAD));

        assertThat(threads)
                .containsExactly(
                        new ReviewThread(
                                comment.thread(), ThreadState.OPEN, List.of(comment, reply)));
        assertThat(reply.thread()).isEqualTo(comment.thread());
        assertThat(reply.origin().producer().words()).isEqualTo("task shop-2");
        assertThat(comment.guid()).isNotEqualTo(reply.guid());
        assertThat(journal(root, CONTAINER)).isRegularFile();
    }

    @Test
    void aThreadStaysReadableAfterARejectAndALaterPushOfTheSameName() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Kept first = store.keep(push(CONTAINER, FIRST_HEAD), comment("first"), OWNER, null);

        // Rejected; the task pushes the same name again at another head.
        final Push later = push(CONTAINER, SECOND_HEAD);
        final Kept reply =
                store.keep(later, new ReviewReply(first.thread(), new Body("fixed")), TASK, null);
        final Kept second = store.keep(later, comment("second"), OWNER, null);

        assertThat(new ReviewComments(root).threads(later))
                .containsExactly(
                        new ReviewThread(first.thread(), ThreadState.OPEN, List.of(first, reply)),
                        new ReviewThread(second.thread(), ThreadState.OPEN, List.of(second)));
        final String journal = Files.readString(journal(root, CONTAINER));
        assertThat(journal).contains(FIRST_HEAD).contains(SECOND_HEAD).contains(BASE);
    }

    @Test
    void aTaskStartedUnderAReusedNameOrAnotherPushSeesNoThread() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Kept kept = store.keep(push(CONTAINER, FIRST_HEAD), comment("old"), OWNER, null);

        // The same task name, project, repository and push name, in a new container.
        final Push reused = push(OTHER_CONTAINER, FIRST_HEAD);
        assertThat(store.threads(reused)).isEmpty();
        assertThat(store.of(reused).openThreads()).isZero();
        assertThatThrownBy(
                        () ->
                                store.keep(
                                        reused,
                                        new ReviewReply(kept.thread(), new Body("mine?")),
                                        TASK,
                                        null))
                .isInstanceOf(ReviewRefused.class)
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));
        assertThatThrownBy(() -> store.state(reused, kept.thread(), ThreadState.RESOLVED))
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));

        final Push otherRepository = new Push("shop", "lib", "main", FIRST_HEAD, BASE, CONTAINER);
        final Push otherProject = new Push("farm", "app", "main", FIRST_HEAD, BASE, CONTAINER);
        final Push otherName = new Push("shop", "app", "other", FIRST_HEAD, BASE, CONTAINER);
        assertThat(store.threads(otherRepository)).isEmpty();
        assertThat(store.threads(otherProject)).isEmpty();
        assertThat(store.threads(otherName)).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.keep(
                                        otherName,
                                        new ReviewReply(kept.thread(), new Body("there?")),
                                        TASK,
                                        null))
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));
        assertThatThrownBy(
                        () ->
                                store.keep(
                                        otherRepository,
                                        new ReviewReply(kept.thread(), new Body("here?")),
                                        TASK,
                                        null))
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));

        assertThat(store.threads(push(CONTAINER, SECOND_HEAD))).hasSize(1);
    }

    @Test
    void aRetryAnswersTheSameKeptAndAnotherItemUnderItsIdIsRefused() throws Exception {
        final Path root = dir.resolve("review");
        final Push push = push(CONTAINER, FIRST_HEAD);
        final Kept first = new ReviewComments(root).keep(push, comment("once"), OWNER, "r-1");

        // After a restart, or from the command line: a new instance.
        final ReviewComments again = new ReviewComments(root);
        assertThat(again.keep(push, comment("once"), OWNER, "r-1")).isEqualTo(first);
        assertThatThrownBy(() -> again.keep(push, comment("twice"), OWNER, "r-1"))
                .satisfies(e -> assertRefused(e, ReviewError.ID_REUSED));
        assertThatThrownBy(() -> again.keep(push, comment("once"), TASK, "r-1"))
                .satisfies(e -> assertRefused(e, ReviewError.ID_REUSED));

        assertThat(again.threads(push)).hasSize(1);
        assertThat(Files.readAllLines(journal(root, CONTAINER))).hasSize(1);
    }

    @Test
    void twoWritersInTwoThreadsLoseNoEntry() throws Exception {
        final Path root = dir.resolve("review");
        // Two instances, as the daemon and the command line hold one each.
        writeAtOnce(new ReviewComments(root), new ReviewComments(root));

        assertThat(bodies(new ReviewComments(root).threads(push(CONTAINER, FIRST_HEAD))))
                .isEqualTo(expectedBodies());
    }

    @Test
    void twoWritersGivenTheStoreUnderTwoSpellingsShareOneLock() throws Exception {
        final Path real = Files.createDirectory(dir.resolve("real"));
        final Path alias = Files.createSymbolicLink(dir.resolve("alias"), real);
        writeAtOnce(
                new ReviewComments(real.resolve("review")),
                new ReviewComments(alias.resolve("review")));

        assertThat(
                        bodies(
                                new ReviewComments(real.resolve("review"))
                                        .threads(push(CONTAINER, FIRST_HEAD))))
                .isEqualTo(expectedBodies());
    }

    @Test
    void twoProcessesLoseNoEntry() throws Exception {
        final Path root = dir.resolve("review");
        final int each = WRITES;
        final Path barrier = dir.resolve("go");
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final List<Process> writers = new ArrayList<>();
        for (final String label : List.of("left", "right")) {
            writers.add(
                    new ProcessBuilder(
                                    java,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    ReviewCommentsWriter.class.getName(),
                                    root.toString(),
                                    CONTAINER,
                                    Integer.toString(each),
                                    label,
                                    barrier.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(dir.resolve(label + ".log").toFile())
                            .start());
        }
        // Both wait for this file, so neither runs ahead while the other JVM still starts.
        Files.createFile(barrier);
        for (final Process writer : writers) {
            assertThat(writer.waitFor(2, TimeUnit.MINUTES)).as("the writer ended").isTrue();
        }
        for (final String label : List.of("left", "right")) {
            assertThat(writers.get(label.equals("left") ? 0 : 1).exitValue())
                    .as(Files.readString(dir.resolve(label + ".log")))
                    .isZero();
        }

        assertThat(bodies(new ReviewComments(root).threads(push(CONTAINER, FIRST_HEAD))))
                .isEqualTo(expectedBodies());
    }

    @Test
    void aTemporaryLeftByAnInterruptedWriteIsRemovedAndNeverRead() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Kept kept = store.keep(push(CONTAINER, FIRST_HEAD), comment("kept"), OWNER, null);
        final Path temporary = journal(root, CONTAINER).resolveSibling("journal.tmp");
        final String half = Files.readString(journal(root, CONTAINER));
        Files.writeString(temporary, half + half.substring(0, half.length() / 2));

        assertThat(store.threads(push(CONTAINER, FIRST_HEAD)))
                .containsExactly(new ReviewThread(kept.thread(), ThreadState.OPEN, List.of(kept)));
        assertThat(temporary).doesNotExist();

        Files.writeString(temporary, "{\"broken");
        final Kept next = store.keep(push(CONTAINER, FIRST_HEAD), comment("next"), OWNER, null);
        assertThat(store.threads(push(CONTAINER, FIRST_HEAD)))
                .extracting(ReviewThread::id)
                .containsExactly(kept.thread(), next.thread());
        assertThat(temporary).doesNotExist();
    }

    @Test
    void aTruncatedUnparsableOrNewerJournalIsRefusedNeverReadAsEmpty() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Push push = push(CONTAINER, FIRST_HEAD);
        store.keep(push, comment("one"), OWNER, null);
        store.keep(push, comment("two"), OWNER, null);
        final Path journal = journal(root, CONTAINER);
        final String whole = Files.readString(journal);

        final String truncated = whole.substring(0, whole.length() - 5);
        final String unparsable = whole + "not json\n";
        final String newer = whole.replace("{\"v\":1,", "{\"v\":2,");
        final String missingField = whole.replaceFirst("\"head\":\"1{40}\",", "");
        for (final String broken : List.of(truncated, unparsable, newer, missingField, "")) {
            Files.writeString(journal, broken);

            assertThatThrownBy(() -> store.threads(push))
                    .as("reading %s", broken)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("journal");
            assertThatThrownBy(() -> store.of(push)).isInstanceOf(UncheckedIOException.class);
            assertThatThrownBy(() -> store.keep(push, comment("three"), OWNER, null))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(Files.readString(journal)).isEqualTo(broken);
        }
    }

    @Test
    void noPartOfTheStoreIsFollowedThroughASymbolicLink() throws Exception {
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        final Path target = Files.writeString(elsewhere.resolve("target"), "untouched");

        final Path linkedRoot = Files.createSymbolicLink(dir.resolve("linked"), elsewhere);
        assertLinkRefused(new ReviewComments(linkedRoot), elsewhere);

        final Path root = dir.resolve("review");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve(CONTAINER), elsewhere);
        assertLinkRefused(new ReviewComments(root), elsewhere);
        Files.delete(root.resolve(CONTAINER));

        for (final String file : List.of("journal", "journal.lock")) {
            final Path container = ownerOnly(Files.createDirectory(root.resolve(CONTAINER)));
            Files.createSymbolicLink(container.resolve(file), target);
            assertLinkRefused(new ReviewComments(root), elsewhere);
            assertThat(Files.readString(target)).isEqualTo("untouched");
            Files.delete(container.resolve(file));
            Files.deleteIfExists(container.resolve("journal.lock"));
            Files.delete(container);
        }

        // A temporary that is a link is removed as a link; what it names is left alone.
        final Path container = ownerOnly(Files.createDirectory(root.resolve(CONTAINER)));
        Files.createSymbolicLink(container.resolve("journal.tmp"), target);
        new ReviewComments(root).keep(push(CONTAINER, FIRST_HEAD), comment("here"), OWNER, null);
        assertThat(Files.readString(target)).isEqualTo("untouched");
        assertThat(Files.isRegularFile(container.resolve("journal"), LinkOption.NOFOLLOW_LINKS))
                .isTrue();
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).containsExactly(target);
        }
    }

    @Test
    void theStoreIsReadableByItsOwnerAlone() throws Exception {
        final Path root = dir.resolve("state").resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Kept kept = store.keep(push(CONTAINER, FIRST_HEAD), comment("mode"), OWNER, null);
        store.state(push(CONTAINER, FIRST_HEAD), kept.thread(), ThreadState.RESOLVED);

        final Path container = root.resolve(CONTAINER);
        // The store made the parent too, so it is owner-only as well.
        assertThat(mode(root.getParent())).isEqualTo("rwx------");
        assertThat(mode(root)).isEqualTo("rwx------");
        assertThat(mode(container)).isEqualTo("rwx------");
        assertThat(mode(container.resolve("journal"))).isEqualTo("rw-------");
        assertThat(mode(container.resolve("journal.lock"))).isEqualTo("rw-------");
        try (var listed = Files.list(container)) {
            assertThat(listed.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("journal", "journal.lock");
        }
    }

    @Test
    void aContainerDirectoryOthersCanReachIsRefusedAndLeftAsItIs() throws Exception {
        final Path root = dir.resolve("review");
        final Path container = Files.createDirectories(root.resolve(CONTAINER));
        Files.setPosixFilePermissions(container, PosixFilePermissions.fromString("rwxr-x---"));
        final ReviewComments store = new ReviewComments(root);

        assertThatThrownBy(() -> store.keep(push(CONTAINER, FIRST_HEAD), comment("x"), OWNER, null))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(CONTAINER);
        assertThatThrownBy(() -> store.threads(push(CONTAINER, FIRST_HEAD)))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(mode(container)).isEqualTo("rwxr-x---");
        try (var listed = Files.list(container)) {
            assertThat(listed).isEmpty();
        }
    }

    @Test
    void aRetryIdThatIsTooLongOrHoldsAnyOtherCharacterIsRefused() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Push push = push(CONTAINER, FIRST_HEAD);
        for (final String id :
                List.of("a".repeat(129), "", "a\nb", "a b", "a/b", "\u00e4", "a\u202eb")) {
            assertThatThrownBy(() -> store.keep(push, comment("x"), OWNER, id))
                    .as("id %s", id)
                    .satisfies(e -> assertRefused(e, ReviewError.TEXT_REFUSED, "id"));
        }
        assertThat(store.threads(push)).isEmpty();

        final String longest = "Az09._:-".repeat(16);
        final Kept kept = store.keep(push, comment("x"), OWNER, longest);
        assertThat(store.keep(push, comment("x"), OWNER, longest)).isEqualTo(kept);
    }

    @Test
    void aBrokenEntryOfAnyPushRefusesTheWholeJournal() throws Exception {
        final Path root = dir.resolve("review");
        final ReviewComments store = new ReviewComments(root);
        final Push mine = push(CONTAINER, FIRST_HEAD);
        final Push other = new Push("shop", "app", "other", FIRST_HEAD, BASE, CONTAINER);
        store.keep(mine, comment("mine"), OWNER, null);
        final Kept theirs = store.keep(other, comment("theirs"), OWNER, null);
        store.state(other, theirs.thread(), ThreadState.RESOLVED);
        final Path journal = journal(root, CONTAINER);
        final List<String> lines = Files.readAllLines(journal);
        final String fresh = UUID.randomUUID().toString();

        final List<String> broken =
                List.of(
                        // The other push's thread opened twice.
                        lines.get(0) + "\n" + lines.get(1) + "\n" + lines.get(1) + "\n",
                        // A reply into a thread never opened.
                        lines.get(0)
                                + "\n"
                                + lines.get(1)
                                        .replace("\"kind\":\"comment\"", "\"kind\":\"reply\"")
                                        .replace(theirs.thread(), fresh)
                                + "\n",
                        // A state before its thread's comment.
                        lines.get(0) + "\n" + lines.get(2) + "\n" + lines.get(1) + "\n",
                        // A reply of one push in another push's thread.
                        lines.get(0)
                                + "\n"
                                + lines.get(1)
                                + "\n"
                                + lines.get(1)
                                        .replace("\"kind\":\"comment\"", "\"kind\":\"reply\"")
                                        .replace("\"name\":\"other\"", "\"name\":\"main\"")
                                + "\n",
                        // Fields no push can have.
                        lines.get(0)
                                + "\n"
                                + lines.get(1).replace(FIRST_HEAD, "not-a-commit")
                                + "\n",
                        lines.get(0) + "\n" + lines.get(1).replace(BASE, "2") + "\n",
                        lines.get(0)
                                + "\n"
                                + lines.get(1).replace("\"project\":\"shop\"", "\"project\":\" \"")
                                + "\n");
        for (final String text : broken) {
            assertThat(text)
                    .as("the case changed the journal")
                    .isNotIn(String.join("\n", lines) + "\n");
            Files.writeString(journal, text);

            assertThatThrownBy(() -> store.threads(mine))
                    .as("reading %s", text)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("journal");
            assertThatThrownBy(() -> store.keep(mine, comment("more"), OWNER, null))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(Files.readString(journal)).isEqualTo(text);
        }
    }

    @Test
    void aThreadIsResolvedAndReopenedAsJournaledStates() throws Exception {
        final Path root = dir.resolve("review");
        final Push push = push(CONTAINER, FIRST_HEAD);
        final ReviewComments store = new ReviewComments(root);
        final Kept first = store.keep(push, comment("first"), OWNER, null);
        final Kept second = store.keep(push, comment("second"), OWNER, null);

        final ReviewThread resolved = store.state(push, first.thread(), ThreadState.RESOLVED);
        assertThat(resolved)
                .isEqualTo(new ReviewThread(first.thread(), ThreadState.RESOLVED, List.of(first)));

        // A reply joins a resolved thread without opening it again.
        final Kept reply =
                store.keep(push, new ReviewReply(first.thread(), new Body("still")), TASK, null);
        assertThat(new ReviewComments(root).threads(push))
                .containsExactly(
                        new ReviewThread(
                                first.thread(), ThreadState.RESOLVED, List.of(first, reply)),
                        new ReviewThread(second.thread(), ThreadState.OPEN, List.of(second)));
        assertThat(new ReviewComments(root).of(push).openThreads()).isEqualTo(1);

        assertThat(store.state(push, first.thread(), ThreadState.OPEN).state())
                .isEqualTo(ThreadState.OPEN);
        assertThat(new ReviewComments(root).of(push).openThreads()).isEqualTo(2);
        // Resolving cleared nothing: every item is still there.
        assertThat(new ReviewComments(root).threads(push).get(0).items())
                .containsExactly(first, reply);

        assertThatThrownBy(
                        () -> store.state(push, UUID.randomUUID().toString(), ThreadState.RESOLVED))
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));
        assertThatThrownBy(
                        () ->
                                new ReviewComments(dir.resolve("empty"))
                                        .state(push, first.thread(), ThreadState.RESOLVED))
                .satisfies(e -> assertRefused(e, ReviewError.NO_SUCH_THREAD));
    }

    @Test
    void aFullJournalRefusesTheNextItemAsTooLarge() throws Exception {
        final Path root = dir.resolve("review");
        final Push push = push(CONTAINER, FIRST_HEAD);
        final ReviewComments store = new ReviewComments(root);
        store.keep(push, comment("seed"), OWNER, null);
        final Path journal = journal(root, CONTAINER);
        final String line = Files.readString(journal);
        final Pattern ids =
                Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        final StringBuilder full = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            final Matcher matcher = ids.matcher(line);
            full.append(matcher.replaceAll(match -> UUID.randomUUID().toString()));
        }
        Files.writeString(journal, full);

        assertThat(store.threads(push)).hasSize(10_000);
        assertThatThrownBy(() -> store.keep(push, comment("one more"), OWNER, null))
                .satisfies(e -> assertRefused(e, ReviewError.TOO_LARGE, "journal"));
        assertThat(Files.readString(journal)).isEqualTo(full.toString());

        // The bytes: two items of nine MiB do not fit in sixteen.
        final Push other = push(OTHER_CONTAINER, FIRST_HEAD);
        final String nine = "x".repeat(9 * 1024 * 1024);
        store.keep(other, comment(nine), OWNER, null);
        assertThatThrownBy(() -> store.keep(other, comment(nine), OWNER, null))
                .satisfies(e -> assertRefused(e, ReviewError.TOO_LARGE, "journal"));
        assertThat(store.threads(other)).hasSize(1);
    }

    @Test
    void theCountsNameTheOwnerAccountsRunOnlyOnceThereIsAComment() throws Exception {
        final Path root = dir.resolve("review");
        final Push push = push(CONTAINER, FIRST_HEAD);
        final ReviewComments store = new ReviewComments(root);
        assertThat(store.of(push)).isEqualTo(new ReviewCounts.Counts(0, 0, List.of()));
        assertThat(root).doesNotExist();

        final Kept first = store.keep(push, comment("first"), OWNER, null);
        store.keep(push, comment("second"), TASK, null);
        store.state(push, first.thread(), ThreadState.RESOLVED);

        assertThat(new ReviewComments(root).of(push))
                .isEqualTo(
                        new ReviewCounts.Counts(
                                0,
                                1,
                                List.of(
                                        new ReviewCounts.Run(
                                                "owner account", Completeness.COMPLETE, 0))));
    }

    private static final int WRITES = 60;

    private static void writeAtOnce(final ReviewComments left, final ReviewComments right)
            throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final List<Future<Void>> writers = new ArrayList<>();
            for (final String label : List.of("left", "right")) {
                final ReviewComments store = label.equals("left") ? left : right;
                final Callable<Void> writer =
                        () -> {
                            start.await();
                            for (int i = 0; i < WRITES; i++) {
                                store.keep(
                                        push(CONTAINER, FIRST_HEAD),
                                        new ReviewComment(
                                                new LineAnchor("a.txt", Side.NEW, 1, 1),
                                                new Body(label + " " + i),
                                                null),
                                        OWNER,
                                        null);
                            }
                            return null;
                        };
                writers.add(pool.submit(writer));
            }
            start.countDown();
            for (final Future<Void> writer : writers) {
                writer.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<String> expectedBodies() {
        final Set<String> bodies = new HashSet<>();
        for (int i = 0; i < WRITES; i++) {
            bodies.add("left " + i);
            bodies.add("right " + i);
        }
        return bodies;
    }

    // The opening comments' bodies, refusing a body kept twice.
    private static Set<String> bodies(final List<ReviewThread> threads) {
        final Set<String> bodies = new HashSet<>();
        for (final ReviewThread thread : threads) {
            final String body = thread.items().get(0).item().body().text();
            assertThat(bodies.add(body)).as("kept once: %s", body).isTrue();
        }
        return bodies;
    }

    private static void assertLinkRefused(final ReviewComments store, final Path elsewhere)
            throws IOException {
        final Push push = push(CONTAINER, FIRST_HEAD);
        assertThatThrownBy(() -> store.keep(push, comment("through"), OWNER, null))
                .isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.threads(push)).isInstanceOf(UncheckedIOException.class);
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed.map(path -> path.getFileName().toString())).containsExactly("target");
        }
    }

    private static void assertRefused(final Throwable thrown, final ReviewError error) {
        assertRefused(thrown, error, null);
    }

    private static void assertRefused(
            final Throwable thrown, final ReviewError error, final @Nullable String detail) {
        assertThat(thrown).isInstanceOf(ReviewRefused.class);
        assertThat(((ReviewRefused) thrown).refusal()).isEqualTo(new ReviewRefusal(error, detail));
    }

    // A container directory as the store makes it, so only the link is left to refuse.
    @Test
    void eachItemKeepsTheTimeItWasWrittenInWholeSecondsAndAnItemWithoutOneReadsAsUntimed()
            throws Exception {
        final Path root = dir.resolve("review");
        final Instant written = Instant.parse("2026-11-25T10:15:30.750Z");
        final ReviewComments store = new ReviewComments(root, Clock.fixed(written, ZoneOffset.UTC));
        final Push push = push(CONTAINER, FIRST_HEAD);
        final Kept kept = store.keep(push, comment("when"), OWNER, null);
        final Path journal = journal(root, CONTAINER);

        assertThat(kept.at())
                .as("the time the item was kept, cut to whole seconds")
                .isEqualTo(Instant.parse("2026-11-25T10:15:30Z"));
        assertThat(Files.readString(journal))
                .as("the journal holds the time in UTC, RFC 3339, whole seconds")
                .contains("\"at\":\"2026-11-25T10:15:30Z\"");
        assertThat(new ReviewComments(root).threads(push).getFirst().items())
                .as("another instance reads the time back")
                .containsExactly(kept);

        // An item written before items carried a time.
        Files.writeString(
                journal, Files.readString(journal).replace(",\"at\":\"2026-11-25T10:15:30Z\"", ""));
        assertThat(new ReviewComments(root).threads(push).getFirst().items().getFirst().at())
                .as("an item written without a time reads as untimed, never as now")
                .isNull();

        Files.writeString(
                journal,
                Files.readString(journal).replace("\"item\":{", "\"at\":\"yesterday\",\"item\":{"));
        assertThatThrownBy(() -> new ReviewComments(root).threads(push))
                .as("a time that is none refuses the journal")
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("journal");
    }

    private static Path ownerOnly(final Path directory) throws IOException {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private static String mode(final Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static Path journal(final Path root, final String container) {
        return root.resolve(container).resolve("journal");
    }

    private static Push push(final String container, final String head) {
        return new Push("shop", "app", "main", head, BASE, container);
    }

    private static ReviewComment comment(final String text) {
        return new ReviewComment(
                new LineAnchor("src/a.txt", Side.NEW, 2, 3),
                new Body(text),
                new Suggestion("instead\n"));
    }
}
