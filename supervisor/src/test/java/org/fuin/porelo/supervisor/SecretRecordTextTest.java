package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
}
