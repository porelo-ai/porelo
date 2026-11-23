package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.fuin.porelo.app.HostPullRequestReader;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that a project's open reviews reach a client in pages that each fit one message. */
class PullRequestPagingTest {

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));
    private final PullRequestPages pages = new PullRequestPages(now::get);

    @Test
    void twentyThousandRowsComeInPagesThatTogetherGiveAll() {
        // A project's open reviews with long titles fill several messages.
        final List<HostPullRequestReader.Pull> open = pulls(1, 15_000, "open");
        final List<HostPullRequestReader.Pull> ended = pulls(15_001, 5_000, "merged");

        final Map<String, Object> first =
                pages.first("demo", new HostPullRequestReader.Round(open, ended));

        final List<Object> openRead = new ArrayList<>((List<?>) first.get("pullRequests"));
        final List<Object> endedRead = new ArrayList<>((List<?>) first.get("ended"));
        int largest = ReviewCut.bytes(first);
        int count = 1;
        final Map<?, ?> paged = (Map<?, ?>) first.get("paged");
        assertThat(paged).as("the first reply says it is paged").isNotNull();
        assertThat(paged.get("pullRequests")).as("open rows in the reading").isEqualTo(15_000);
        assertThat(paged.get("ended")).as("ended rows in the reading").isEqualTo(5_000);
        Object next = paged.get("next");
        final String reading = String.valueOf(paged.get("reading"));
        while (next != null) {
            final Map<String, Object> page =
                    pages.page("demo", reading, ((Number) next).longValue());
            count++;
            largest = Math.max(largest, ReviewCut.bytes(page));
            openRead.addAll((List<?>) page.get("pullRequests"));
            endedRead.addAll((List<?>) page.get("ended"));
            next = page.get("next");
        }

        assertThat(openRead)
                .as("every open row once, in order")
                .isEqualTo(open.stream().map(HostPullRequestReader.Pull::asMap).toList());
        assertThat(endedRead)
                .as("every ended row once, in order")
                .isEqualTo(ended.stream().map(HostPullRequestReader.Pull::asMap).toList());
        assertThat(count).as("more than one page").isGreaterThan(1);
        assertThat(largest)
                .as("every page fits one message")
                .isLessThanOrEqualTo(VarlinkConnection.MAX_MESSAGE);
    }

    @Test
    void aReadingThatFitsIsAnsweredWholeAndNotKept() {
        // A client reading the first reply alone gets the whole list as before.
        final Map<String, Object> first =
                pages.first(
                        "demo", new HostPullRequestReader.Round(pulls(1, 3, "open"), List.of()));

        assertThat(first).as("no paging").doesNotContainKey("paged");
        assertThat((List<?>) first.get("pullRequests")).hasSize(3);
        assertThat(pages.kept()).as("nothing kept for pages").isZero();
    }

    @Test
    void aPositionAtOrPastTheReadingsEndIsInvalid() {
        final String reading = reading();

        for (final long from : List.of(20_000L, 25_000L, -1L)) {
            assertThatThrownBy(() -> pages.page("demo", reading, from))
                    .as("from %d", from)
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            error -> {
                                assertThat(error.getErrorName())
                                        .isEqualTo("org.varlink.service.InvalidParameter");
                                assertThat(error.getParameters())
                                        .containsEntry("parameter", "from");
                            });
        }
    }

    @Test
    void aReadingIsGoneAfterTenMinutesAndForAnotherProject() {
        final String reading = reading();

        assertThatThrownBy(() -> pages.page("other", reading, 1))
                .as("another project's reading is none")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error ->
                                assertThat(error.getErrorName())
                                        .isEqualTo(
                                                PoreloDaemon.INTERFACE
                                                        + ".PullRequestsReadingGone"));
        now.set(now.get().plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThatThrownBy(() -> pages.page("demo", reading, 1))
                .as("ten minutes after its first reply")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".PullRequestsReadingGone");
                            assertThat(error.getParameters())
                                    .containsEntry("project", "demo")
                                    .containsEntry("reading", reading);
                        });
    }

    @Test
    void onlyTheNewestReadingsAreKept() {
        // A client asking again and again never makes the daemon hold more than a few readings.
        final String oldest = reading();
        for (int i = 1; i < PullRequestPages.MOST_READINGS; i++) {
            reading();
        }
        assertThat(pages.kept()).isEqualTo(PullRequestPages.MOST_READINGS);

        reading();

        assertThat(pages.kept()).as("still the most").isEqualTo(PullRequestPages.MOST_READINGS);
        assertThatThrownBy(() -> pages.page("demo", oldest, 1))
                .as("the oldest gave way")
                .isInstanceOf(VarlinkException.class);
    }

    @Test
    void theDaemonAnswersAPageOfAReadingItDoesNotHoldByName(@TempDir final Path dir)
            throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".PullRequestsPage",
                                                Map.of(
                                                        "project",
                                                        "demo",
                                                        "reading",
                                                        "0".repeat(32),
                                                        "from",
                                                        1)))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                error ->
                                        assertThat(error.getErrorName())
                                                .isEqualTo(
                                                        PoreloDaemon.INTERFACE
                                                                + ".PullRequestsReadingGone"));
            }
        }
        assertThat(McpMethods.NEVER)
                .as("a page hands out what the person's identity read")
                .contains("PullRequestsPage", "RecordCalls");
    }

    @Test
    void aWatchSnapshotAboveOneMessageSendsItsFirstRowsAndPagesTheRest() throws Exception {
        // The frontend reads open reviews only through the watch: a large project must reach it.
        final List<HostPullRequestReader.Pull> open = pulls(1, 15_000, "open");
        final List<HostPullRequestReader.Pull> ended = pulls(15_001, 5_000, "merged");
        final var large = new HostPullRequestReader.Round(open, ended);
        final var small = new HostPullRequestReader.Round(pulls(1, 3, "open"), List.of());
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        final var replies = new Captured(true, 2);

        GateMethods.watchPullRequests(
                () -> {},
                previous -> reads.getAndIncrement() < 2 ? large : small,
                pages,
                "demo",
                replies,
                () -> {});

        assertThat(replies.events).as("the large snapshot, then the small one").hasSize(2);
        assertThat(reads.get()).as("an unchanged large snapshot is not sent again").isEqualTo(3);
        assertThat(pages.kept()).as("one reading for the one snapshot sent").isEqualTo(1);
        assertThat(replies.last).as("the watch goes on").isFalse();
        final Map<String, Object> first = replies.events.get(0);
        assertThat(ReviewCut.bytes(first))
                .as("the first snapshot fits one message")
                .isLessThanOrEqualTo(VarlinkConnection.MAX_MESSAGE);
        final Map<?, ?> paged = (Map<?, ?>) first.get("paged");
        assertThat(paged).as("the snapshot says it is paged").isNotNull();
        assertThat(paged.get("pullRequests")).as("open rows in the snapshot").isEqualTo(15_000);
        assertThat(paged.get("ended")).as("ended rows in the snapshot").isEqualTo(5_000);
        final List<List<Object>> read = readAll(first);
        assertThat(read.get(0))
                .as("every open row once, in order")
                .isEqualTo(open.stream().map(HostPullRequestReader.Pull::asMap).toList());
        assertThat(read.get(1))
                .as("every ended row once, in order")
                .isEqualTo(ended.stream().map(HostPullRequestReader.Pull::asMap).toList());
        assertThat(replies.events.get(1))
                .as("a snapshot that fits is whole and names no reading")
                .doesNotContainKey("paged")
                .containsEntry(
                        "pullRequests",
                        small.open().stream().map(HostPullRequestReader.Pull::asMap).toList());
    }

    @Test
    void withoutMoreALargeSnapshotIsPagedAsPullRequests() throws Exception {
        final List<HostPullRequestReader.Pull> open = pulls(1, 20_000, "open");
        final var replies = new Captured(false, 1);

        GateMethods.watchPullRequests(
                () -> {},
                previous -> new HostPullRequestReader.Round(open, List.of()),
                pages,
                "demo",
                replies,
                () -> {});

        assertThat(replies.last).as("one final reply").isTrue();
        assertThat(readAll(replies.events.get(0)).get(0))
                .as("its pages give every row")
                .isEqualTo(open.stream().map(HostPullRequestReader.Pull::asMap).toList());
    }

    @Test
    void anOlderSnapshotPagesWhileNewerOnesAreKeptUntilItsReadingIsGone() throws Exception {
        // A client still paging one snapshot when the next arrives must not get mixed rows.
        final List<List<HostPullRequestReader.Pull>> snapshots =
                List.of(
                        pulls(1, 20_000, "open", 'a'),
                        pulls(1, 20_000, "open", 'b'),
                        pulls(1, 20_000, "open", 'c'));
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        final var replies = new Captured(true, 3);
        final Instant start = now.get();

        GateMethods.watchPullRequests(
                () -> {},
                previous ->
                        new HostPullRequestReader.Round(
                                snapshots.get(reads.getAndIncrement()), List.of()),
                pages,
                "demo",
                replies,
                () -> now.set(now.get().plus(Duration.ofMinutes(1))));

        assertThat(replies.events).hasSize(3);
        assertThat(pages.kept()).as("a reading per snapshot").isEqualTo(3);
        assertThat(readAll(replies.events.get(0)).get(0))
                .as("the oldest snapshot still pages, its own rows")
                .isEqualTo(
                        snapshots.get(0).stream().map(HostPullRequestReader.Pull::asMap).toList());

        now.set(start.plus(PullRequestPages.KEPT));

        final Map<?, ?> oldest = (Map<?, ?>) replies.events.get(0).get("paged");
        assertThatThrownBy(
                        () ->
                                pages.page(
                                        "demo",
                                        String.valueOf(oldest.get("reading")),
                                        ((Number) oldest.get("next")).longValue()))
                .as("the oldest snapshot's reading is no longer kept")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error ->
                                assertThat(error.getErrorName())
                                        .isEqualTo(
                                                PoreloDaemon.INTERFACE
                                                        + ".PullRequestsReadingGone"));
        assertThat(readAll(replies.events.get(2)).get(0))
                .as("the newest snapshot still pages")
                .isEqualTo(
                        snapshots.get(2).stream().map(HostPullRequestReader.Pull::asMap).toList());
    }

    /** Reads a reply's rows and every page of its reading, open and ended. */
    private List<List<Object>> readAll(final Map<String, Object> first) {
        final List<Object> open = new ArrayList<>((List<?>) first.get("pullRequests"));
        final List<Object> ended = new ArrayList<>((List<?>) first.get("ended"));
        final Map<?, ?> paged = (Map<?, ?>) first.get("paged");
        assertThat(paged).as("the reply is paged").isNotNull();
        final String reading = String.valueOf(paged.get("reading"));
        Object next = paged.get("next");
        while (next != null) {
            final Map<String, Object> page =
                    pages.page("demo", reading, ((Number) next).longValue());
            assertThat(ReviewCut.bytes(page))
                    .as("every page fits one message")
                    .isLessThanOrEqualTo(VarlinkConnection.MAX_MESSAGE);
            open.addAll((List<?>) page.get("pullRequests"));
            ended.addAll((List<?>) page.get("ended"));
            next = page.get("next");
        }
        assertThat(open)
                .as("exactly paged.pullRequests rows")
                .hasSize((Integer) paged.get("pullRequests"));
        assertThat(ended).as("exactly paged.ended rows").hasSize((Integer) paged.get("ended"));
        return List.of(open, ended);
    }

    private static final class Captured implements VarlinkServer.Replies {
        private final boolean streaming;
        private final int limit;
        private final List<Map<String, Object>> events = new ArrayList<>();
        private boolean last;

        Captured(final boolean streaming, final int limit) {
            this.streaming = streaming;
            this.limit = limit;
        }

        @Override
        public void more(final Map<String, Object> reply) {
            events.add(reply);
        }

        @Override
        public void last(final Map<String, Object> reply) {
            last = true;
            events.add(reply);
        }

        @Override
        public boolean streaming() {
            return streaming;
        }

        @Override
        public boolean open() {
            return events.size() < limit;
        }
    }

    private String reading() {
        final Map<String, Object> first =
                pages.first(
                        "demo",
                        new HostPullRequestReader.Round(pulls(1, 20_000, "open"), List.of()));
        return String.valueOf(((Map<?, ?>) first.get("paged")).get("reading"));
    }

    private static List<HostPullRequestReader.Pull> pulls(
            final long first, final int count, final String state) {
        return pulls(first, count, state, 't');
    }

    private static List<HostPullRequestReader.Pull> pulls(
            final long first, final int count, final String state, final char title) {
        return LongStream.range(first, first + count)
                .mapToObj(
                        n ->
                                new HostPullRequestReader.Pull(
                                        "repo",
                                        n,
                                        String.valueOf(title).repeat(300),
                                        "a".repeat(40),
                                        "Author <author@example.org>",
                                        "Committer <committer@example.org>",
                                        List.of(),
                                        state))
                .toList();
    }
}
