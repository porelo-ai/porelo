package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Grants}: which endpoints a grant may use, and how a failure to reach one is
 * said.
 */
class GrantsTest {

    @Test
    void httpsAnywhereAndPlainHttpOnlyOnLoopback() {
        assertThat(Grants.secure("https://auth.example.org/token")).isTrue();
        assertThat(Grants.secure("http://127.0.0.1:8080/token")).isTrue();
        assertThat(Grants.secure("http://localhost/token")).isTrue();
        assertThat(Grants.secure("http://[::1]:9000/token")).isTrue();
        assertThat(Grants.secure("http://auth.example.org/token"))
                .as("leaves the machine unencrypted")
                .isFalse();
        assertThat(Grants.secure("http://127.0.0.1.example.org/token")).isFalse();
        assertThat(Grants.secure("ftp://127.0.0.1/token")).isFalse();
        // A stand-in on the machine can now be measured against.
        assertThat(
                        DeviceGrant.Client.of(
                                        "-",
                                        Map.of(
                                                "client_id",
                                                "x",
                                                "device_authorization_url",
                                                "http://127.0.0.1:1/device",
                                                "token_url",
                                                "http://127.0.0.1:1/token"))
                                .tokenUrl())
                .isEqualTo("http://127.0.0.1:1/token");
    }

    @Test
    void aServiceNobodyListensForIsSaidAsRefusedNotAsNull() throws IOException {
        final int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        assertThatThrownBy(
                        () ->
                                Grants.send(
                                        HttpClient.newHttpClient(),
                                        "http://127.0.0.1:" + port + "/token",
                                        Map.of("a", "b")))
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageEndingWith("could not be reached: the connection was refused")
                .hasMessageNotContaining("null");
    }

    @Test
    void aHostThatDoesNotExistIsSaidAsSuch() {
        assertThatThrownBy(
                        () ->
                                Grants.send(
                                        HttpClient.newHttpClient(),
                                        "https://auth.invalid/token",
                                        Map.of("a", "b")))
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageEndingWith("could not be reached: no such host");
    }

    @Test
    void aFailureWithNoMessageIsNamedByItsKind() {
        assertThat(Grants.cause(new IOException())).isEqualTo("IOException");
        assertThat(Grants.cause(new IOException("said"))).isEqualTo("IOException");
    }

    @Test
    void anErrorIsRepeatedOnlyWhenItIsACodeOAuthDefines() {
        assertThat(Grants.error("{\"error\":\"invalid_grant\"}")).isEqualTo("invalid_grant");
        assertThat(Grants.error("{\"error\":\"invalid_redirect_uri\"}"))
                .isEqualTo("invalid_redirect_uri");
        assertThat(Grants.error("{\"error\":\"unsupported_token_type\"}"))
                .isEqualTo("unsupported_token_type");
        assertThat(Grants.error("{\"error\":\"the-client-secret-sent\"}"))
                .isEqualTo("an error code OAuth does not define");
        assertThat(Grants.error("{\"error\":7}")).isEqualTo("an error code OAuth does not define");
        assertThat(Grants.error("not json")).isEqualTo("no error named");
    }

    @Test
    void aBearerTokensErrorIsACodeToo() {
        assertThat(Grants.error("{\"error\":\"invalid_token\"}")).isEqualTo("invalid_token");
        assertThat(Grants.error("{\"error\":\"insufficient_scope\"}"))
                .isEqualTo("insufficient_scope");
        assertThat(Grants.named("unsupported_response_type"))
                .isEqualTo("unsupported_response_type");
    }

    @Test
    void whatAFailureSaidIsNotRepeated() {
        assertThat(
                        Grants.cause(
                                new java.io.IOException(
                                        "Invalid status line: \"the-client-secret\"")))
                .doesNotContain("the-client-secret")
                .isEqualTo("IOException");
        assertThat(
                        Grants.cause(
                                new java.io.IOException(
                                        new javax.net.ssl.SSLException("the-client-secret"))))
                .doesNotContain("the-client-secret")
                .contains("TLS");
    }

    @Test
    void anAddressIsShownWithoutWhatItCarries() {
        assertThat(Grants.shown("http://a.example/x?token=secret#part"))
                .isEqualTo("http://a.example/x");
        assertThat(Grants.shown("http://me:secret@a.example/x")).isEqualTo("http://a.example/x");
        assertThat(Grants.shown("not an address")).isEqualTo("not an address");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                Grants.revocationUrl(
                                        Map.of(
                                                "revocation_url",
                                                "http://a.example/x?token=secret")))
                .hasMessageNotContaining("secret")
                .hasMessageContaining("http://a.example/x");
    }

    @Test
    void anAddressThatCannotBeReadIsNotSecure() {
        assertThat(Grants.secure("https://a.example/x?token=se cret")).isFalse();
    }
}
