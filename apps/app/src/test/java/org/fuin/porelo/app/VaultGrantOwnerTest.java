package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.supervisor.TokenPurchase;
import org.fuin.porelo.supervisor.TokenSource;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards a rotating grant against spending while its vault cannot save the replacement. */
class VaultGrantOwnerTest {
    @Test
    void lockingBeforeRenewalNeverSpendsTheGrant(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final Fixture fixture = new Fixture(dir, owner, () -> {});
            final TokenSource source = fixture.source();
            owner.forget();
            assertThatThrownBy(source::current)
                    .as("renewal needs the owner before buying")
                    .isInstanceOf(TokenPurchase.Refused.class);
            assertThat(fixture.http.sent)
                    .as("a locked vault must not spend a rotating token")
                    .isZero();
        }
    }

    @Test
    void aLockDuringPurchaseStillKeepsTheReplacement(@TempDir Path dir) throws Exception {
        try (HostVaultOwner owner = new HostVaultOwner()) {
            final Fixture fixture = new Fixture(dir, owner, owner::forget);
            final TokenSource source = fixture.source();
            assertThat(source.current())
                    .as("the purchase already in flight completes")
                    .isEqualTo("new-access");
            assertThat(owner.isOpen()).as("the concurrent lock stays in effect").isFalse();
            final var entries = fixture.context.vault().read(fixture.share);
            assertThat(entries.get(TaskSecrets.GRANT_PREFIX + "service").value())
                    .as("the replacement survives a broker restart")
                    .isEqualTo("new-refresh");
            assertThat(entries.get("service"))
                    .as("the service's client configuration is preserved")
                    .isEqualTo(fixture.entry);
            assertThat(source.current())
                    .as("a token already bought remains usable after lock")
                    .isEqualTo("new-access");
            assertThat(fixture.http.sent).as("the access token is cached").isEqualTo(1);
        }
    }

    private static final class Fixture {
        private final byte[] share = new byte[32];
        private final PoreloContext context;
        private final ReplyingClient http;
        private final VaultEntry entry =
                new VaultEntry(
                        "client-secret",
                        "oauth-device",
                        Map.of(
                                "device_authorization_url",
                                "https://service.example/device",
                                "token_url",
                                "https://service.example/token",
                                "client_id",
                                "client"));

        private Fixture(Path dir, HostVaultOwner owner, Runnable duringPurchase) {
            context =
                    new PoreloContext(
                                    new FakeCommandRunner(),
                                    new PoreloPaths(
                                            XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                    args -> 0)
                            .withHostVault(owner);
            java.util.Arrays.fill(share, (byte) 34);
            context.vault()
                    .write(
                            Map.of(
                                    "service",
                                    entry,
                                    TaskSecrets.GRANT_PREFIX + "service",
                                    VaultEntry.of("old-refresh")),
                            share);
            owner.keep(share);
            http = new ReplyingClient(duringPurchase);
        }

        private TokenSource source() {
            final VaultServeCommand serve = new VaultServeCommand();
            serve.setContext(context);
            serve.tokenHttp = () -> http;
            return java.util.Objects.requireNonNull(
                    serve.granted("service", entry, new PrintWriter(new StringWriter())));
        }
    }

    private static final class ReplyingClient extends HttpClient {
        private final Runnable duringPurchase;
        private int sent;

        private ReplyingClient(Runnable duringPurchase) {
            this.duringPurchase = duringPurchase;
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
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
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
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sent++;
            duringPurchase.run();
            final HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);
            final HttpResponse.BodySubscriber<T> subscriber =
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
            subscriber.onNext(
                    List.of(
                            ByteBuffer.wrap(
                                    "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}"
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            subscriber.onComplete();
            final T body = subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
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
                    return body;
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
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
    }
}
