package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.gittree.CardProposals;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every forge operation is confined to a fake HTTP forge and carries the lent token. */
class ForgeCardProposalsTest {
    final List<String> calls = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    final List<Object> bodies = new ArrayList<>();
    int polls;
    int failed;
    final HttpClient http = new FakeClient();

    private class FakeClient extends HttpClient {
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        public java.util.Optional<java.time.Duration> connectTimeout() {
            return java.util.Optional.empty();
        }

        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        public Version version() {
            return Version.HTTP_1_1;
        }

        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }

        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) {
            String path = request.uri().getPath();
            calls.add(request.method() + " " + path);
            tokens.add(request.headers().firstValue("Authorization").orElse(""));
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            request.bodyPublisher()
                    .ifPresent(
                            publisher ->
                                    publisher.subscribe(
                                            new java.util.concurrent.Flow.Subscriber<
                                                    java.nio.ByteBuffer>() {
                                                public void onSubscribe(
                                                        java.util.concurrent.Flow.Subscription
                                                                subscription) {
                                                    subscription.request(Long.MAX_VALUE);
                                                }

                                                public void onNext(java.nio.ByteBuffer bytes) {
                                                    byte[] part = new byte[bytes.remaining()];
                                                    bytes.get(part);
                                                    body.writeBytes(part);
                                                }

                                                public void onError(Throwable failure) {
                                                    throw new AssertionError(failure);
                                                }

                                                public void onComplete() {}
                                            }));
            if (body.size() > 0) bodies.add(Json.parse(body.toString(StandardCharsets.UTF_8)));
            int status = 200;
            Object answer = Map.of();
            if (path.endsWith("/user")) answer = Map.of("login", "alice");
            else if (path.endsWith("/forks")) status = 202;
            else if (path.contains("/repos/alice/")) {
                if (++polls < 3) status = 404;
            } else if (path.endsWith("/pulls") && request.method().equals("GET"))
                answer = List.of(pull(path));
            else if (path.contains("/pulls")) {
                answer = pull(path);
                if (request.method().equals("POST")) status = 201;
            } else if (path.endsWith("/comments")) status = 201;
            if (failed != 0) {
                status = failed;
                answer = Map.of("message", "lent-secret must never be echoed");
            }
            final int code = status;
            final var headers =
                    java.net.http.HttpHeaders.of(
                            Map.of("Content-Type", List.of("application/json")),
                            (name, value) -> true);
            var subscriber =
                    handler.apply(
                            new java.net.http.HttpResponse.ResponseInfo() {
                                public int statusCode() {
                                    return code;
                                }

                                public java.net.http.HttpHeaders headers() {
                                    return headers;
                                }

                                public Version version() {
                                    return Version.HTTP_1_1;
                                }
                            });
            subscriber.onSubscribe(
                    new java.util.concurrent.Flow.Subscription() {
                        public void request(long count) {}

                        public void cancel() {}
                    });
            subscriber.onNext(
                    List.of(
                            java.nio.ByteBuffer.wrap(
                                    Json.write(answer).getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            final T received = subscriber.getBody().toCompletableFuture().join();
            return new java.net.http.HttpResponse<>() {
                public int statusCode() {
                    return code;
                }

                public java.net.http.HttpRequest request() {
                    return request;
                }

                public java.util.Optional<java.net.http.HttpResponse<T>> previousResponse() {
                    return java.util.Optional.empty();
                }

                public java.net.http.HttpHeaders headers() {
                    return headers;
                }

                public T body() {
                    return received;
                }

                public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return java.util.Optional.empty();
                }

                public URI uri() {
                    return request.uri();
                }

                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(send(request, handler));
        }

        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler,
                java.net.http.HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
    }

    Map<?, ?> pull(String path) {
        if (!path.startsWith("/api/v1/")) {
            try (var input =
                    getClass().getResourceAsStream("/forge-answers/github-card-pull.json")) {
                return (Map<?, ?>)
                        Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
        return Map.of(
                "number",
                7,
                "html_url",
                "https://forge.example/acme/org/pulls/7",
                "user",
                Map.of("login", "alice"),
                "head",
                Map.of(
                        "ref",
                        "porelo-card-alice",
                        "sha",
                        "a".repeat(40),
                        "repo",
                        Map.of("owner", Map.of("login", "alice"))),
                "base",
                Map.of("ref", "main"),
                "created_at",
                "2026-11-12T12:00:00Z",
                "state",
                "open",
                "merged",
                false);
    }

    ForgeCardProposals forge(boolean github) {
        ForgeAddress address =
                ForgeAddress.of(
                        github
                                ? "https://github.com/acme/org.git"
                                : "https://forge.example/acme/org.git");
        return new ForgeCardProposals(
                address,
                "lent-secret",
                http,
                URI.create("http://127.0.0.1" + (github ? "/" : "/api/v1/")));
    }

    @ParameterizedTest
    @CsvSource({
        "https://acme/acme/org.git, https://acme/alice/org.git",
        "https://forge.example/acme/org.git, https://forge.example/alice/org.git",
        "https://forge.example:3000/git/acme/org.git, https://forge.example:3000/git/alice/org.git",
        "git@acme:acme/org.git, git@acme:alice/org.git"
    })
    void aForkIsNamedByTheOwnerBeforeTheRepositoryNotByAHostOfTheSameName(
            String organization, String fork) {
        assertThat(ForgeCardProposals.forkUrl(organization, "acme", "alice"))
                .as("only the owner segment changes")
                .isEqualTo(fork);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void forksPollsOpensListsReadsAndClosesWithTheLentToken(boolean github) {
        ForgeCardProposals forge = forge(github);
        assertThat(forge.login()).as("token's own account").isEqualTo("alice");
        assertThat(forge.fork("alice"))
                .as("derived same-forge git address")
                .isEqualTo(
                        github
                                ? "https://github.com/alice/org.git"
                                : "https://forge.example/alice/org.git");
        assertThat(polls)
                .as("GitHub asynchronous fork must answer before push")
                .isGreaterThanOrEqualTo(github ? 3 : 0);
        CardProposals.Pull opened = forge.open("alice", true);
        assertThat(opened.number()).as("opened pull request").isEqualTo(7);
        assertThat(forge.list()).as("open pull requests").hasSize(1);
        assertThat(forge.read(7).commit()).as("fresh head").isEqualTo("a".repeat(40));
        assertThat(forge.read(7).sourceOwner())
                .as("refresh retains the proposal's source repository")
                .isEqualTo("alice");
        forge.close(7, "Withdrawn by the person.");
        String prefix = github ? "" : "/api/v1";
        assertThat(calls)
                .as("close and comment are distinct operations")
                .contains(
                        "POST " + prefix + "/repos/acme/org/issues/7/comments",
                        "PATCH " + prefix + "/repos/acme/org/pulls/7");
        assertThat(tokens)
                .as("every API call uses the lent token")
                .allMatch(value -> value != null && value.contains("lent-secret"));
        assertThat(bodies)
                .as("base and exact personal branch")
                .anySatisfy(
                        value ->
                                assertThat(value.toString())
                                        .as("open body")
                                        .contains("base=main", "head=alice:porelo-card-alice"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void forgeFailureNeverRepeatsTheToken(boolean github) {
        failed = 403;
        assertThatThrownBy(() -> forge(github).fork("alice"))
                .as("refusal sanitizes forge text")
                .hasMessageNotContaining("lent-secret")
                .hasMessageContaining("read");
    }
}
