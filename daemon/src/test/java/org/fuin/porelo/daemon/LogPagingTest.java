package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client scrolling up in a task log pages back from a position the daemon gave it, over the real
 * socket.
 */
class LogPagingTest {

    private static final String TASK = "porelo-uc-shell-1";

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private Path log(final byte[] content) throws Exception {
        final Path state = Files.createDirectories(context().paths().tasks().containerState(TASK));
        return Files.write(state.resolve("task.log"), content);
    }

    private void serving(final Body body) throws Exception {
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context(), socket);
                VarlinkClient client = new VarlinkClient(socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try {
                body.accept(client);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface Body {
        void accept(VarlinkClient client) throws Exception;
    }

    /** The log's identity now, as Tail gives it; any name for a log that is not there. */
    private String of() {
        try {
            return LogIdentity.of(
                    context().paths().tasks().containerState(TASK).resolve("task.log"));
        } catch (java.io.IOException ex) {
            return "gone";
        }
    }

    private Map<String, Object> before(
            final VarlinkClient client,
            final long before,
            final int lines,
            final @Nullable Boolean formatted) {
        return before(client, of(), before, lines, formatted);
    }

    private static Map<String, Object> before(
            final VarlinkClient client,
            final String of,
            final long before,
            final int lines,
            final @Nullable Boolean formatted) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", TASK);
        parameters.put("log", "task.log");
        parameters.put("of", of);
        parameters.put("before", before);
        parameters.put("lines", lines);
        if (formatted != null) {
            parameters.put("formatted", formatted);
        }
        return client.call(PoreloDaemon.INTERFACE + ".LogBefore", parameters);
    }

    private static List<String> lines(final Map<String, Object> reply) {
        return ((List<?>) reply.get("lines")).stream().map(String.class::cast).toList();
    }

    private static long offset(final Map<String, Object> reply) {
        return ((Number) reply.get("offset")).longValue();
    }

    @Test
    void pagesWalkBackToTheStartAndJoinToTheWholeLog() throws Exception {

        // A client scrolling up past what Tail sent saw nothing before it: the lines it pages back
        // through must be the log's own, none lost or repeated, a line longer than a read included.
        final List<String> written = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            written.add(i == 7 ? "y".repeat(150_000) : i == 11 ? "" : "line " + i + " é");
        }
        log((String.join("\n", written) + "\n").getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final Map<String, Object> tail =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".Tail",
                                    Map.of("task", TASK, "log", "task.log", "last", 3));
                    final List<String> joined = new ArrayList<>(lines(tail));
                    long position = offset(tail);
                    int pages = 0;
                    while (true) {
                        final Map<String, Object> page = before(client, position, 4, null);
                        assertThat(lines(page)).hasSizeLessThanOrEqualTo(4).isNotEmpty();
                        joined.addAll(0, lines(page));
                        assertThat(offset(page)).isLessThan(position);
                        position = offset(page);
                        pages++;
                        assertThat(page.get("atStart"))
                                .as("at the start exactly when the offset is 0")
                                .isEqualTo(position == 0);
                        if (position == 0) {
                            break;
                        }
                        assertThat(pages).as("a page that does not move on").isLessThan(20);
                    }
                    assertThat(joined).isEqualTo(written);
                    assertThat(pages).isEqualTo(5);

                    final Map<String, Object> none = before(client, 0, 5, null);
                    assertThat(lines(none)).isEmpty();
                    assertThat(none).containsEntry("atStart", true);
                    assertThat(offset(none)).isZero();
                });
    }

    @Test
    void aPositionThatIsNoLineStartIsRefusedNamingIt() throws Exception {

        // A guess from the middle of a line would show half a line as if it were the log's.
        log("one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    for (final long position : List.of(2L, 5L, -4L)) {
                        assertThatThrownBy(() -> before(client, position, 5, null))
                                .as("before %d", position)
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex -> {
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            "org.varlink.service.InvalidParameter");
                                            assertThat(ex.getParameters())
                                                    .containsEntry("parameter", "before");
                                        });
                    }
                    assertThat(lines(before(client, 8, 5, null))).isEqualTo(List.of("one", "two"));
                });
    }

    @Test
    void aCountOutsideOneToAThousandIsRefusedNamingIt() throws Exception {

        // No count is a page of nothing that reads as no more lines; an unbounded one is the whole
        // log in one reply.
        log("one\ntwo\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    for (final int count : List.of(0, 1001, -1)) {
                        assertThatThrownBy(() -> before(client, 8, count, null))
                                .as("lines %d", count)
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex -> {
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            "org.varlink.service.InvalidParameter");
                                            assertThat(ex.getParameters())
                                                    .containsEntry("parameter", "lines");
                                        });
                    }
                    assertThat(lines(before(client, 8, 1000, null)))
                            .isEqualTo(List.of("one", "two"));
                    assertThat(lines(before(client, 8, 1, null))).isEqualTo(List.of("two"));
                });
    }

    @Test
    void aLogReplacedSinceThePositionWasGivenIsNoSuchLog() throws Exception {

        // A new run's log read from an old run's position would show lines of the one as the other.
        final Path log = log("first run, one\nfirst run, two\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final long end = Files.size(log);
                    Files.writeString(log, "new\n");
                    assertThatThrownBy(() -> before(client, end, 5, null))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                    Files.delete(log);
                    assertThatThrownBy(() -> before(client, 4, 5, null))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                });
    }

    @Test
    void aLogMadeAnewSinceTailNamedItIsNoSuchLogThoughThePositionStillFits() throws Exception {

        // A new run's log that grew past an old run's position was read from it as if it were the
        // old one: Tail names the log, and a page asked of another is answered as gone.
        final Path log = log("one\ntwo\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final Map<String, Object> tail =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".Tail",
                                    Map.of("task", TASK, "log", "task.log", "last", 1));
                    final Object of = tail.get("of");
                    assertThat(of).as("Tail names the log").isEqualTo(of());
                    assertThat(lines(before(client, String.valueOf(of), 8, 5, null)))
                            .as("the same log pages back")
                            .isEqualTo(List.of("one", "two"));
                    final Path made = log.resolveSibling("task.log.next");
                    Files.writeString(made, "abc\ndef\nghi\n");
                    Files.move(made, log, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    assertThatThrownBy(() -> before(client, String.valueOf(of), 8, 5, null))
                            .as("a page of the log made anew, asked with the old one's name")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                });
    }

    @Test
    void aFormattedPageFromATaskWhoseAgentIsNotHereComesAsItIs() throws Exception {
        log("one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client ->
                        assertThat(lines(before(client, 8, 5, true)))
                                .isEqualTo(List.of("one", "two")));
    }

    @Test
    void aFormattedPageLeavesTheLinesTheAgentHidesOut() throws Exception {

        // The page goes through the same formatting as Tail: a hidden line shown when scrolling up
        // is a line the agent's own view never shows.
        final Path log =
                log("shown one\nhidden\nshown two\nlast\n".getBytes(StandardCharsets.UTF_8));

        final Map<String, Object> page =
                TaskMethods.logBefore(
                        log,
                        LogIdentity.of(log),
                        "shown one\nhidden\nshown two\n".length(),
                        5,
                        raw ->
                                raw.stream()
                                        .map(line -> line.equals("hidden") ? null : "> " + line)
                                        .toList(),
                        TASK,
                        "task.log");

        assertThat(page.get("lines")).isEqualTo(List.of("> shown one", "> shown two"));
        assertThat(page).containsEntry("offset", 0L).containsEntry("atStart", true);
    }

    /** The size of a reply as it goes on the wire, without the NUL that ends it. */
    private static int encoded(final Map<String, Object> reply) {
        return org.fuin.porelo.wire.Json.write(Map.of("parameters", reply))
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }

    @Test
    void pagesOfLinesThatEscapeSixfoldEachFitOneMessage() throws Exception {

        // A control character goes on the wire as six bytes: a page of 4 MiB of them was a reply
        // the client refused, so it could never scroll past it.
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            written.add(i + "\u0001".repeat(3000));
        }
        final Path log = log((String.join("\n", written) + "\n").getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final List<String> joined = new ArrayList<>();
                    long position = Files.size(log);
                    int pages = 0;
                    while (position > 0) {
                        final Map<String, Object> page = before(client, position, 1000, null);
                        assertThat(lines(page)).isNotEmpty();
                        joined.addAll(0, lines(page));
                        assertThat(offset(page)).isLessThan(position);
                        position = offset(page);
                        assertThat(++pages).as("a page that does not move on").isLessThan(100);
                    }
                    assertThat(joined).isEqualTo(written);
                });
    }

    @Test
    void aLineLongerThanAPageComesInPiecesThatEachFitOneMessageAndJoinToIt() throws Exception {

        // One line without end, all of it escaped on the wire, still arrives: in pieces read back
        // from its end, never refused, and none of it lost.
        final String endless = "\u0001".repeat(5 * 1024 * 1024);
        log((endless + "\nlast\n").getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final StringBuilder joined = new StringBuilder();
                    long position = endless.length() + 1L;
                    int pages = 0;
                    while (true) {
                        final Map<String, Object> page = before(client, position, 1000, null);
                        assertThat(lines(page)).hasSize(1);
                        assertThat(lines(page).getFirst()).isNotEmpty();
                        joined.insert(0, lines(page).getFirst());
                        assertThat(offset(page)).isLessThan(position);
                        position = offset(page);
                        assertThat(++pages).as("a page that does not move on").isLessThan(20);
                        if (Boolean.TRUE.equals(page.get("atStart"))) {
                            break;
                        }
                    }
                    assertThat(position).isZero();
                    assertThat(joined.toString()).isEqualTo(endless);
                });
    }

    /** A log of one line, its end as its last byte or none, as large as asked, counting reads. */
    private static final class OneLongLine implements LogPages.Bytes {

        private final long size;

        private final boolean ended;

        private final java.util.concurrent.atomic.AtomicLong read =
                new java.util.concurrent.atomic.AtomicLong();

        OneLongLine(final long size, final boolean ended) {
            this.size = size;
            this.ended = ended;
        }

        @Override
        public long start() {
            return 0;
        }

        @Override
        public long end() {
            return size;
        }

        @Override
        public void read(final long position, final byte[] into, final int length) {
            read.addAndGet(length);
            for (int i = 0; i < length; i++) {
                into[i] = ended && position + i == size - 1 ? (byte) '\n' : (byte) 'x';
            }
        }
    }

    @Test
    void aPageOfALineWithoutEndReadsAPageNotTheWholeLine() throws Exception {

        // Paging scanned a log of one giant line in full at every call, back to its start, looking
        // for where the line begins: the scan is bounded by the page.
        final long size = 256L * 1024 * 1024;
        final OneLongLine log = new OneLongLine(size, true);
        final java.util.function.Supplier<VarlinkException> gone =
                () -> new VarlinkException(PoreloDaemon.INTERFACE + ".NoSuchLog", Map.of());

        final LogPages.Page page = LogPages.before(log, size, 1000, gone);

        assertThat(page.lines()).singleElement().asString().hasSize(LogPages.PIECE);
        assertThat(page.offset()).isEqualTo(size - 1 - LogPages.PIECE);
        assertThat(page.atStart()).isFalse();
        final long bound = 3L * LogPages.MOST_BYTES + 2L * LogPages.BLOCK;
        assertThat(log.read.get()).as("bytes read for one page").isLessThanOrEqualTo(bound);

        log.read.set(0);
        final LogPages.Page next = LogPages.before(log, page.offset(), 1000, gone);
        assertThat(next.offset()).isEqualTo(page.offset() - LogPages.PIECE);
        assertThat(log.read.get()).as("bytes read for the next").isLessThanOrEqualTo(bound);

        final OneLongLine unended = new OneLongLine(size, false);
        assertThat(LogPages.lineEnd(unended))
                .as("the recording's last line end, looked for no further back than a page")
                .isEqualTo(size - LogPages.PIECE);
        assertThat(unended.read.get()).isLessThanOrEqualTo(bound);
    }

    @Test
    void tailsStartOfALogWithoutLineEndsReadsAPageNotTheWholeLog() throws Exception {

        // Tail looked for the start of a log's last lines back to the log's start: a log with no
        // line end, sparse or huge, was read whole at every Tail. Bounded as paging is.
        final long size = 256L * 1024 * 1024;
        final long bound = 2L * LogPages.MOST_BYTES + 2L * LogPages.BLOCK;

        final OneLongLine ended = new OneLongLine(size, true);
        assertThat(LogPages.startOfLast(ended, 20))
                .as("the piece of the last line that ends it")
                .isEqualTo(size - 1 - LogPages.PIECE);
        assertThat(ended.read.get()).as("bytes read").isLessThanOrEqualTo(bound);

        final OneLongLine unended = new OneLongLine(size, false);
        assertThat(LogPages.startOfLast(unended, 20)).isEqualTo(size - LogPages.PIECE);
        assertThat(unended.read.get()).as("bytes read").isLessThanOrEqualTo(bound);

        final java.util.function.Supplier<VarlinkException> gone =
                () -> new VarlinkException(PoreloDaemon.INTERFACE + ".NoSuchLog", Map.of());
        assertThat(LogPages.before(ended, size - 1 - LogPages.PIECE, 1, gone).offset())
                .as("a position paging back goes on from")
                .isEqualTo(size - 1 - 2L * LogPages.PIECE);
    }

    @Test
    void aLongLineOfWideCharactersComesInPiecesThatJoinToItWithNoneSplit() throws Exception {

        // A piece cut at a page's bytes could start inside a character of two, three or four
        // bytes, which then read as a replacement character on both sides of the cut.
        final String line = "\u00e9\u20ac\ud83d\ude00".repeat(200_000);
        final Path log = log((line + "\n").getBytes(StandardCharsets.UTF_8));

        final StringBuilder joined = new StringBuilder();
        long position = Files.size(log);
        int pages = 0;
        while (position > 0) {
            final Map<String, Object> page =
                    TaskMethods.logBefore(
                            log, LogIdentity.of(log), position, 1000, null, TASK, "task.log");
            assertThat(lines(page)).hasSize(1);
            joined.insert(0, lines(page).getFirst());
            assertThat(offset(page)).isLessThan(position);
            position = offset(page);
            assertThat(++pages).as("a page that does not move on").isLessThan(20);
        }
        assertThat(pages).as("in pieces").isGreaterThan(1);
        assertThat(joined.toString()).doesNotContain("\ufffd").isEqualTo(line);
    }

    @Test
    void aFormattedPageFitsOneMessageThoughTheAgentLengthensItsLines() throws Exception {

        // The agent's formatter lengthens a line after the page was cut: the bound holds for what
        // is sent, and the next page goes on from the first line sent.
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            written.add("line " + i + " " + "x".repeat(3000));
        }
        final Path log = log((String.join("\n", written) + "\n").getBytes(StandardCharsets.UTF_8));
        final java.util.function.Function<List<String>, List<@Nullable String>> eightfold =
                raw -> raw.stream().<@Nullable String>map(line -> line.repeat(8)).toList();

        final List<String> joined = new ArrayList<>();
        long position = Files.size(log);
        int pages = 0;
        while (position > 0) {
            final Map<String, Object> page =
                    TaskMethods.logBefore(
                            log, LogIdentity.of(log), position, 1000, eightfold, TASK, "task.log");
            assertThat(encoded(page))
                    .as("the reply as sent")
                    .isLessThanOrEqualTo(
                            org.fuin.porelo.wire.varlink.VarlinkConnection.MAX_MESSAGE);
            joined.addAll(0, lines(page));
            assertThat(offset(page)).isLessThan(position);
            position = offset(page);
            assertThat(++pages).as("a page that does not move on").isLessThan(100);
        }
        assertThat(joined).isEqualTo(written.stream().map(line -> line.repeat(8)).toList());
    }

    @Test
    void tailsFirstReplyFitsOneMessageThoughTheAgentLengthensAnEscapeHeavyLog() throws Exception {

        // A control character goes on the wire as six bytes, and the agent's formatter lengthens
        // a line after it was read: Tail's opening reply was bounded by the bytes read alone, and a
        // client refused it. Every reply fits, and the replies together give every line once.
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            written.add(i + "\u0001".repeat(3000));
        }
        final Path log = log((String.join("\n", written) + "\n").getBytes(StandardCharsets.UTF_8));
        final java.util.function.Function<List<String>, List<@Nullable String>> sixteenfold =
                raw -> raw.stream().<@Nullable String>map(line -> line.repeat(16)).toList();

        final List<String> joined = new ArrayList<>();
        long position = 0;
        int replies = 0;
        while (position < Files.size(log)) {
            final TaskMethods.Tailed tailed = TaskMethods.tail(log, position, sixteenfold);
            assertThat(
                            encoded(
                                    Map.of(
                                            "lines",
                                            tailed.lines(),
                                            "offset",
                                            tailed.offset(),
                                            "of",
                                            "0".repeat(32))))
                    .as("reply %d as sent", replies)
                    .isLessThanOrEqualTo(
                            org.fuin.porelo.wire.varlink.VarlinkConnection.MAX_MESSAGE);
            assertThat(tailed.offset())
                    .as("the reply starts where the last one ended")
                    .isEqualTo(position);
            joined.addAll(tailed.lines());
            assertThat(tailed.next()).as("a reply that does not move on").isGreaterThan(position);
            position = tailed.next();
            assertThat(++replies).as("replies").isLessThan(2000);
        }
        assertThat(joined).isEqualTo(written.stream().map(line -> line.repeat(16)).toList());
    }

    @Test
    void aLogThatCannotBeReadIsFailedWithoutItsPath() throws Exception {

        // The answer reaches a client: the path under the state directory is none of its business.
        final Path log = log("one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(log, java.util.Set.of());
        try {
            serving(
                    client ->
                            assertThatThrownBy(() -> before(client, 4, 5, null))
                                    .isInstanceOfSatisfying(
                                            VarlinkException.class,
                                            ex -> {
                                                assertThat(ex.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE + ".Failed");
                                                assertThat(
                                                                String.valueOf(
                                                                        ex.getParameters()
                                                                                .get("message")))
                                                        .isNotBlank()
                                                        .doesNotContain(dir.toString())
                                                        .doesNotContain("task.log");
                                            }));
        } finally {
            Files.setPosixFilePermissions(
                    log, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void aLogShortenedUnderTheReadIsNoSuchLogAsOneGone() {

        // The log was cut between its size being read and its bytes: the position belongs to what
        // was there, as for a log gone.
        final LogPages.Bytes shortened =
                new LogPages.Bytes() {
                    @Override
                    public long start() {
                        return 0;
                    }

                    @Override
                    public long end() {
                        return 100;
                    }

                    @Override
                    public void read(final long position, final byte[] into, final int length)
                            throws java.io.IOException {
                        throw new java.io.EOFException();
                    }
                };

        assertThatThrownBy(
                        () ->
                                LogPages.read(
                                        shortened,
                                        50,
                                        5,
                                        () ->
                                                new VarlinkException(
                                                        PoreloDaemon.INTERFACE + ".NoSuchLog",
                                                        Map.of()),
                                        "The log could not be read"))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchLog"));
    }

    @Test
    void aLogThatIsALinkIsNoSuchLog() throws Exception {

        // A link in the state directory would let the daemon read a file elsewhere as the log.
        final Path elsewhere = Files.writeString(dir.resolve("elsewhere.txt"), "secret\nmore\n");
        final Path state = Files.createDirectories(context().paths().tasks().containerState(TASK));
        Files.createSymbolicLink(state.resolve("task.log"), elsewhere);

        serving(
                client -> {
                    assertThatThrownBy(() -> before(client, 7, 5, null))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    PoreloDaemon.INTERFACE + ".Tail",
                                                    Map.of("task", TASK, "log", "task.log")))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                });
    }
}
