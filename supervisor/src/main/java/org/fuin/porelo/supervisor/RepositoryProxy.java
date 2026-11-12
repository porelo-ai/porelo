package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads a company's package repository for a task, with a credential the task never holds.
 *
 * <p>The second kind of broker beside {@link VaultProxy}, and not a mode of it: that one picks its
 * route by a phantom token and answers only what its scan can read; a package repository is read by
 * a build tool that sends no token, and answers jars. So this one carries <strong>no token at
 * all</strong>. The task is known by its own socket, as the agent's broker knows it; the path's
 * first segment names the repository; the vault's credential is added, and every credential header
 * the task sent is dropped.
 *
 * <p><strong>Read only, and only under the prefix.</strong> GET and HEAD pass, every other method
 * is refused, so a task cannot deploy with the company's credential. The rest of the path is held
 * to the declared prefix before anything is sent: a {@code .} or {@code ..} segment, plain or
 * percent-encoded, an encoded slash or backslash, an encoded percent, an empty segment and a
 * control character are each refused, so no reading of the path by the repository leaves it. A
 * redirect is followed by the broker itself while it stays under the prefix, and answered as a
 * failure, unfollowed and unrepeated, when it leaves.
 *
 * <p><strong>What comes back.</strong> A text answer is scanned as it streams for the credential's
 * values and cut off where one appears; it is asked for uncompressed, and one that comes compressed
 * anyway is withheld, since the scan could not read it. A binary answer is streamed unread: a jar
 * or a tarball cannot be scanned, and the prefix, which the person chooses, is the control. Every
 * request is logged by its method, its path and the status, never a header, a query or a
 * credential.
 *
 * <p>Its socket is bound as the agent broker's is ({@link VaultProxy#bind}). One request per
 * connection, answered with {@code Connection: close}.
 */
public final class RepositoryProxy implements AutoCloseable, Runnable {

    /** The methods that pass; every other is refused before the repository. */
    public static final Set<String> METHODS = Set.of("GET", "HEAD");

    /** The most redirects under the prefix the broker follows for one request. */
    public static final int REDIRECTS = 5;

    /**
     * Headers dropped from the task's request beside {@link VaultProxy#CREDENTIAL_HEADERS}: a
     * cookie is a credential too, and a compressed answer is one the scan cannot read.
     */
    private static final Set<String> DROPPED = Set.of("cookie", "accept-encoding");

    /** Headers of the repository's answer that are not handed to the task. */
    private static final Set<String> WITHHELD = Set.of("set-cookie", "location");

    /**
     * One repository the task reads.
     *
     * @param name The first segment of the path the task asks for it by.
     * @param prefix The URL every request goes under, ending with a slash.
     * @param header The header the credential goes in.
     * @param value The header's value, the credential in it. Never logged.
     * @param secrets The values an answer must not carry: the credential in each form it has.
     */
    public record Repository(
            String name, String prefix, String header, String value, List<String> secrets) {

        /** Constructor that ends the prefix with a slash, so a sibling path never matches it. */
        public Repository {
            prefix = prefix.endsWith("/") ? prefix : prefix + "/";
            secrets = secrets.stream().filter(secret -> !secret.isEmpty()).toList();
        }

        @Override
        public String toString() {
            // Never the value, wherever a repository is printed.
            return "Repository[" + name + " -> " + prefix + "]";
        }
    }

    private final Path socket;

    private final ServerSocketChannel server;

    private final Map<String, Repository> repositories;

    private final java.util.function.Consumer<String> log;

    private final HttpClient http;

    private volatile boolean running = true;

    /**
     * Binds the socket. Nothing is served until {@link #run()} is called.
     *
     * @param socket Path to bind.
     * @param repositories What the task reads, by name.
     * @param log Receives one line per request, and one per answer cut off.
     * @param authorities The account's added certificate authorities.
     */
    public RepositoryProxy(
            Path socket,
            List<Repository> repositories,
            java.util.function.Consumer<String> log,
            Path authorities) {
        final Map<String, Repository> byName = new LinkedHashMap<>();
        repositories.forEach(repository -> byName.put(repository.name(), repository));
        this.repositories = Map.copyOf(byName);
        this.log = log;
        this.socket = socket;
        this.http =
                org.fuin.porelo.core.net.TrustedCertificates.builder(authorities)
                        .connectTimeout(Duration.ofSeconds(30))
                        // Followed here, and only while under the prefix.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.server = VaultProxy.bind(socket, "repository broker", "repository socket");
    }

