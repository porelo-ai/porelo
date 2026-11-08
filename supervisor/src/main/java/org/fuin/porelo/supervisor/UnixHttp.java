package org.fuin.porelo.supervisor;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP/1.1 exchange over a Unix socket on this host.
 *
 * <p><strong>Why its own.</strong> {@link HttpClient} dials hosts and ports only and cannot reach a
 * Unix socket. This is the smallest client the gateway needs: one request per connection, closed
 * after, its body sent with a {@code Content-Length}; an answer framed by {@code Content-Length} or
 * chunked, handed over as it arrives so a stream of events reaches the task event by event.
 *
 * <p><strong>An answer it cannot frame is refused, never guessed at.</strong> A status line that is
 * not HTTP/1.x, an interim answer, a header that is not one, a body that declares neither a length
 * nor chunks, both at once, a length that is not one, or a transfer coding other than chunked is
 * {@link Unframed}, before anything reaches the task. A body that breaks its own framing later ends
 * with an {@link IOException}, so the task sees a cut-off answer rather than a whole one.
 */
final class UnixHttp {

    /** Largest answer head accepted, in bytes. */
    private static final int HEAD_LIMIT = 64 * 1024;

    /** The status line: the version, three digits, and an optional reason. */
    private static final Pattern STATUS = Pattern.compile("HTTP/1\\.[01] ([1-9][0-9]{2})(?: .*)?");

