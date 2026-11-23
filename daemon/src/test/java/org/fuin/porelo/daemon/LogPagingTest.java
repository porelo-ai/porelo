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
}