    /**
     * Returns the socket being served.
     *
     * @return Socket path.
     */
    public Path socket() {
        return socket;
    }

    /** Serves until closed, each connection on its own virtual thread. */
    @Override
    public void run() {
        while (running) {
            final SocketChannel channel;
            try {
                channel = server.accept();
            } catch (IOException ex) {
                // Closing the server socket lands here, which is the normal way out.
                return;
            }
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (SocketChannel open = channel) {
                                    handle(open);
                                } catch (IOException ex) {
                                    // A client that goes away is not the accept loop's to answer.
                                    return;
                                }
                            });
        }
    }

    private void handle(SocketChannel channel) throws IOException {
        final InputStream in = new java.io.BufferedInputStream(Channels.newInputStream(channel));
        final OutputStream out = Channels.newOutputStream(channel);
        final HttpHead head = HttpHead.read(in);
        if (head == null) {
            return;
        }
        final String method = head.method().toUpperCase(Locale.ROOT);
        final String shown = shown(head.target());
        if (!METHODS.contains(method)) {
            refuse(out, 405, "Method Not Allowed", method, shown, "only GET and HEAD are read");
            return;
        }
        final Target target = target(head.target());
        if (target == null) {
            refuse(
                    out,
                    400,
                    "Bad Request",
                    method,
                    shown,
                    "the path could leave its repository's prefix");
            return;
        }
        final Repository repository = repositories.get(target.repository());
        if (repository == null) {
            refuse(out, 404, "Not Found", method, shown, "no repository of that name");
            return;
        }
        URI next = URI.create(repository.prefix() + target.rest() + target.query());
        for (int hop = 0; ; hop++) {
            final HttpResponse<InputStream> response;
            try {
                response =
                        http.send(
                                request(head, method, next, repository),
                                HttpResponse.BodyHandlers.ofInputStream());
            } catch (IllegalArgumentException ex) {
                // Never the exception's words: they may quote the request.
                refuse(out, 400, "Bad Request", method, shown, "a header the client cannot send");
                return;
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                refuse(
                        out,
                        502,
                        "Bad Gateway",
                        method,
                        shown,
                        "the repository could not be reached");
                return;
            }
            final int status = response.statusCode();
            if (!redirect(status)) {
                answer(out, response, method, shown, repository);
                return;
            }
            response.body().close();
            final URI moved = moved(response, next, repository);
            if (moved == null) {
                // Where it pointed is neither followed nor repeated: a signed URL is a credential.
                refuse(
                        out,
                        502,
                        "Bad Gateway",
                        method,
                        shown,
                        "the repository's redirect leaves its prefix");
                return;
            }
            if (hop + 1 >= REDIRECTS) {
                refuse(
                        out,
                        502,
                        "Bad Gateway",
                        method,
                        shown,
                        "the repository redirects more than " + REDIRECTS + " times");
                return;
            }
            next = moved;
        }
    }

    /**
     * The repository and the rest of a request's path.
     *
     * @param repository The first segment.
     * @param rest The rest, segments as sent, each checked.
     * @param query The query with its {@code ?}, or empty.
     */
    record Target(String repository, String rest, String query) {}

    /**
     * Reads a request target: an origin-form path whose first segment names a repository and whose
     * every segment stays where it is however it is decoded.
     *
     * @param raw The target as sent.
     * @return It, or {@code null} when it could leave the prefix or is no path.
     */
    static @Nullable Target target(String raw) {
        if (!raw.startsWith("/") || raw.startsWith("//") || !printable(raw)) {
            return null;
        }
        final int mark = raw.indexOf('?');
        final String path = mark < 0 ? raw : raw.substring(0, mark);
        final String query = mark < 0 ? "" : raw.substring(mark);
        if (raw.indexOf('#') >= 0) {
            return null;
        }
        final String[] segments = path.substring(1).split("/", -1);
        if (segments.length < 2) {
            // "/corp" alone: no slash after the name, so nothing under the prefix.
            return null;
        }
        for (int at = 0; at < segments.length; at++) {
            // An empty last segment is a listing under the prefix: "/corp/" or ".../dir/".
            if (!safe(segments[at], at > 0 && at == segments.length - 1)) {
                return null;
            }
        }
        return new Target(
                segments[0],
                String.join("/", java.util.Arrays.copyOfRange(segments, 1, segments.length)),
                query);
    }

    private static boolean safe(String segment, boolean mayBeEmpty) {
        if (segment.isEmpty()) {
            return mayBeEmpty;
        }
        if (segment.indexOf('\\') >= 0) {
            return false;
        }
        final String lower = segment.toLowerCase(Locale.ROOT);
        // An encoded slash or backslash is a separator to some servers, an encoded dot a
        // dot-segment to some, and an encoded percent a second decoding away from either.
        if (lower.contains("%2f")
                || lower.contains("%5c")
                || lower.contains("%2e")
                || lower.contains("%25")
                || lower.contains("%00")) {
            return false;
        }
        return !".".equals(segment) && !"..".equals(segment);
    }

    private static boolean printable(String text) {
        return text.chars().allMatch(each -> each > 0x20 && each < 0x7f);
    }

    /** The target as logged: its path, never a query, which may carry what the task named. */
    private static String shown(String raw) {
        final int mark = raw.indexOf('?');
        final String path = mark < 0 ? raw : raw.substring(0, mark) + "?...";
        return printable(path) && path.length() <= 512
                ? path
                : "(a target of " + raw.length() + " characters)";
    }

    private static HttpRequest request(
            HttpHead head, String method, URI uri, Repository repository) {
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody());
        final String own = repository.header().toLowerCase(Locale.ROOT);
        for (int at = 0; at < head.names().size(); at++) {
            final String name = head.names().get(at);
            final String lower = name.toLowerCase(Locale.ROOT);
            if (VaultProxy.HOP_BY_HOP.contains(lower)
                    || VaultProxy.CREDENTIAL_HEADERS.contains(lower)
                    || DROPPED.contains(lower)
                    || lower.equals(own)) {
                continue;
            }
            builder.header(name, head.values().get(at));
        }
        builder.header(repository.header(), repository.value());
        return builder.build();
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Returns where a redirect points when it stays under the repository's prefix, on its scheme,
     * host and port, every segment of the rest held to a request's rule; {@code null} otherwise.
     */
    private static @Nullable URI moved(
            HttpResponse<InputStream> response, URI from, Repository repository) {
        final String location = response.headers().firstValue("location").orElse(null);
        if (location == null) {
            return null;
        }
        final URI resolved;
        try {
            resolved = from.resolve(new URI(location));
        } catch (java.net.URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
        final URI prefix = URI.create(repository.prefix());
        final String path = resolved.getRawPath();
        if (path == null
                || resolved.getRawFragment() != null
                || resolved.getRawUserInfo() != null
                || !prefix.getScheme().equalsIgnoreCase(String.valueOf(resolved.getScheme()))
                || !prefix.getHost().equalsIgnoreCase(String.valueOf(resolved.getHost()))
                || port(prefix) != port(resolved)
                || !path.startsWith(prefix.getRawPath())) {
            return null;
        }
        final String rest = path.substring(prefix.getRawPath().length());
        final String query = resolved.getRawQuery() == null ? "" : "?" + resolved.getRawQuery();
        if (target("/" + repository.name() + "/" + rest + query) == null) {
            return null;
        }
        return URI.create(repository.prefix() + rest + query);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void refuse(
            OutputStream out, int status, String reason, String method, String shown, String why)
            throws IOException {
        log.accept(method + " " + shown + " -> " + status + " " + why);
        out.write(HttpHead.response(status, reason, "{\"error\":\"porelo: " + why + "\"}"));
        out.flush();
    }

    private void answer(
            OutputStream out,
            HttpResponse<InputStream> response,
            String method,
            String shown,
            Repository repository)
            throws IOException {
        final int status = response.statusCode();
        final boolean text = text(response);
        final String coding =
                response.headers()
                        .firstValue("content-encoding")
                        .map(value -> value.strip().toLowerCase(Locale.ROOT))
                        .orElse("identity");
        if (text && !"HEAD".equals(method) && !"identity".equals(coding)) {
            response.body().close();
            refuse(
                    out,
                    502,
                    "Bad Gateway",
                    method,
                    shown,
                    "the repository answered " + status + " in text the broker cannot read");
            return;
        }
        log.accept(method + " " + shown + " -> " + status + " from the repository");
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(" \r\n");
        response.headers()
                .map()
                .forEach(
                        (name, values) -> {
                            final String lower = name.toLowerCase(Locale.ROOT);
                            if (lower.startsWith(":")
                                    || VaultProxy.HOP_BY_HOP.contains(lower)
                                    || WITHHELD.contains(lower)) {
                                return;
                            }
                            for (final String value : values) {
                                if (value.chars().allMatch(each -> each >= 0x20 && each < 0x7f)
                                        && !carries(value, repository)) {
                                    head.append(name).append(": ").append(value).append("\r\n");
                                }
                            }
                        });
        response.headers()
                .firstValue("content-length")
                .ifPresent(length -> head.append("Content-Length: ").append(length).append("\r\n"));
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        try (InputStream body = response.body()) {
            if ("HEAD".equals(method)) {
                // Nothing follows a HEAD's head.
            } else if (!text) {
                body.transferTo(out);
            } else if (!scanned(body, out, repository)) {
                log.accept(
                        method
                                + " "
                                + shown
                                + " answer cut off: it carried the credential of "
                                + repository.name());
            }
        }
        out.flush();
    }

    private static boolean carries(String value, Repository repository) {
        return repository.secrets().stream().anyMatch(value::contains);
    }

    /** Whether an answer is text the scan reads: typed as text, JSON or XML, or untyped. */
    private static boolean text(HttpResponse<InputStream> response) {
        final String type =
                response.headers()
                        .firstValue("content-type")
                        .map(value -> value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))
                        .orElse("");
        return type.isEmpty()
                || type.startsWith("text/")
                || type.endsWith("/json")
                || type.endsWith("+json")
                || type.endsWith("/xml")
                || type.endsWith("+xml")
                || type.endsWith("/javascript");
    }

    /**
     * Streams a text answer, holding back the bytes a secret could still be completing, and stops
     * before the first secret.
     *
     * @return Whether it streamed whole.
     */
    private static boolean scanned(InputStream body, OutputStream out, Repository repository)
            throws IOException {
        final List<byte[]> needles =
                repository.secrets().stream()
                        .map(secret -> secret.getBytes(StandardCharsets.UTF_8))
                        .toList();
        final int keep = needles.stream().mapToInt(needle -> needle.length).max().orElse(1) - 1;
        byte[] pending = new byte[0];
        final byte[] chunk = new byte[16 * 1024];
        int got;
        while ((got = body.read(chunk)) >= 0) {
            final byte[] window = new byte[pending.length + got];
            System.arraycopy(pending, 0, window, 0, pending.length);
            System.arraycopy(chunk, 0, window, pending.length, got);
            final int found = first(window, needles);
            if (found >= 0) {
                out.write(window, 0, found);
                return false;
            }
            final int passed = Math.max(0, window.length - keep);
            out.write(window, 0, passed);
            pending = java.util.Arrays.copyOfRange(window, passed, window.length);
        }
        out.write(pending);
        return true;
    }

    private static int first(byte[] window, List<byte[]> needles) {
        int earliest = -1;
        for (final byte[] needle : needles) {
            final int at = indexOf(window, needle);
            if (at >= 0 && (earliest < 0 || at < earliest)) {
                earliest = at;
            }
        }
        return earliest;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int at = 0; at + needle.length <= haystack.length; at++) {
            if (java.util.Arrays.equals(
                    haystack, at, at + needle.length, needle, 0, needle.length)) {
                return at;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ex) {
            // Nothing useful to do while shutting down.
        }
        http.shutdownNow();
        http.close();
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The socket lives in the runtime directory, which the kernel clears at session end.
        }
    }
}
