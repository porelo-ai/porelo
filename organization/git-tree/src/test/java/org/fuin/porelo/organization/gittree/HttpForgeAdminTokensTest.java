package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpForgeAdminTokensTest {
    @FunctionalInterface
    private interface Requests {
        Object send(String method, String route, int expected, Object body) throws IOException;
    }

    private static HttpForgeAdmin client(Requests replies) {
        return new HttpForgeAdmin(
                (method, path, authorization, body) -> {
                    Object value =
                            replies.send(
                                    method,
                                    path.substring("/admin/users/".length()),
                                    method.equals("DELETE") ? 204 : 200,
                                    body.length == 0
                                            ? null
                                            : org.fuin.porelo.wire.Json.parseExact(
                                                    new String(body, StandardCharsets.UTF_8)));
                    return new HttpForgeAdmin.Answer(
                            method.equals("DELETE") ? 204 : 200,
                            value == null
                                    ? new byte[0]
                                    : org.fuin.porelo.wire.Json.write(value)
                                            .getBytes(StandardCharsets.UTF_8));
                },
                "fixture-admin-token".toCharArray(),
                false);
    }

    private static Object answer(java.io.InputStream input, int expected) {
        try (var api =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> HttpForgeAdmin.answer(input),
                        "fixture-admin-token".toCharArray(),
                        false)) {
            return api.requestUser(
                    expected == 204 ? "DELETE" : "GET", "alice/tokens", expected, null);
        }
    }

    @Test
    void anEmptyRevocationReplyCannotCompleteAPersonLock() {
        try (var api =
                new HttpForgeAdmin(
                        (method, path, authorization, body) ->
                                new HttpForgeAdmin.Answer(200, new byte[0]),
                        "fixture-admin-token".toCharArray(),
                        false)) {
            assertThatThrownBy(
                            () ->
                                    api.requestUser(
                                            "PATCH", "alice", 200, Map.of("prohibit_login", true)))
                    .as("a person lock stays pending when the forge gives no JSON confirmation")
                    .isInstanceOfSatisfying(
                            HttpForgeAdmin.Refusal.class,
                            error ->
                                    assertThat(error.exitCode())
                                            .as("the service did not answer")
                                            .isEqualTo(69));
        }
    }

    @Test
    void everyPageIsReadBeforeDeletionInNumericOrder() throws Exception {
        final List<String> calls = new ArrayList<>();
        final HttpForgeAdmin client =
                client(
                        (method, route, expected, body) -> {
                            calls.add(method + " " + route);
                            if (method.equals("DELETE")) {
                                assertThat(expected)
                                        .as("successful deletion is exactly 204")
                                        .isEqualTo(204);
                                return null;
                            }
                            return switch (route) {
                                case "porelo-alice/tokens?limit=50&page=1" ->
                                        List.of(row(20, "task"), row(2, "other"));
                                case "porelo-alice/tokens?limit=50&page=2" ->
                                        List.of(row(3, "task"));
                                default -> List.of();
                            };
                        });
        assertThat(client.dropNamed("porelo-alice", Set.of("task")))
                .as("all matching IDs, including duplicate names, are deleted")
                .containsExactly("task", "task");
        assertThat(calls)
                .as("deleting while paging would skip tokens when Forgejo shrinks its result set")
                .containsExactly(
                        "GET porelo-alice/tokens?limit=50&page=1",
                        "GET porelo-alice/tokens?limit=50&page=2",
                        "GET porelo-alice/tokens?limit=50&page=3",
                        "DELETE porelo-alice/tokens/3",
                        "DELETE porelo-alice/tokens/20");
    }

    private static Map<String, Object> row(int id, String name) {
        return Map.of("id", BigInteger.valueOf(id), "name", name);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateIdsWithinOnePageNeverChooseWhichTokenToDelete(boolean reversed) {
        final List<String> calls = new ArrayList<>();
        try (var api =
                client(
                        (method, route, expected, body) -> {
                            calls.add(method);
                            if (!route.endsWith("page=1")) return List.of();
                            return reversed
                                    ? List.of(row(7, "task"), row(7, "keep"))
                                    : List.of(row(7, "keep"), row(7, "task"));
                        })) {
            assertThatThrownBy(() -> api.dropNamed("porelo-alice", Set.of("task")))
                    .as("ambiguous IDs cannot authorize deletion or hide an expired token")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class)
                    .hasMessage("Forgejo repeated a page of access tokens");
        }
        assertThat(calls).as("no deletion follows an ambiguous page").containsExactly("GET");
    }

    @Test
    void aRepeatedPageIsRefusedBeforeAnyDeletion() {
        final List<String> calls = new ArrayList<>();
        final HttpForgeAdmin client =
                client(
                        (method, route, expected, body) -> {
                            calls.add(method);
                            return List.of(row(1, "task"));
                        });
        assertThatThrownBy(() -> client.dropNamed("porelo-alice", Set.of("task")))
                .as("a broken pager cannot loop or partially delete")
                .isInstanceOf(HttpForgeAdmin.Refusal.class)
                .hasMessage("Forgejo repeated a page of access tokens");
        assertThat(calls).as("only pages were requested").containsExactly("GET", "GET");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[null]",
                "[{}]",
                "[{\"id\":true}]",
                "[{\"id\":1.0}]",
                "[{\"id\":0}]",
                "[{\"id\":-1}]",
                "[{\"id\":\"1\"}]"
            })
    void invalidTokenListsAreRefusedBeforeAnyDeletion(String json) {
        final HttpForgeAdmin client =
                client(
                        (method, route, expected, body) ->
                                org.fuin.porelo.wire.Json.parseNumberTypes(json));
        assertThatThrownBy(() -> client.dropNamed("porelo-alice", Set.of("task")))
                .as("Python requires positive integer IDs, excluding booleans and floats")
                .isInstanceOf(HttpForgeAdmin.Refusal.class)
                .hasMessage("Forgejo answered no valid access-token list");
    }

    @Test
    void unknownTokenNamesAreRefusedForANamedSweepButRevokedForALock() throws Exception {
        final List<String> calls = new ArrayList<>();
        final HttpForgeAdmin client =
                client(
                        (method, route, expected, body) -> {
                            calls.add(method + " " + route);
                            return route.endsWith("page=1")
                                    ? List.of(
                                            Map.of("id", BigInteger.ONE),
                                            Map.of("id", BigInteger.TWO, "name", 123))
                                    : List.of();
                        });
        assertThatThrownBy(() -> client.dropNamed("alice", Set.of("porelo-task-one")))
                .as("a named deletion refuses malformed names instead of treating them as absence")
                .isInstanceOf(HttpForgeAdmin.Refusal.class)
                .hasMessage("Forgejo answered no valid access-token name");
        client.dropNamed("alice", null);
        assertThat(calls)
                .as("a lock revokes every token even without a string name")
                .endsWith("DELETE alice/tokens/1", "DELETE alice/tokens/2");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n[\r\n1\r\n]\r\n0\r\n\r\n",
                "HTTP/1.0 200 OK\r\n\r\n[]"
            })
    void lengthChunkedAndConnectionCloseResponsesAreRead(String response) throws Exception {
        assertThat(answer(stream(response), 200))
                .as("Forgejo may frame a JSON response in any standard way")
                .isEqualTo(List.of());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not HTTP\r\n",
                "HTTP/1.1 200 OK\r\nBad\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nContent-Length: 16777217\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n[",
                "HTTP/1.1 200 OK\n\n[]"
            })
    void malformedOrTruncatedFramingIsRefused(String response) {
        assertThatThrownBy(() -> answer(stream(response), 200))
                .as("unframed or oversized data never becomes a partial token list")
                .isInstanceOf(HttpForgeAdmin.Refusal.class);
    }

    @Test
    void aNoContentDeletionNeedsNoJsonBody() throws Exception {
        assertThat(answer(stream("HTTP/1.1 204 No Content\r\n\r\n"), 204))
                .as("successful deletion has no JSON")
                .isNull();
    }

    @Test
    void invalidUtf8AndInvalidJsonAreNotValidApiAnswers() {
        assertThatThrownBy(
                        () ->
                                answer(
                                        new ByteArrayInputStream(
                                                new byte[] {
                                                    'H',
                                                    'T',
                                                    'T',
                                                    'P',
                                                    '/',
                                                    '1',
                                                    '.',
                                                    '1',
                                                    ' ',
                                                    '2',
                                                    '0',
                                                    '0',
                                                    ' ',
                                                    'O',
                                                    'K',
                                                    13,
                                                    10,
                                                    13,
                                                    10,
                                                    (byte) 0xff
                                                }),
                                        200))
                .as("invalid UTF-8 is not silently repaired")
                .isInstanceOf(HttpForgeAdmin.Refusal.class);
        assertThatThrownBy(() -> answer(stream("HTTP/1.1 200 OK\r\n\r\n{"), 200))
                .as("malformed JSON fails before tokens can be examined")
                .isInstanceOf(HttpForgeAdmin.Refusal.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../alice",
                "alice\r\nInjected: value",
                "alice/tokens?limit=50&page=1\r\n",
                "alice/../../other"
            })
    void routesCannotLeaveTheLocallyConstructedAdminPath(String route) {
        final HttpForgeAdmin client =
                client(
                        (method, target, expected, body) -> {
                            throw new AssertionError("Invalid routes reach no transport");
                        });
        assertThatThrownBy(() -> client.requestUser("GET", route, 200, null))
                .as("route validation precedes transport")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Forgejo admin route");
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}"})
    void unhashableTokenNamesStopANamedSweepInsteadOfBecomingAbsence(String name) {
        final List<String> calls = new ArrayList<>();
        final HttpForgeAdmin client =
                client(
                        (method, route, expected, body) -> {
                            calls.add(method);
                            return route.endsWith("page=1")
                                    ? List.of(
                                            Map.of(
                                                    "id",
                                                    BigInteger.ONE,
                                                    "name",
                                                    org.fuin.porelo.wire.Json.parseNumberTypes(
                                                            name)))
                                    : List.of();
                        });
        assertThatThrownBy(() -> client.dropNamed("porelo-alice", Set.of("porelo-task-one")))
                .as("Python cannot test an unhashable name against its due-name set either")
                .isInstanceOf(HttpForgeAdmin.Refusal.class)
                .hasMessage("Forgejo answered no valid access-token name");
        assertThat(calls)
                .as("the malformed name stops the call before any token is deleted")
                .containsExactly("GET", "GET");
    }
}
