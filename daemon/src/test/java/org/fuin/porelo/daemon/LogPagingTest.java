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

    private static Map<String, Object> before(
            final VarlinkClient client,
            final long before,
            final int lines,
            final @Nullable Boolean formatted) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", TASK);
        parameters.put("log", "task.log");
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
    void aLineLongerThanAPageComesCutToWhatOneMessageCarries() throws Exception {

        // One line without end, all of it escaped on the wire, still arrives: cut, never refused.
        final String endless = "\u0001".repeat(5 * 1024 * 1024);
        log((endless + "\nlast\n").getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final Map<String, Object> page =
                            before(client, endless.length() + 1L, 1000, null);
                    assertThat(lines(page)).hasSize(1);
                    assertThat(lines(page).getFirst()).isNotEmpty();
                    assertThat(endless).startsWith(lines(page).getFirst());
                    assertThat(offset(page)).isZero();
                    assertThat(page).containsEntry("atStart", true);
                });
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
                    TaskMethods.logBefore(log, position, 1000, eightfold, TASK, "task.log");
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
