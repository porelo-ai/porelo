package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.ScreenFile;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client scrolling past what Screen shows pages back through the recording a task's root keeps of
 * its session, over the real socket.
 */
class ScreenPagingTest {

    private static final String TASK = "porelo-uc-shell-1";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private Path state() throws Exception {
        return Files.createDirectories(context().paths().tasks().containerState(TASK));
    }

    /**
     * Leaves a recording as the root's writer leaves it: the bytes from {@code first} segments on,
     * in files of {@link ScreenFile#SEGMENT} bytes named by their place.
     */
    private Path recorded(final int first, final byte[] bytes) throws Exception {
        final Path recording =
                Files.createDirectories(
                        ScreenFile.directory(state()).resolve(ScreenFile.RECORDING));
        for (int at = 0, index = first; at < bytes.length; at += ScreenFile.SEGMENT, index++) {
            Files.write(
                    recording.resolve(name(index)),
                    Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + ScreenFile.SEGMENT)));
        }
        return recording;
    }

    private static String name(final int index) {
        return String.format("%0" + ScreenFile.SUFFIX + "d", index);
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
                thread.join(Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface Body {
        void accept(VarlinkClient client) throws Exception;
    }

    /** The recording's identity now, as Screen gives it; any name for one that is not there. */
    private String of() {
        try {
            return LogIdentity.of(
                    ScreenFile.directory(context().paths().tasks().containerState(TASK))
                            .resolve(ScreenFile.RECORDING));
        } catch (java.io.IOException ex) {
            return "gone";
        }
    }

    private Map<String, Object> before(
            final VarlinkClient client, final long before, final int lines, final boolean escapes) {
        return before(client, of(), before, lines, escapes);
    }

    private static Map<String, Object> before(
            final VarlinkClient client,
            final String of,
            final long before,
            final int lines,
            final boolean escapes) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", TASK);
        parameters.put("of", of);
        parameters.put("before", before);
        parameters.put("lines", lines);
        parameters.put("escapes", escapes);
        return client.call(PoreloDaemon.INTERFACE + ".ScreenBefore", parameters);
    }

    private static List<String> lines(final Map<String, Object> reply) {
        return ((List<?>) reply.get("lines")).stream().map(String.class::cast).toList();
    }

    private static long offset(final Map<String, Object> reply) {
        return ((Number) reply.get("offset")).longValue();
    }

    /**
     * Lines of about 100 bytes, numbered, enough to fill {@code segments} files and part of one.
     */
    private static List<String> session(final double segments) {
        final List<String> lines = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; bytes < segments * ScreenFile.SEGMENT; i++) {
            final String line = "line " + i + " " + "x".repeat(90);
            lines.add(line);
            bytes += line.length() + 1;
        }
        return lines;
    }

    @Test
    void screenGivesTheRecordingsLastLineEndAndPagesWalkBackToTheCut() throws Exception {

        // A client scrolling past what Screen shows saw nothing older; and a session that prints
        // without end is cut at its bound, where paging back says it reached the start.
        final List<String> session = session(3.5);
        final byte[] whole =
                (String.join("\r\n", session) + "\r\npartial, no line end yet")
                        .getBytes(StandardCharsets.UTF_8);
        // The writer removed the first file of the four: what is kept begins one file in.
        final byte[] kept = Arrays.copyOfRange(whole, ScreenFile.SEGMENT, whole.length);
        recorded(1, kept);
        runner.answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\n")
                .answering("capture-pane", "partial, no line end yet\n");

        serving(
                client -> {
                    final Map<String, Object> screen =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".Screen",
                                    Map.of("task", TASK, "last", 5));
                    final long end = ((Number) screen.get("end")).longValue();
                    assertThat(end)
                            .as("after the last whole line, the line still written left to Screen")
                            .isEqualTo(whole.length - "partial, no line end yet".length());

                    final List<String> joined = new ArrayList<>();
                    long position = end;
                    int pages = 0;
                    while (true) {
                        final Map<String, Object> page = before(client, position, 1000, false);
                        joined.addAll(0, lines(page));
                        position = offset(page);
                        if (Boolean.TRUE.equals(page.get("atStart"))) {
                            break;
                        }
                        assertThat(++pages).as("a page that does not move on").isLessThan(50);
                    }
                    assertThat(position).as("the cut").isEqualTo(ScreenFile.SEGMENT);
                    final int cutLine = session.size() - joined.size();
                    assertThat(session.get(cutLine))
                            .as("the line the cut went through, from the cut on")
                            .endsWith(joined.getFirst());
                    assertThat(joined.subList(1, joined.size()))
                            .isEqualTo(session.subList(cutLine + 1, session.size()));

                    final Map<String, Object> older = before(client, 10, 5, false);
                    assertThat(lines(older)).as("older than the cut").isEmpty();
                    assertThat(older).containsEntry("atStart", true);
                });
    }

    @Test
    void aRecordingCutWhileAClientPagesSaysItReachedTheStart() throws Exception {

        // The writer removed the oldest file while a client held a position in it: the client
        // reaches the new start, never lines that are gone or a refusal.
        final List<String> session = session(2.5);
        final byte[] whole = (String.join("\n", session) + "\n").getBytes(StandardCharsets.UTF_8);
        final Path recording = recorded(0, whole);

        serving(
                client -> {
                    final Map<String, Object> page = before(client, whole.length, 1000, false);
                    long position = offset(page);
                    while (position >= ScreenFile.SEGMENT) {
                        position = offset(before(client, position, 1000, false));
                    }
                    assertThat(position).isPositive();
                    Files.delete(recording.resolve(name(0)));

                    final Map<String, Object> cut = before(client, position, 1000, false);
                    assertThat(lines(cut)).isEmpty();
                    assertThat(cut).containsEntry("atStart", true);
                    assertThat(offset(cut)).isEqualTo(ScreenFile.SEGMENT);
                });
    }

    @Test
    void aFileOlderThanTheKeptDaysIsNoLongerKept() throws Exception {

        // records.kept_days says how long a task's records are kept, and the recording is one.
        final List<String> session = session(1.5);
        final byte[] whole = (String.join("\n", session) + "\n").getBytes(StandardCharsets.UTF_8);
        final Path recording = recorded(0, whole);
        Files.setLastModifiedTime(
                recording.resolve(name(0)),
                FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        serving(
                client -> {
                    long position = whole.length;
                    Map<String, Object> page;
                    do {
                        page = before(client, position, 1000, false);
                        position = offset(page);
                    } while (!Boolean.TRUE.equals(page.get("atStart")));
                    assertThat(position).isEqualTo(ScreenFile.SEGMENT);
                    assertThat(recording.resolve(name(0)))
                            .as("a reading method changes nothing")
                            .exists();
                });
    }

    @Test
    void plainTextLeavesTheEscapesOutAndEscapesKeepThem() throws Exception {

        // A client that draws no escapes showed them as text.
        recorded(
                0,
                "\u001b[1;32mgreen\u001b[0m and \u001b]0;title\u0007plain\r\n10%\r20%\r\n"
                        .getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    final long end =
                            "\u001b[1;32mgreen\u001b[0m and \u001b]0;title\u0007plain\r\n10%\r20%\r\n"
                                    .getBytes(StandardCharsets.UTF_8)
                                    .length;
                    assertThat(lines(before(client, end, 5, false)))
                            .isEqualTo(List.of("green and plain", "20%"));
                    assertThat(lines(before(client, end, 5, true)).getFirst())
                            .startsWith("\u001b[1;32mgreen\u001b[0m");
                });
    }

    @Test
    void aPositionNoScreenGaveOrACountOutOfRangeIsRefusedNamingIt() throws Exception {
        recorded(0, "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    for (final long position : List.of(3L, -1L)) {
                        assertThatThrownBy(() -> before(client, position, 5, false))
                                .as("before %d", position)
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getParameters())
                                                        .containsEntry("parameter", "before"));
                    }
                    for (final int count : List.of(0, 1001)) {
                        assertThatThrownBy(() -> before(client, 5, count, false))
                                .as("lines %d", count)
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getParameters())
                                                        .containsEntry("parameter", "lines"));
                    }
                    assertThat(lines(before(client, 5, 5, false))).isEqualTo(List.of("one"));
                });
    }

    @Test
    void aRecordingBegunAnewOrNoneIsNoSuchLog() throws Exception {

        // A new session's recording read from an old one's position would show the one's output
        // as the other's.
        final Path recording = recorded(0, "one\ntwo\n".getBytes(StandardCharsets.UTF_8));

        serving(
                client -> {
                    Files.writeString(recording.resolve(name(0)), "x\n");
                    assertThatThrownBy(() -> before(client, 8, 5, false))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                    Files.delete(recording.resolve(name(0)));
                    assertThatThrownBy(() -> before(client, 2, 5, false))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                });
    }

    @Test
    void aRecordingBegunAnewSinceScreenNamedItIsNoSuchLogThoughThePositionStillFits()
            throws Exception {

        // A new session that wrote past an old session's position was read from it as if it were
        // the old one: Screen names the recording, and a page asked of another is answered as gone.
        final Path recording = recorded(0, "one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        runner.answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\n")
                .answering("capture-pane", "two\n");

        serving(
                client -> {
                    final Map<String, Object> screen =
                            client.call(
                                    PoreloDaemon.INTERFACE + ".Screen",
                                    Map.of("task", TASK, "last", 5));
                    final Object of = screen.get("of");
                    assertThat(of).as("Screen names the recording").isEqualTo(of());
                    assertThat(lines(before(client, String.valueOf(of), 8, 5, false)))
                            .as("the same recording pages back")
                            .isEqualTo(List.of("one", "two"));
                    // As the root's writer begins a new session's recording. The old one is
                    // moved aside until the new one is made, so the new one cannot take its
                    // inode within the same clock tick and pass for it.
                    final Path old = recording.resolveSibling("old-recording");
                    Files.move(recording, old);
                    recorded(0, "abc\ndef\nghi\n".getBytes(StandardCharsets.UTF_8));
                    Files.delete(old.resolve(name(0)));
                    Files.delete(old);
                    assertThatThrownBy(() -> before(client, String.valueOf(of), 8, 5, false))
                            .as("a page of the new recording, asked with the old one's name")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NoSuchLog"));
                });
    }

    @Test
    void screenGivesNoEndWithoutARecording() throws Exception {
        runner.answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\n")
                .answering("capture-pane", "a screen\n");

        serving(
                client ->
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Screen",
                                                Map.of("task", TASK, "last", 5)))
                                .containsEntry("live", true)
                                .containsEntry("of", "")
                                .doesNotContainKey("end"));
    }

    @Test
    void pagesOfEscapesEachFitOneMessage() throws Exception {

        // ESC goes on the wire as six bytes: a page of escapes for a client that draws them was a
        // reply the client refused, so it could never scroll past it.
        final List<String> session = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            session.add(i + "\u001b[1m".repeat(750));
        }
        final byte[] whole =
                (String.join("\r\n", session) + "\r\n").getBytes(StandardCharsets.UTF_8);
        recorded(0, whole);

        serving(
                client -> {
                    final List<String> joined = new ArrayList<>();
                    long position = whole.length;
                    int pages = 0;
                    while (position > 0) {
                        final Map<String, Object> page = before(client, position, 1000, true);
                        assertThat(lines(page)).isNotEmpty();
                        joined.addAll(0, lines(page));
                        assertThat(offset(page)).isLessThan(position);
                        position = offset(page);
                        assertThat(++pages).as("a page that does not move on").isLessThan(100);
                    }
                    assertThat(joined).isEqualTo(session);
                });
    }

    @Test
    void aRecordingThatCannotBeReadIsFailedWithoutItsPath() throws Exception {

        // The answer reaches a client: the path under the state directory is none of its business.
        final Path recording = recorded(0, "one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(recording, java.util.Set.of());
        try {
            serving(
                    client ->
                            assertThatThrownBy(() -> before(client, 4, 5, false))
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
                                                        .doesNotContain(dir.toString());
                                            }));
        } finally {
            Files.setPosixFilePermissions(
                    recording,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }
}
