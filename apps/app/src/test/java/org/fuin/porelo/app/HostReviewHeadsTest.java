package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostReviewHeadsTest {
    @TempDir Path directory;

    /**
     * A review head whose pack is larger than 32 MiB is fetched through the real fetch: git, the
     * smart HTTP protocol and a pull request ref, as the host's forge serves them.
     */
    @Test
    @Tag("slow")
    void aReviewHeadWithAPackLargerThanThirtyTwoMebibytesIsFetched() throws Exception {
        final Path served = directory.resolve("served");
        final Path origin = served.resolve("demo/demo.git");
        git(directory, "init", "--quiet", "--bare", origin.toString());
        // Random bytes, so that the pack cannot compress below the size.
        final Path big = directory.resolve("big");
        final byte[] bytes = new byte[33 * 1024 * 1024];
        new java.util.Random(7).nextBytes(bytes);
        Files.write(big, bytes);
        final String blob = git(origin, "hash-object", "-w", big.toString());
        final String tree = gitWithInput(origin, "100644 blob " + blob + "\tbig\n", "mktree");
        final String head = git(origin, "commit-tree", tree, "-m", "a large review head");
        git(origin, "update-ref", "refs/pull/7/head", head);
        final HttpServer forge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        forge.createContext("/demo/demo.git", exchange -> backend(served, exchange));
        forge.start();
        try {
            final Path store = directory.resolve("review.git");
            HostReviewHeads.fetch(
                    store,
                    URI.create(
                            "http://127.0.0.1:"
                                    + forge.getAddress().getPort()
                                    + "/api/v1/repos/demo/demo"),
                    7,
                    head,
                    "test-token",
                    LimitFactors.DEFAULT);
            long packed = 0;
            try (var packs = Files.list(store.resolve("objects/pack"))) {
                for (final Path each : packs.toList())
                    if (each.toString().endsWith(".pack")) packed += Files.size(each);
            }
            assertThat(packed).isGreaterThan(32L * 1024 * 1024);
            assertThat(git(store, "rev-parse", "--verify", "refs/porelo/review^{commit}"))
                    .isEqualTo(head);
        } finally {
            forge.stop(0);
        }
    }

    private static String git(Path where, String... arguments) throws Exception {
        return gitWithInput(where, null, arguments);
    }

    private static String gitWithInput(Path where, String input, String... arguments)
            throws Exception {
        final List<String> command = new ArrayList<>(List.of("git"));
        if (Files.isDirectory(where.resolve("objects"))) command.add("--git-dir=" + where);
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(where.toFile());
        identity(builder.environment());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process process = builder.start();
        try (var stdin = process.getOutputStream()) {
            if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
        return output.strip();
    }

    private static void identity(Map<String, String> environment) {
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        for (final String role : List.of("AUTHOR", "COMMITTER")) {
            environment.put("GIT_" + role + "_NAME", "Reviewer");
            environment.put("GIT_" + role + "_EMAIL", "reviewer@example.org");
            environment.put("GIT_" + role + "_DATE", "1700000000 +0000");
        }
    }

    /** The forge's smart HTTP, through git's own CGI program. */
    private static void backend(Path served, HttpExchange exchange) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder("git", "http-backend");
        final Map<String, String> env = builder.environment();
        identity(env);
        env.put("GIT_PROJECT_ROOT", served.toString());
        env.put("GIT_HTTP_EXPORT_ALL", "1");
        env.put("PATH_INFO", exchange.getRequestURI().getPath());
        env.put(
                "QUERY_STRING",
                java.util.Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""));
        env.put("REQUEST_METHOD", exchange.getRequestMethod());
        env.put(
                "CONTENT_TYPE",
                java.util.Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("Content-Type"), ""));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process process = builder.start();
        try (var input = process.getOutputStream()) {
            exchange.getRequestBody().transferTo(input);
        }
        final byte[] response = process.getInputStream().readAllBytes();
        final String text = new String(response, StandardCharsets.ISO_8859_1);
        final int seam = text.indexOf("\r\n\r\n");
        int status = 200;
        for (String header : text.substring(0, seam).split("\r\n")) {
            final int colon = header.indexOf(':');
            final String name = header.substring(0, colon);
            final String value = header.substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Status")) status = Integer.parseInt(value.substring(0, 3));
            else exchange.getResponseHeaders().add(name, value);
        }
        exchange.sendResponseHeaders(status, response.length - seam - 4);
        try (exchange) {
            exchange.getResponseBody().write(response, seam + 4, response.length - seam - 4);
        }
        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }

    @Test
    void reviewFetchTimeFollowsMachinesRatherThanServices() {
        assertThat(HostReviewHeads.bound(LimitFactors.parse("machines=2.0,services=3.0")))
                .isEqualTo(Duration.ofSeconds(40));
    }
}
