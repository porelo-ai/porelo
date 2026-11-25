package org.fuin.porelo.build.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.Json;

/**
 * A forge adapter the tests start that writes its frames by hand, so it can answer what no {@link
 * ReviewReader} built on the server could: a frame that is not JSON, a page past its bound, facts
 * no record holds, an error with a null field.
 *
 * <p>It answers {@code Describe} as version 1 of forge {@code fake}. Every other answer is chosen
 * by the call's key - a post's document, a page's cursor, a pull request's address, a branch - from
 * {@link #ANSWERS}; a key it does not know is answered with {@code not-json}.
 */
public final class RawReviewAdapter {

    /** A text past every bound a cursor or field has. */
    static final String LONG = "x".repeat(300);

    /** A decision time as the contract writes one. */
    private static final String TIME = "2026-11-20T10:15:30Z";

    /** Each key's whole frame, without its NUL. */
    static final Map<String, String> ANSWERS =
            Map.ofEntries(
                    Map.entry(
                            "huge",
                            Json.write(
                                    Map.of(
                                            "parameters",
                                            Map.of(
                                                    "document",
                                                    "y"
                                                            .repeat(
                                                                    ReviewProtocol.MOST_PAGE_BYTES
                                                                            + 1))))),
                    Map.entry(
                            "long-cursor",
                            Json.write(
                                    Map.of(
                                            "parameters",
                                            Map.of("document", "{}", "cursor", LONG)))),
                    Map.entry(
                            "control-cursor",
                            Json.write(
                                    Map.of(
                                            "parameters",
                                            Map.of("document", "{}", "cursor", "a\nb")))),
                    Map.entry("thread-empty", thread("")),
                    Map.entry("thread-letters", thread("abc")),
                    Map.entry("thread-65", thread("1".repeat(65))),
                    Map.entry("thread-missing", "{\"parameters\":{}}"),
                    Map.entry(
                            "field-null",
                            "{\"error\":\"org.fuin.porelo.Review1.NotInProfile\","
                                    + "\"parameters\":{\"field\":null}}"),
                    Map.entry(
                            "field-long",
                            Json.write(
                                    Map.of(
                                            "error",
                                            "org.fuin.porelo.Review1.NotInProfile",
                                            "parameters",
                                            Map.of("field", LONG)))),
                    Map.entry(
                            "outcome-unknown",
                            "{\"error\":\"org.fuin.porelo.Review1.WriteOutcomeUnknown\","
                                    + "\"parameters\":{}}"),
                    Map.entry("evil", url("http://evil.example/o/n/pull/1")),
                    Map.entry("file", url("file:///etc/passwd")),
                    Map.entry("blank", url(" ")),
                    Map.entry("number", "{\"parameters\":{\"url\":7}}"),
                    Map.entry("dots", url("https://forge.example/o/../pull/1")),
                    Map.entry(
                            "https://forge.example/o/n/pull/1",
                            facts("https://user:pass@forge.example/o/n", "open", null, null)),
                    Map.entry(
                            "https://forge.example/o/n/pull/2",
                            facts("https://forge.example/o/n\ninjected", "open", null, null)),
                    Map.entry(
                            "https://forge.example/o/n/pull/3",
                            facts("https://forge.example/o/n", "merged", "main\nstatus: ok", TIME)),
                    Map.entry(
                            "https://forge.example/o/n/pull/4",
                            facts("https://forge.example/o/n", "merged", "main..x", TIME)),
                    Map.entry(
                            "https://forge.example/o/n/pull/5",
                            facts("https://forge.example/o/n", "pending", null, TIME)),
                    Map.entry(
                            "https://forge.example/o/n/pull/6",
                            facts("https://forge.example/o/n", "open", null, TIME)),
                    Map.entry(
                            "https://forge.example/o/n/pull/7",
                            facts("https://forge.example/o/n", "merged", "main", null)),
                    Map.entry(
                            "https://forge.example/o/n/pull/8",
                            facts("https://forge.example/o/n", "closed", null, "2026-11-20")));

    private RawReviewAdapter() {}

    private static String thread(final String thread) {
        return Json.write(Map.of("parameters", Map.of("thread", thread)));
    }

    private static String url(final String url) {
        return Json.write(Map.of("parameters", Map.of("url", url)));
    }

    private static String facts(
            final String repository,
            final String state,
            final String merged,
            final String decidedAt) {
        final Map<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("repository", repository);
        facts.put("head", "a".repeat(40));
        facts.put("base", "b".repeat(40));
        facts.put("mergeBase", "c".repeat(40));
        facts.put("state", state);
        facts.put("approved", Boolean.FALSE);
        if (merged != null) {
            facts.put("mergeCommit", "d".repeat(40));
            facts.put("mergedHead", merged);
        }
        if (decidedAt != null) {
            facts.put("decidedAt", decidedAt);
        }
        return Json.write(Map.of("parameters", facts));
    }

    /**
     * Serves one connection after another until the process is ended.
     *
     * @param arguments {@code --limits <line> serve <socket>}: the socket comes last.
     * @throws IOException When the socket cannot be served.
     */
    public static void main(final String[] arguments) throws IOException {
        final Path socket = Path.of(arguments[arguments.length - 1]);
        Files.deleteIfExists(socket);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            System.out.println("ready " + socket);
            System.out.flush();
            while (true) {
                try (SocketChannel connection = server.accept()) {
                    serve(connection);
                } catch (IOException ex) {
                    // The caller went; the next one connects anew.
                }
            }
        }
    }

    private static void serve(final SocketChannel connection) throws IOException {
        while (true) {
            final String frame = frame(connection);
            if (frame == null) {
                return;
            }
            final Map<?, ?> call = (Map<?, ?>) Json.parseExact(frame);
            final String method = String.valueOf(call.get("method"));
            final Map<?, ?> parameters =
                    call.get("parameters") instanceof Map<?, ?> given ? given : Map.of();
            final String answer;
            if (method.endsWith(".Describe")) {
                answer = "{\"parameters\":{\"protocolVersion\":1,\"forge\":\"fake\"}}";
            } else {
                final Object key =
                        switch (method) {
                            case ReviewProtocol.POST -> parameters.get("document");
                            case ReviewProtocol.COMMENTS -> parameters.get("cursor");
                            case ReviewProtocol.PULL_REQUEST -> parameters.get("url");
                            case ReviewProtocol.PULL_REQUEST_OF -> parameters.get("branch");
                            default -> null;
                        };
                answer = ANSWERS.getOrDefault(String.valueOf(key), "not-json");
            }
            final byte[] bytes = (answer + "\0").getBytes(StandardCharsets.UTF_8);
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                connection.write(buffer);
            }
        }
    }

    private static String frame(final SocketChannel connection) throws IOException {
        final ByteArrayOutputStream read = new ByteArrayOutputStream();
        final ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (connection.read(one) < 0) {
                return null;
            }
            final byte got = one.get(0);
            if (got == 0) {
                return read.toString(StandardCharsets.UTF_8);
            }
            read.write(got);
        }
    }
}
