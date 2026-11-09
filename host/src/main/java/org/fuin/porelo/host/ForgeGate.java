package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** One accepted forge connection; each request is checked before any upstream bytes are sent. */
final class ForgeGate {
    @FunctionalInterface
    interface Upstream {
        GateConnection open() throws IOException;
    }

    @FunctionalInterface
    interface Login {
        @Nullable String read() throws IOException;
    }

    @FunctionalInterface
    interface Hosts {
        Set<String> read();
    }

    private final String mode;
    private final Login login;
    private final Hosts hosts;
    private final Upstream upstream;

    ForgeGate(String mode, Login login, Hosts hosts, Upstream upstream) {
        if (!Set.of("web", "git", "runner").contains(mode))
            throw new IllegalArgumentException("Unknown gate mode");
        this.mode = mode;
        this.login = login;
        this.hosts = hosts;
        this.upstream = upstream;
    }

    static ForgeGate installed(String mode, SocketChannel peer, Path upstream) {
        final ProjectFiles files = new ProjectFiles();
        final ProjectRuntime runtime = new ProjectRuntime(files);
        return installed(mode, peer, upstream, files, runtime);
    }

    static ForgeGate installed(
            String mode,
            SocketChannel peer,
            Path upstream,
            ProjectFiles files,
            ProjectRuntime runtime) {
        return installed(
                mode,
                () ->
                        runtime.gateLogin(
                                peer.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED)
                                        .user()
                                        .getName()),
                upstream,
                files);
    }

    static ForgeGate installed(String mode, Login login, Path upstream, ProjectFiles files) {
        return new ForgeGate(
                mode,
                login,
                () -> {
                    try {
                        return ForgeRoutes.hosts(
                                files.text(files.etc.resolve("forge.conf"), false));
                    } catch (IOException | HostRefusal ex) {
                        return Set.of();
                    }
                },
                () -> {
                    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                    try {
                        channel.connect(UnixDomainSocketAddress.of(upstream));
                        return new GateConnection(channel);
                    } catch (IOException | RuntimeException ex) {
                        channel.close();
                        throw ex;
                    }
                });
    }

    void serve(InputStream in, OutputStream out) throws IOException {
        final String person = mode.equals("web") ? login.read() : null;
        while (true) {
            final GateHttp.Request request;
            try {
                request = GateHttp.request(in);
            } catch (IOException ex) {
                GateHttp.reply(out, "", 400, false, new byte[0]);
                return;
            }
            if (request == null) return;
            if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .contains(request.method())) {
                GateHttp.reply(out, request.method(), 501, false, new byte[0]);
                return;
            }
            final Refusal refusal = refusal(request, person);
            if (refusal != null) {
                GateHttp.reply(
                        out,
                        request.method(),
                        refusal.status(),
                        refusal.challenge(),
                        refusal.body());
                return;
            }
            if (!forward(request, person, in, out)) return;
        }
    }

    record Refusal(int status, boolean challenge, byte[] body) {
        Refusal(int status) {
            this(status, false, new byte[0]);
        }
    }

    @Nullable Refusal refusal(GateHttp.Request request, @Nullable String person) {
        final var headers = request.headers();
        final String method = request.method();
        final String target = request.target();
        if (mode.equals("web")) {
            if (person == null) return new Refusal(403);
            final Set<String> ownHosts = hosts.read();
            final List<String> host = headers.all("Host");
            if (host.size() != 1 || !ownHosts.contains(host.getFirst().toLowerCase(Locale.ROOT)))
                return new Refusal(
                        403, false, ForgeRoutes.guidance(headers, method, target, ownHosts));
            if (ForgeRoutes.foreign(headers, method)) return new Refusal(403);
        }
        if (ForgeRoutes.unsafe(target)) return new Refusal(400);
        if (ForgeRoutes.blocked(target, method, mode)) return new Refusal(403);
        final var authorizations = headers.all("Authorization");
        if (mode.equals("web")
                && authorizations.isEmpty()
                && ForgeRoutes.TOKEN_ONLY.matcher(ForgeRoutes.route(target)).matches())
            return new Refusal(headers.browser() ? 403 : 401, !headers.browser(), new byte[0]);
        if (authorizations.size() > 1) return new Refusal(400);
        final boolean tokens = !mode.equals("web");
        if (tokens
                && authorizations.isEmpty()
                && !(mode.equals("runner") && ForgeRoutes.runner(target)))
            return new Refusal(401, true, new byte[0]);
        final String schemes = mode.equals("runner") ? "Basic|token|Bearer" : "Basic|token";
        if (tokens
                && !authorizations.isEmpty()
                && !authorizations.getFirst().matches("(?i:" + schemes + ") [A-Za-z0-9+/=_\\-.]+"))
            return new Refusal(401, true, new byte[0]);
        final var lengths = headers.all("Content-Length");
        final var transfers = headers.all("Transfer-Encoding");
        if ((!transfers.isEmpty() && (!transfers.equals(List.of("chunked")) || !lengths.isEmpty()))
                || lengths.size() > 1
                || (!lengths.isEmpty() && !lengths.getFirst().matches("[0-9]{1,12}"))
                || !target.startsWith("/")
                || target.startsWith("//")
                || target.contains("#")) return new Refusal(400);
        if (omitted(headers).contains("authorization")) return new Refusal(400);
        return null;
    }

    private static Set<String> omitted(GateHttp.Headers headers) {
        final Set<String> result = new HashSet<>(GateHttp.HOP);
        for (String value : headers.all("Connection"))
            for (String item : value.split(",", -1))
                result.add(item.strip().toLowerCase(Locale.ROOT));
        return result;
    }

    private boolean forward(
            GateHttp.Request request, @Nullable String person, InputStream in, OutputStream out)
            throws IOException {
        final var headers = request.headers();
        final boolean chunked = !headers.all("Transfer-Encoding").isEmpty();
        final String length =
                headers.all("Content-Length").isEmpty() ? "0" : headers.first("Content-Length");
        if (headers.first("Expect").equalsIgnoreCase("100-continue")) {
            GateHttp.write(out, "HTTP/1.1 100 Continue\r\n\r\n");
            out.flush();
        }
        final GateConnection connection;
        final GateHttp.Response response;
        try {
            connection = upstream.open();
        } catch (IOException ex) {
            GateHttp.reply(out, request.method(), 502, false, new byte[0]);
            return false;
        }
        try (connection) {
            try {
                sendRequest(request, person, chunked, length, connection.output);
                if (chunked) GateHttp.chunks(in, connection.output, true, false);
                else GateHttp.fixed(in, connection.output, Long.parseLong(length));
                connection.output.flush();
                response = GateHttp.response(connection.input);
            } catch (IOException ex) {
                GateHttp.reply(out, request.method(), 502, false, new byte[0]);
                return false;
            }
            final boolean noBody =
                    request.method().equals("HEAD")
                            || response.status() == 204
                            || response.status() == 304
                            || response.status() < 200;
            final var lengths = response.headers().all("Content-Length");
            final boolean responseChunked =
                    response.headers().first("Transfer-Encoding").equalsIgnoreCase("chunked");
            final boolean fixed =
                    lengths.size() == 1
                            && lengths.getFirst().matches("[0-9]{1,18}")
                            && !responseChunked;
            final boolean keep =
                    mode.equals("web")
                            && request.version().equals("HTTP/1.1")
                            && response.status() >= 200
                            && headers.all("Connection").stream()
                                    .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                                    .noneMatch(value -> value.strip().equalsIgnoreCase("close"));
            final boolean encode = keep && !noBody && !fixed;
            GateHttp.write(
                    out,
                    "HTTP/1.1 "
                            + response.status()
                            + " "
                            + GateHttp.reason(response.status())
                            + "\r\n");
            for (GateHttp.Header header : response.headers().entries()) {
                if (!GateHttp.HOP.contains(header.name().toLowerCase(Locale.ROOT))
                        && !((responseChunked || encode)
                                && header.name().equalsIgnoreCase("Content-Length")))
                    GateHttp.write(out, header.name() + ": " + header.value() + "\r\n");
            }
            if (encode) GateHttp.write(out, "Transfer-Encoding: chunked\r\n");
            GateHttp.write(out, "Connection: " + (keep ? "keep-alive" : "close") + "\r\n\r\n");
            if (!noBody) {
                if (responseChunked) GateHttp.chunks(connection.input, out, encode, true);
                else if (fixed)
                    GateHttp.fixed(connection.input, out, Long.parseLong(lengths.getFirst()));
                else GateHttp.untilEnd(connection.input, out, encode);
            }
            out.flush();
            return keep;
        }
    }

    private void sendRequest(
            GateHttp.Request request,
            @Nullable String person,
            boolean chunked,
            String length,
            OutputStream out)
            throws IOException {
        final Set<String> omitted = omitted(request.headers());
        final var forwarded = new LinkedHashMap<String, String>();
        for (GateHttp.Header header : request.headers().entries()) {
            final String key = header.name().toLowerCase(Locale.ROOT);
            if (!omitted.contains(key)
                    && !key.startsWith("x-webauth-")
                    && !Set.of("host", "expect").contains(key)
                    && !(!mode.equals("web") && key.equals("cookie")))
                forwarded.put(header.name(), header.value());
        }
        forwarded.put("Host", "localhost");
        forwarded.put("Connection", "close");
        if (chunked) forwarded.put("Transfer-Encoding", "chunked");
        else {
            forwarded.keySet().removeIf(key -> key.equalsIgnoreCase("Content-Length"));
            forwarded.put("Content-Length", length);
        }
        if (mode.equals("web") && request.headers().all("Authorization").isEmpty())
            forwarded.put(
                    "X-WEBAUTH-USER",
                    java.util.Objects.requireNonNull(
                            person, "The web gate checks admission before forwarding"));
        GateHttp.write(out, request.method() + " " + request.target() + " HTTP/1.1\r\n");
        for (var header : forwarded.entrySet())
            GateHttp.write(out, header.getKey() + ": " + header.getValue() + "\r\n");
        GateHttp.write(out, "\r\n");
    }
}
