package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the broker's records print of themselves, as a line must never carry what they hold. */
class SecretRecordTextTest {

    private static final String SECRET = "s3cret-value";
    private static final String URL = "https://idp.example/token";

    @Test
    void aGrantedTokenDoesNotPrintTheCredential() {
        assertThat(new TokenExchange.Granted(SECRET, "route").toString())
                .contains("route")
                .doesNotContain(SECRET);
    }

    @Test
    void aPurchasesClientDoesNotPrintItsSecret() {
        assertThat(new TokenPurchase.Client(URL, "id", SECRET, "s", null).toString())
                .contains(URL)
                .doesNotContain(SECRET);
    }

    @Test
    void aRefreshPrintsNeitherItsSecretNorItsToken() {
        assertThat(new TokenPurchase.Refresh(URL, "id", SECRET, SECRET, each -> {}).toString())
                .contains(URL)
                .doesNotContain(SECRET);
    }

    @Test
    void aDeviceGrantsStartDoesNotPrintTheDeviceCode() {
        assertThat(
                        new DeviceGrant.Started(
                                        SECRET,
                                        "ABCD",
                                        "https://idp.example/device",
                                        Duration.ofMinutes(5),
                                        Duration.ofSeconds(5))
                                .toString())
                .contains("https://idp.example/device")
                .doesNotContain(SECRET);
    }

    @Test
    void aDeviceGrantsOutcomePrintsNeitherToken() {
        assertThat(new DeviceGrant.Outcome("done", SECRET, SECRET, null).toString())
                .contains("done")
                .doesNotContain(SECRET);
    }

    @Test
    void aDeviceGrantsClientDoesNotPrintItsSecret() {
        assertThat(new DeviceGrant.Client(URL, URL, "id", SECRET, "s", null).toString())
                .contains(URL)
                .doesNotContain(SECRET);
    }

    @Test
    void aCodeGrantsClientDoesNotPrintItsSecret() {
        assertThat(new CodeGrant.Client(URL, URL, "id", SECRET, "s", 0, null).toString())
                .contains(URL)
                .doesNotContain(SECRET);
    }

    @Test
    void aGrantsServiceDoesNotPrintItsSecret() {
        assertThat(new Grants.Service(URL, "id", SECRET, null).toString())
                .contains(URL)
                .doesNotContain(SECRET);
    }

    @Test
    void aUrlsCredentialIsNotPrintedByAnyOfThem() {
        final String url = "https://user:" + SECRET + "@idp.example/token";

        assertThat(
                        List.of(
                                new TokenPurchase.Client(url, "id", "x", "s", null),
                                new TokenPurchase.Refresh(url, "id", "x", "x", each -> {}),
                                new DeviceGrant.Client(url, url, "id", "x", "s", null),
                                new CodeGrant.Client(url, url, "id", "x", "s", 0, null),
                                new Grants.Service(url, "id", "x", null)))
                .allSatisfy(
                        each ->
                                assertThat(each.toString())
                                        .contains("idp.example/token")
                                        .doesNotContain(SECRET));
    }

    @Test
    void aCodeGrantsStartDoesNotPrintItsLink() {
        final String link = "https://idp.example/authorize?state=" + SECRET;

        assertThat(new CodeGrant.Started(link, 8123, Duration.ofMinutes(5)).toString())
                .contains("8123")
                .doesNotContain(SECRET);
    }
}
