package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** A forge's available name must not become a missing card field because its dialect differs. */
class HttpForgeProfileTest {
    @Test
    void a_forgejo_profile_names_the_person_with_full_name() {
        final var directory =
                new HttpForgeDirectory(
                        URI.create("https://forge.example.org"),
                        null,
                        new ReplyClient(
                                "{\"login\":\"alice\",\"full_name\":\"Alice\",\"email\":null}"));
        assertThat(directory.profile("alice"))
                .as("Forgejo's public display name is available for the card")
                .contains(new HttpForgeDirectory.Profile("alice", "Alice", null));
    }

    @Test
    void a_github_profile_names_the_person_with_name() {
        final var directory =
                new HttpForgeDirectory(
                        URI.create("https://github.com"),
                        null,
                        new ReplyClient("{\"login\":\"alice\",\"name\":\"Alice\",\"email\":\"\"}"));
        assertThat(directory.profile("alice"))
                .as("GitHub's public display name is available and empty email is absent")
                .contains(new HttpForgeDirectory.Profile("alice", "Alice", null));
    }

    /** Supplies whole HTTP bodies through the same bounded subscriber as a network response. */
    private static final class ReplyClient extends HttpClient {
        private final String body;

        ReplyClient(String body) {
            this.body = body;
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            final HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);
            final var subscriber =
                    handler.apply(
                            new HttpResponse.ResponseInfo() {
                                @Override
                                public int statusCode() {
                                    return 200;
                                }

                                @Override
                                public HttpHeaders headers() {
                                    return headers;
                                }

                                @Override
                                public Version version() {
                                    return Version.HTTP_1_1;
                                }
                            });
            subscriber.onSubscribe(
                    new Flow.Subscription() {
                        @Override
                        public void request(long count) {}

                        @Override
                        public void cancel() {}
                    });
            subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            return subscriber
                    .getBody()
                    .toCompletableFuture()
                    .thenApply(
                            value ->
                                    new HttpResponse<T>() {
                                        @Override
                                        public int statusCode() {
                                            return 200;
                                        }

                                        @Override
                                        public HttpRequest request() {
                                            return request;
                                        }

                                        @Override
                                        public Optional<HttpResponse<T>> previousResponse() {
                                            return Optional.empty();
                                        }

                                        @Override
                                        public HttpHeaders headers() {
                                            return headers;
                                        }

                                        @Override
                                        public T body() {
                                            return value;
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
                                        public Version version() {
                                            return Version.HTTP_1_1;
                                        }
                                    });
        }
    }
}
