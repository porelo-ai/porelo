package org.fuin.porelo.host;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Bounded HTTP headers and streamed HTTP bodies, without interpreting credential bytes. */
final class GateHttp {
    static final Set<String> HOP =
            Set.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade");

    record Header(String name, String value) {
        @Override
        public String toString() {
            return "Header[withheld]";
        }
    }

    record Headers(List<Header> entries) {
        Headers {
            entries = List.copyOf(entries);
        }

        List<String> all(String name) {
            return entries.stream()
                    .filter(header -> header.name().equalsIgnoreCase(name))
                    .map(Header::value)
                    .toList();
        }

        String first(String name) {
            final var values = all(name);
            return values.isEmpty() ? "" : values.getFirst();
        }

        boolean browser() {
            return entries.stream()
                    .anyMatch(
                            header ->
                                    header.name()
                                            .toLowerCase(Locale.ROOT)
                                            .startsWith("sec-fetch-"));
        }

        @Override
        public String toString() {
            return "Headers[withheld]";
        }
    }

    record Request(String method, String target, String version, Headers headers) {
        @Override
        public String toString() {
            return "Request[withheld]";
        }
    }

    record Response(int status, Headers headers) {}

    private GateHttp() {}

    static @Nullable Request request(InputStream in) throws IOException {
        final String line = line(in, 65536);
        if (line == null) return null;
        final String[] words = line.split(" +", -1);
        if (words.length != 3
                || !words[0].matches("[A-Z]+")
                || !Set.of("HTTP/1.0", "HTTP/1.1").contains(words[2])
                || words[1].chars().anyMatch(value -> value <= 32 || value >= 127))
            throw new IOException("Invalid HTTP request");
        return new Request(words[0], words[1], words[2], headers(in));
    }

    static Response response(InputStream in) throws IOException {
        while (true) {
            final String line = line(in, 65536);
            if (line == null) throw new EOFException("No upstream response");
            final String[] words = line.split(" +", 3);
            if (words.length < 2 || !words[0].startsWith("HTTP/") || !words[1].matches("[0-9]{3}"))
                throw new IOException("Invalid upstream response");
            final int status = Integer.parseInt(words[1]);
            final Headers headers = headers(in);
            if (status != 100) return new Response(status, headers);
        }
    }

    static Headers headers(InputStream in) throws IOException {
        final List<Header> headers = new ArrayList<>();
        for (int count = 0; count < 100; count++) {
            final String line = line(in, 65536);
            if (line == null) throw new EOFException("Incomplete HTTP headers");
            if (line.isEmpty()) return new Headers(headers);
            final int colon = line.indexOf(':');
            if (colon < 1
                    || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || line.chars()
                            .anyMatch(value -> (value < 32 && value != '\t') || value == 127))
                throw new IOException("Invalid HTTP header");
            headers.add(
                    new Header(
                            line.substring(0, colon),
                            line.substring(colon + 1).replaceFirst("^[ \\t]+", "")));
        }
        throw new IOException("Too many HTTP headers");
    }

    static @Nullable String line(InputStream in, int maximum) throws IOException {
        return line(in, maximum, false);
    }

    private static @Nullable String line(InputStream in, int maximum, boolean crlf)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count <= maximum; count++) {
            final int value = in.read();
            if (value == -1) {
                if (count == 0) return null;
                throw new EOFException("Incomplete HTTP line");
            }
            if (value == '\n') {
                final byte[] raw = bytes.toByteArray();
                if (crlf && (raw.length == 0 || raw[raw.length - 1] != '\r'))
                    throw new IOException("Invalid chunk size line");
                return new String(
                        raw,
                        0,
                        raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length,
                        StandardCharsets.ISO_8859_1);
            }
            bytes.write(value);
        }
        throw new IOException("HTTP line exceeds limit");
    }

    static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    static String reason(int status) {
        return switch (status) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 102 -> "Processing";
            case 103 -> "Early Hints";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 207 -> "Multi-Status";
            case 208 -> "Already Reported";
            case 226 -> "IM Used";
            case 300 -> "Multiple Choices";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 305 -> "Use Proxy";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 418 -> "I'm a Teapot";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Content";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 425 -> "Too Early";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            case 506 -> "Variant Also Negotiates";
            case 507 -> "Insufficient Storage";
            case 508 -> "Loop Detected";
            case 510 -> "Not Extended";
            case 511 -> "Network Authentication Required";
            default -> "";
        };
    }

    static void reply(OutputStream out, String method, int status, boolean challenge, byte[] body)
            throws IOException {
        write(out, "HTTP/1.1 " + status + " " + reason(status) + "\r\n");
        if (challenge) write(out, "WWW-Authenticate: Basic\r\n");
        if (body.length > 0) write(out, "Content-Type: text/plain; charset=utf-8\r\n");
        write(out, "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n");
        if (!method.equals("HEAD")) out.write(body);
        out.flush();
    }

    static void fixed(InputStream in, OutputStream out, long length) throws IOException {
        final byte[] buffer = new byte[65536];
        while (length > 0) {
            final int count = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (count < 0) throw new EOFException("Incomplete HTTP body");
            out.write(buffer, 0, count);
            length -= count;
        }
    }

    static void untilEnd(InputStream in, OutputStream out, boolean encode) throws IOException {
        final byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (encode) write(out, Integer.toHexString(count) + "\r\n");
            out.write(buffer, 0, count);
            if (encode) write(out, "\r\n");
            out.flush();
        }
        if (encode) write(out, "0\r\n\r\n");
    }

    static void chunks(InputStream in, OutputStream out, boolean encode, boolean trailers)
            throws IOException {
        while (true) {
            final String line = line(in, 128, !trailers);
            final String size = line == null ? "" : trailers ? line.split(";", 2)[0] : line;
            if (!size.matches("[0-9a-fA-F]{1,16}")) throw new IOException("Invalid chunk size");
            final long length;
            try {
                length = Long.parseLong(size, 16);
            } catch (NumberFormatException ex) {
                throw new IOException("Chunk exceeds limit", ex);
            }
            if (length == 0) {
                if (trailers) headers(in);
                else if (in.read() != '\r' || in.read() != '\n')
                    throw new IOException("Trailers are not supported");
                if (encode) write(out, "0\r\n\r\n");
                return;
            }
            if (encode) write(out, Long.toHexString(length) + "\r\n");
            fixed(in, out, length);
            if (in.read() != '\r' || in.read() != '\n')
                throw new IOException("Invalid chunk ending");
            if (encode) write(out, "\r\n");
        }
    }
}