    /** A header's name, a token as RFC 9110 has it. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /** An answer whose framing cannot be read. */
    static final class Unframed extends IOException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message What was wrong with it.
         */
        Unframed(String message) {
            super(message);
        }
    }

    private UnixHttp() {}

    /**
     * Sends a request over a Unix socket and returns the answer, its body still to be read.
     *
     * @param socket The socket.
     * @param request The request; its URI gives the target and its headers are sent as they are.
     * @param body The request body.
     * @return The answer; closing its body closes the connection.
     * @throws Unframed If the answer's head cannot be framed.
     * @throws IOException If the socket cannot be reached or the exchange fails.
     */
    static HttpResponse<InputStream> send(Path socket, HttpRequest request, byte[] body)
            throws IOException {
        // Asked again here and not only when the destination was taken: a directory can be swapped
        // for a link in between.
        try {
            UnixUpstream.linkFree(socket);
        } catch (UnixUpstream.Refused refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            final OutputStream out = Channels.newOutputStream(channel);
            out.write(head(request, body.length));
            out.write(body);
            out.flush();
            final InputStream in = new BufferedInputStream(Channels.newInputStream(channel));
            return answer(in, request, channel);
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex;
        }
    }

    /** The request line and headers. */
    private static byte[] head(HttpRequest request, int length) throws IOException {
        final URI uri = request.uri();
        final String path =
                uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        final String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        final StringBuilder head = new StringBuilder();
        head.append(request.method()).append(' ').append(target).append(" HTTP/1.1\r\n");
        head.append("Host: localhost\r\n");
        for (final Map.Entry<String, List<String>> header : request.headers().map().entrySet()) {
            for (final String value : header.getValue()) {
                head.append(plain(header.getKey()))
                        .append(": ")
                        .append(plain(value))
                        .append("\r\n");
            }
        }
        // Always, even for no body: a POST without it is a request whose end the server guesses.
        if (length > 0 || !request.method().equals("GET") && !request.method().equals("HEAD")) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        return head.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Refuses a line break in a header, which would start a header the request did not carry. */
    private static String plain(String text) throws IOException {
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
            throw new IOException("A header holds a line break");
        }
        return text;
    }

    /**
     * Reads an answer's head and frames its body.
     *
     * @param in The connection, buffered.
     * @param request The request it answers.
     * @param connection Closed with the body.
     * @return The answer.
     * @throws IOException If it cannot be read or framed.
     */
    static HttpResponse<InputStream> answer(
            InputStream in, HttpRequest request, java.io.Closeable connection) throws IOException {
        final String statusLine = line(in);
        if (statusLine == null) {
            throw new Unframed("the destination closed without an answer");
        }
        final Matcher status = STATUS.matcher(statusLine);
        if (!status.matches()) {
            throw new Unframed("the answer does not open with an HTTP/1.1 status line");
        }
        final int code = Integer.parseInt(status.group(1));
        if (code < 200) {
            // Nothing here asks for one; an interim answer, or a protocol switch, is not framed.
            throw new Unframed("the answer is an interim " + code);
        }
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        int size = statusLine.length();
        while (true) {
            final String header = line(in);
            if (header == null) {
                throw new Unframed("the answer's head ended before its blank line");
            }
            if (header.isEmpty()) {
                break;
            }
            size += header.length();
            if (size > HEAD_LIMIT) {
                throw new Unframed("the answer's head exceeds " + HEAD_LIMIT + " bytes");
            }
            final int colon = header.indexOf(':');
            if (colon < 1 || !TOKEN.matcher(header.substring(0, colon)).matches()) {
                // A folded line, a space before the colon, or no colon: not a header.
                throw new Unframed("the answer carries a line that is not a header");
            }
            headers.computeIfAbsent(header.substring(0, colon), name -> new ArrayList<>())
                    .add(header.substring(colon + 1).strip());
        }
        final HttpHeaders read = HttpHeaders.of(headers, (name, value) -> true);
        final InputStream body = body(in, read, code, request.method(), connection);
        return new Answer(code, request, read, body);
    }

    private static InputStream body(
            InputStream in,
            HttpHeaders headers,
            int code,
            String method,
            java.io.Closeable connection)
            throws IOException {
        final List<String> codings = headers.allValues("transfer-encoding");
        final List<String> lengths = headers.allValues("content-length");
        if (!codings.isEmpty() && !lengths.isEmpty()) {
            throw new Unframed("the answer declares both a length and a transfer coding");
        }
        if (method.equals("HEAD") || code == 204 || code == 304) {
            return new Bounded(in, 0, connection);
        }
        if (!codings.isEmpty()) {
            if (codings.size() != 1 || !codings.get(0).equalsIgnoreCase("chunked")) {
                throw new Unframed("the answer's transfer coding is not chunked alone");
            }
            return new Chunked(in, connection);
        }
        if (lengths.isEmpty()) {
            // Read to the close would pass a cut-off answer on as a whole one.
            throw new Unframed("the answer declares neither a length nor chunks");
        }
        long length = -1;
        for (final String each : lengths) {
            for (final String value : each.split(",", -1)) {
                final String digits = value.strip();
                if (digits.isEmpty()
                        || digits.length() > 18
                        || !digits.chars().allMatch(Character::isDigit)) {
                    throw new Unframed("the answer's Content-Length is not a length");
                }
                final long parsed = Long.parseLong(digits);
                if (length >= 0 && parsed != length) {
                    throw new Unframed("the answer declares two lengths");
                }
                length = parsed;
            }
        }
        return new Bounded(in, length, connection);
    }

    /** Reads one line ended by CRLF, or {@code null} at the end; a bare LF is refused. */
    private static @Nullable String line(InputStream in) throws IOException {
        final StringBuilder text = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                final int end = text.length() - 1;
                if (end < 0 || text.charAt(end) != '\r') {
                    throw new Unframed("a line of the answer does not end in CRLF");
                }
                text.setLength(end);
                return text.toString();
            }
            text.append((char) read);
            if (text.length() > HEAD_LIMIT) {
                throw new Unframed("a line of the answer exceeds " + HEAD_LIMIT + " bytes");
            }
        }
        if (!text.isEmpty()) {
            throw new Unframed("the answer ended within a line");
        }
        return null;
    }

    /** A body of a declared length; ending before it is an error, never a shorter body. */
    private static final class Bounded extends InputStream {

        private final InputStream in;

        private final java.io.Closeable connection;

        private long left;

        Bounded(InputStream in, long length, java.io.Closeable connection) {
            this.in = in;
            this.left = length;
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left == 0) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            final int got = in.read(buffer, offset, (int) Math.min(length, left));
            if (got < 0) {
                throw new IOException("the answer ended " + left + " bytes before its length");
            }
            left -= got;
            return got;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), left);
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }
    }

    /** A chunked body, handed over chunk by chunk as it arrives. */
    private static final class Chunked extends InputStream {

        private final InputStream in;

        private final java.io.Closeable connection;

        /** What is left of the current chunk; zero between chunks. */
        private long left;

        private boolean ended;

        Chunked(InputStream in, java.io.Closeable connection) {
            this.in = in;
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            int got;
            do {
                got = read(one, 0, 1);
            } while (got == 0);
            return got < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (left == 0 && !next()) {
                return -1;
            }
            final int got = in.read(buffer, offset, (int) Math.min(length, left));
            if (got < 0) {
                throw new IOException("the answer ended within a chunk");
            }
            left -= got;
            if (left == 0) {
                separator();
            }
            return got;
        }

        /** Reads the next chunk's size; {@code false} once the last chunk and trailers are read. */
        private boolean next() throws IOException {
            if (ended) {
                return false;
            }
            final String header = line(in);
            if (header == null) {
                throw new IOException("the answer ended before its last chunk");
            }
            final int semicolon = header.indexOf(';');
            final String size = (semicolon < 0 ? header : header.substring(0, semicolon)).strip();
            if (size.isEmpty()
                    || size.length() > 15
                    || !size.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                throw new IOException("the answer carries a chunk size that is not one");
            }
            left = Long.parseLong(size, 16);
            if (left == 0) {
                // Trailers, then the blank line; none is passed on.
                String trailer;
                do {
                    trailer = line(in);
                    if (trailer == null) {
                        throw new IOException("the answer ended before its last blank line");
                    }
                } while (!trailer.isEmpty());
                ended = true;
                return false;
            }
            return true;
        }

        /** The CRLF after a chunk's data. */
        private void separator() throws IOException {
            final String after = line(in);
            if (after == null || !after.isEmpty()) {
                throw new IOException("a chunk of the answer is not followed by CRLF");
            }
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), left);
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }
    }

    /** The answer, in the shape the gateway already relays. */
    private record Answer(
            int statusCode, HttpRequest request, HttpHeaders headers, InputStream body)
            implements HttpResponse<InputStream> {

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
