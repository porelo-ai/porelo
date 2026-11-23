package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import java.util.stream.LongStream;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests how {@link HostPullRequests} pages through a host forge's open pull requests. */
class HostPullRequestsTest {
    private static final String TOKEN = "b".repeat(40);
    private static final String REPOSITORY = "/api/v1/repos/owner/repo";
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void theBranchsPullBeyondPageOneHundredIsFound() throws Exception {
        // Every page is read: a pull past the hundredth page is the branch's, never a refusal.
        final long total = 100L * 50 + 1;
        final var pulls = start(page -> page(LongStream.rangeClosed(1, total), page, ""));

        assertThat(pulls.open("task", "branch-" + total, "Subject"))
                .as("the branch's existing pull, found on page 101")
                .isEqualTo("http://127.0.0.1/owner/repo/pulls/" + total);
        assertThat(asked)
                .as("page 101 was asked and nothing was opened")
                .contains("GET " + REPOSITORY + "/pulls?state=open&limit=50&page=101")
                .noneMatch(line -> line.startsWith("POST"));
    }

    @Test
    void aPageOfAboutTwoMiBIsTaken() throws Exception {
        // A legal page of pulls with long multibyte bodies is far above one MiB.
        final String body = "€".repeat(65_536);
        final var pulls = start(page -> page(LongStream.rangeClosed(1, 11), page, body));

        assertThat(pulls.open("task", "branch-new", "Subject"))
                .as("the page was read whole and the pull opened")
                .isEqualTo("http://127.0.0.1/owner/repo/pulls/12");
        assertThat(asked)
                .as("one page answered the list")
                .contains("POST " + REPOSITORY + "/pulls");
    }

    @Test
    void anAnswerAboveTheBoundIsRefused() throws Exception {
        // No forge answer is held in memory beyond the bound above the largest legal page.
        final var pulls =
                start(
                        page ->
                                (" ".repeat(32 * 1024 * 1024) + "[]")
                                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> pulls.open("task", "branch-new", "Subject"))
                .as("an answer above 32 MiB is refused")
                .isInstanceOf(GateException.class)
                .hasMessageContaining("the pull request failed");
        assertThat(asked).as("nothing was opened").noneMatch(line -> line.startsWith("POST"));
    }

    @Test
    void aForgeRepeatingAFullPageIsRefusedLoudly() throws Exception {
        // A forge that ignores the page asked would otherwise be read forever.
        final var pulls = start(page -> page(LongStream.rangeClosed(1, 50), 1, ""));

        assertThatThrownBy(() -> pulls.open("task", "branch-new", "Subject"))
                .as("a page adding no new pull is refused")
                .isInstanceOf(GateException.class)
                .hasMessageContaining("repeated a page of open pull requests");
        assertThat(asked)
                .as("refused at the second page, nothing opened")
                .containsExactly(
                        "GET " + REPOSITORY,
                        "GET " + REPOSITORY + "/pulls?state=open&limit=50&page=1",
                        "GET " + REPOSITORY + "/pulls?state=open&limit=50&page=2");
    }

    @Test
    void aForgeAnsweringFewerThanAskedOnEveryPageIsReadToItsEnd() throws Exception {
        // A forge whose largest page is below the 50 asked answers 30 a page: a short page is not
        // the last one, so the branch's pull on page 2 is found and no second one is opened.
        final var pulls =
                start(
                        page -> {
                            final List<Object> entries = new ArrayList<>();
                            LongStream.rangeClosed(1, 40)
                                    .skip((page - 1) * 30L)
                                    .limit(30)
                                    .forEach(n -> entries.add(entry(n, "")));
                            return Json.write(entries).getBytes(StandardCharsets.UTF_8);
                        });

        assertThat(pulls.open("task", "branch-35", "Subject"))
                .as("the branch's existing pull, on the second page of 30")
                .isEqualTo("http://127.0.0.1/owner/repo/pulls/35");
        assertThat(asked).as("nothing was opened").noneMatch(line -> line.startsWith("POST"));
    }

    private HostPullRequests start(IntFunction<byte[]> pages) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                REPOSITORY,
                exchange -> {
                    final String query = exchange.getRequestURI().getRawQuery();
                    asked.add(
                            exchange.getRequestMethod()
                                    + " "
                                    + exchange.getRequestURI().getPath()
                                    + (query == null ? "" : "?" + query));
                    final String path = exchange.getRequestURI().getPath();
                    if (path.equals(REPOSITORY)) {
                        answer(exchange, 200, Json.write(Map.of("default_branch", "main")));
                    } else if (exchange.getRequestMethod().equals("POST")) {
                        answer(exchange, 201, Json.write(entry(12, "")));
                    } else {
                        final int page =
                                Integer.parseInt(query.substring(query.indexOf("page=") + 5));
                        answer(exchange, 200, pages.apply(page));
                    }
                });
        server.start();
        return new HostPullRequests(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/owner/repo.git",
                task -> Optional.of(TOKEN),
                LimitFactors.DEFAULT);
    }

    private static byte[] page(LongStream numbers, int page, String body) {
        final List<Object> entries = new ArrayList<>();
        numbers.skip((page - 1) * 50L).limit(50).forEach(n -> entries.add(entry(n, body)));
        return Json.write(entries).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> entry(long number, String body) {
        return Map.of(
                "number",
                number,
                "body",
                body,
                "head",
                Map.of("ref", "branch-" + number),
                "base",
                Map.of("ref", "main"),
                "html_url",
                "http://127.0.0.1/owner/repo/pulls/" + number);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        answer(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        } catch (IOException ex) {
            // The client may stop reading an answer above its bound.
        }
    }
}
