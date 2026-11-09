package org.fuin.porelo.organization.gittree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** Forgejo administration (https://forgejo.org/docs/latest/user/api-usage/), without redirects. */
public final class HttpForgeAdmin implements AutoCloseable {
    private static final int LIMIT = 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Transport transport;
    private final char[] token;
    private final boolean central;

    /** A safe refusal carrying no remote body or credential. */
    public static final class Refusal extends RuntimeException {
        private final int exitCode;

        private Refusal(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }

        /** The project's sysexits status. */
        public int exitCode() {
            return exitCode;
        }
    }

    /** A local administrator talks only to this Unix socket, with no proxy or TCP fallback. */
    public HttpForgeAdmin(Path socket, char[] token) {
        this(socket, token, TIMEOUT);
    }

    HttpForgeAdmin(Path socket, char[] token, Duration timeout) {
        this(
                (method, path, authorization, body) ->
                        unix(socket, method, path, authorization, body, timeout),
                token,
                false);
    }

    /**
     * A central forge is asked at its validated origin; plain HTTP is allowed on literal loopback.
     */
    public static HttpForgeAdmin central(URI origin, char[] token) {
        final String base =
                origin.toString().equals("https://github.com")
                        ? "https://api.github.com"
                        : checkedOrigin(origin) + "/api/v1";
        return new HttpForgeAdmin(tcp(URI.create(base), TIMEOUT), token, true);
    }

    HttpForgeAdmin(URI base, char[] token, Duration timeout) {
        this(tcp(base, timeout), token, false);
    }

    HttpForgeAdmin(Transport transport, char[] token, boolean central) {
        if (token.length < 1 || token.length > 4096) {
            throw new IllegalArgumentException("Expected one printable forge token");
        }
        for (char character : token) {
            if (character < '!' || character > '~') {
                throw new IllegalArgumentException("Expected one printable forge token");
            }
        }
        this.transport = transport;
        this.token = token.clone();
        this.central = central;
    }

    /** Ask once, validating status and JSON and never exposing response bodies on failure. */
    public @Nullable Object request(
            String method, String path, @Nullable Object body, int... expected) {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.chars().anyMatch(c -> c < 33 || c > 126)
                || path.indexOf('#') >= 0
                || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("Expected an API method and relative path");
        }
        final byte[] bytes =
                body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
        final byte[] authorization = new byte[6 + token.length];
        System.arraycopy(new byte[] {'t', 'o', 'k', 'e', 'n', ' '}, 0, authorization, 0, 6);
        for (int index = 0; index < token.length; index++) {
            authorization[6 + index] = (byte) token[index];
        }
        final Answer answer;
        try {
            answer = transport.request(method, path, authorization, bytes);
        } catch (IOException | IllegalArgumentException ex) {
            throw new Refusal(
                    69,
                    central
                            ? "the central forge did not answer the deploy-key request"
                            : "the forge did not answer " + method + " " + path + ": IOException");
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(authorization, (byte) 0);
        }
        try {
            final boolean accepted =
                    Arrays.stream(expected.length == 0 ? new int[] {200} : expected)
                            .anyMatch(code -> code == answer.status());
            if (!accepted || answer.body().length > LIMIT) {
                throw new Refusal(
                        central || answer.status() >= 500 ? 69 : 65,
                        central
                                ? "the central forge refused the deploy-key request (HTTP "
                                        + answer.status()
                                        + ")"
                                : "the forge answered HTTP "
                                        + answer.status()
                                        + " for "
                                        + method
                                        + " "
                                        + path);
            }
            if (answer.status() == 404
                    || answer.body().length == 0 && (central || !method.equals("GET"))) {
                return null;
            }
            final Object parsed;
            try {
                parsed =
                        Json.parseExact(
                                StandardCharsets.UTF_8
                                        .newDecoder()
                                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(answer.body()))
                                        .toString());
            } catch (JsonException | java.nio.charset.CharacterCodingException ex) {
                throw new Refusal(
                        central ? 69 : 65,
                        central
                                ? "the central forge answered malformed deploy-key JSON"
                                : "the forge sent invalid JSON for " + method + " " + path);
            }
            if (parsed == null && !central) {
                throw new Refusal(
                        65, "the forge sent no identity or list for " + method + " " + path);
            }
            return parsed;
        } finally {
            Arrays.fill(answer.body(), (byte) 0);
        }
    }

    /** Read all pages, rejecting repetition and incomplete lists before any destructive act. */
    public List<Map<?, ?>> listing(String path) {
        final List<Map<?, ?>> result = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            final Object answer = request("GET", path + "?limit=50&page=" + page, null, 200);
            if (!(answer instanceof List<?> items)
                    || items.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
                throw new Refusal(65, "the forge sent no list for " + path);
            }
            if (items.isEmpty()) {
                return result;
            }
            if (!seen.add(canonical(items))) {
                throw new Refusal(65, "the forge repeated a page for " + path);
            }
            for (Object item : items) {
                result.add((Map<?, ?>) item);
            }
        }
        throw new Refusal(65, "the forge did not finish listing " + path + " in 100 pages");
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, @Nullable Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            final StringBuilder result = new StringBuilder("{");
            sorted.forEach(
                    (key, item) ->
                            result.append(Json.write(key))
                                    .append(':')
                                    .append(item == null ? "null" : canonical(item))
                                    .append(','));
            return result.append('}').toString();
        }
        if (value instanceof List<?> items) {
            return items.stream()
                    .map(item -> item == null ? "null" : canonical(item))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return Json.write(value);
    }

    @Override
    public void close() {
        Arrays.fill(token, '\0');
        transport.close();
    }

    @Override
    public String toString() {
        return "HttpForgeAdmin[credential withheld]";
    }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        Answer request(String method, String path, byte[] authorization, byte[] body)
                throws IOException;

        @Override
        default void close() {}
    }

    record Answer(int status, byte[] body) {}

    private static String checkedOrigin(URI origin) {
        final boolean loopback =
                "http".equals(origin.getScheme())
                        && Set.of("127.0.0.1", "[::1]").contains(origin.getHost());
        if (!("https".equals(origin.getScheme()) || loopback)
                || origin.getHost() == null
                || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Expected a credential-free HTTPS forge origin");
        }
        return origin.getScheme() + "://" + origin.getRawAuthority();
    }

    private static Transport tcp(URI base, Duration timeout) {
        checkedOrigin(URI.create(base.getScheme() + "://" + base.getRawAuthority()));
        final HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(
                                new ProxySelector() {
                                    @Override
                                    public List<java.net.Proxy> select(URI uri) {
                                        return List.of(java.net.Proxy.NO_PROXY);
                                    }

                                    @Override
                                    public void connectFailed(
                                            URI uri,
                                            java.net.SocketAddress address,
                                            IOException error) {}
                                })
                        .connectTimeout(timeout)
                        .build();
        return new Transport() {
            @Override
            public Answer request(String method, String path, byte[] authorization, byte[] body)
                    throws IOException {
                final HttpRequest request =
                        HttpRequest.newBuilder(URI.create(base + path))
                                .timeout(timeout)
                                // HttpRequest's header API requires an immutable String.
                                .header(
                                        "Authorization",
                                        new String(authorization, StandardCharsets.US_ASCII))
                                .header("Content-Type", "application/json")
                                .method(
                                        method,
                                        body.length == 0
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofByteArray(body))
                                .build();
                final CompletableFuture<HttpResponse<byte[]>> pending =
                        client.sendAsync(request, info -> new Bounded());
                try {
                    final HttpResponse<byte[]> response =
                            pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    return new Answer(response.statusCode(), response.body());
                } catch (ExecutionException | TimeoutException ex) {
                    pending.cancel(true);
                    throw new IOException("Forge response unavailable");
                } catch (InterruptedException ex) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new IOException("Forge request interrupted");
                }
            }

            @Override
            public void close() {
                client.shutdownNow();
            }
        };
    }

    private static Answer unix(
            Path path,
            String method,
            String route,
            byte[] authorization,
            byte[] body,
            Duration timeout)
            throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            final FutureTask<Answer> task =
                    new FutureTask<>(
                            () -> {
                                channel.connect(UnixDomainSocketAddress.of(path));
                                final var out = Channels.newOutputStream(channel);
                                final byte[] before =
                                        (method
                                                        + " /api/v1"
                                                        + route
                                                        + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: ")
                                                .getBytes(StandardCharsets.US_ASCII);
                                final byte[] after =
                                        ("\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                                                        + body.length
                                                        + "\r\n\r\n")
                                                .getBytes(StandardCharsets.US_ASCII);
                                out.write(before);
                                out.write(authorization);
                                out.write(after);
                                out.write(body);
                                out.flush();
                                final InputStream in = Channels.newInputStream(channel);
                                final String[] status = line(in).split(" ", 3);
                                if (status.length < 2
                                        || !status[0].matches("HTTP/1\\.[01]")
                                        || !status[1].matches("[0-9]{3}")) {
                                    throw new IOException("Invalid HTTP status");
                                }
                                int size = -1;
                                boolean chunked = false;
                                int headerBytes = 0;
                                while (true) {
                                    final String header = line(in);
                                    headerBytes += header.length() + 2;
                                    if (headerBytes > 65536) {
                                        throw new IOException("HTTP headers exceed limit");
                                    }
                                    if (header.isEmpty()) {
                                        break;
                                    }
                                    final int colon = header.indexOf(':');
                                    if (colon < 1) {
                                        throw new IOException("Malformed HTTP header");
                                    }
                                    final String name = header.substring(0, colon);
                                    final String value = header.substring(colon + 1).strip();
                                    if (name.equalsIgnoreCase("Content-Length")) {
                                        if (size != -1 || !value.matches("[0-9]{1,9}")) {
                                            throw new IOException("Ambiguous HTTP length");
                                        }
                                        size = Integer.parseInt(value);
                                    } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                                        if (chunked || !value.equalsIgnoreCase("chunked")) {
                                            throw new IOException("Unsupported transfer encoding");
                                        }
                                        chunked = true;
                                    }
                                }
                                if (chunked && size >= 0) {
                                    throw new IOException("Ambiguous HTTP framing");
                                }
                                final int code = Integer.parseInt(status[1]);
                                if (code == 204 || code == 304) {
                                    return new Answer(code, new byte[0]);
                                }
                                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                if (chunked) {
                                    for (int chunks = 0; chunks < LIMIT; chunks++) {
                                        final String chunk = line(in).split(";", 2)[0];
                                        if (!chunk.matches("[0-9a-fA-F]{1,8}")) {
                                            throw new IOException("Invalid HTTP chunk");
                                        }
                                        final long count = Long.parseLong(chunk, 16);
                                        if (count == 0) {
                                            return new Answer(code, bytes.toByteArray());
                                        }
                                        if (count > LIMIT - bytes.size()) {
                                            throw new IOException("HTTP body exceeds limit");
                                        }
                                        bytes.write(exact(in, (int) count));
                                        if (!line(in).isEmpty()) {
                                            throw new IOException("Invalid HTTP chunk ending");
                                        }
                                    }
                                    throw new IOException("Too many HTTP chunks");
                                }
                                if (size > LIMIT) {
                                    throw new IOException("HTTP body exceeds limit");
                                }
                                final byte[] result =
                                        size < 0 ? in.readNBytes(LIMIT + 1) : exact(in, size);
                                if (result.length > LIMIT) {
                                    throw new IOException("HTTP body exceeds limit");
                                }
                                return new Answer(code, result);
                            });
            final Thread worker = Thread.ofVirtual().name("project-forge-http").start(task);
            try {
                return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException ex) {
                throw new IOException("Forge response unavailable");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Forge request interrupted");
            } finally {
                task.cancel(true);
                worker.interrupt();
                channel.close();
                boolean interrupted = Thread.interrupted();
                while (worker.isAlive()) {
                    try {
                        worker.join();
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static byte[] exact(InputStream in, int count) throws IOException {
        final byte[] bytes = in.readNBytes(count);
        if (bytes.length != count) {
            throw new IOException("HTTP body was cut short");
        }
        return bytes;
    }

    private static String line(InputStream in) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count < 8192; count++) {
            final int character = in.read();
            if (character < 0) {
                throw new IOException("HTTP reply was cut short");
            }
            if (character == '\n') {
                final byte[] value = bytes.toByteArray();
                if (value.length == 0 || value[value.length - 1] != '\r') {
                    throw new IOException("Invalid HTTP line ending");
                }
                return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(character);
        }
        throw new IOException("HTTP line exceeds limit");
    }

    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.@Nullable Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (item.remaining() > LIMIT - bytes.size()) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    result.completeExceptionally(new IOException("Forge response exceeds limit"));
                    return;
                }
                final byte[] part = new byte[item.remaining()];
                item.get(part);
                bytes.writeBytes(part);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(new IOException("Forge response unavailable"));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
