package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The server a homeserver's address lends for. Harm guarded: a person's own homeserver on this
 * machine that cannot be named for a project at all; a token stored for it that reaches a
 * homeserver elsewhere over plain http, one of the same host over https, or another port of the
 * same host; and a token lent to a host's gate, which registers without one.
 */
class LentEntryTest {

    @ParameterizedTest
    @CsvSource({
        "http://127.0.0.1:8008, t.http:127.0.0.1:8008",
        "http://127.0.0.1:8008/, t.http:127.0.0.1:8008",
        "http://127.0.0.1:8008/base, t.http:127.0.0.1:8008",
        "HTTP://127.0.0.1:8008, t.http:127.0.0.1:8008",
        "http://127.0.0.1:08008, t.http:127.0.0.1:8008",
        "http://127.42.0.255:6167, t.http:127.42.0.255:6167",
        "http://localhost:8008, t.http:localhost:8008",
        "http://LocalHost:8008/_matrix, t.http:localhost:8008",
        "http://[::1]:8008, t.http:[::1]:8008",
        "http://localhost, t.http:localhost:80",
        "http://127.0.0.1/, t.http:127.0.0.1:80",
        "http://127.0.0.1:/, t.http:127.0.0.1:80",
    })
    void aLoopbackHttpAddressLendsForItsHostAndPort(final String address, final String entry) {
        assertThat(LentEntry.name("t", address)).as(address).isEqualTo(entry);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://matrix.example.org",
                "http://matrix.example.org:8008",
                "http://128.0.0.1:8008",
                "http://10.0.0.1:8008",
                "http://127.0.0.1.example.org:8008",
                "http://localhost.example.org:8008",
                "http://x.localhost:8008",
                "http://[::2]:8008",
                "http://[0:0:0:0:0:0:0:1]:8008",
                "http://[::1%25lo]:8008",
                "http://[::ffff:127.0.0.1]:8008",
                "http://127.1:8008",
                "http://0x7f.0.0.1:8008",
                "http://0177.0.0.1:8008",
                "http://127.0.0.01:8008",
                "http://127.0.0.256:8008",
                "http://2130706433:8008",
                "http://127.0.0.1:0",
                "http://127.0.0.1:65536",
                "http://127.0.0.1:123456",
                "http://user@127.0.0.1:8008",
                "http://evil.example@127.0.0.1:8008",
                "http://127.0.0.1:8008@evil.example",
                "http://127.0.0.1:8008?@evil.example",
                "http://127.0.0.1:8008#@evil.example",
                "http://127.0.0.1:8008\\@evil.example",
                "http://evil.example\\@127.0.0.1:8008",
                "http:// 127.0.0.1:8008",
                "http://127.0.0.1:8008 ",
                "http:127.0.0.1:8008",
                "ws://127.0.0.1:8008",
            })
    void anyOtherHttpAddressStillLendsNothing(final String address) {
        assertThat(LentEntry.name("t", address)).as(address).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unix:/run/porelo/room.sock",
                "unix:/run/user/1000/porelo-matrix/client.sock",
                "https://gate.host.localhost",
                "https://Gate.HOST.LocalHost:8448/_matrix",
                "https://localhost.localhost",
                "https://gate.host.localhost.",
                "https://gate.host.localhost.:8448",
            })
    void aHostsGateLendsNothing(final String address) {
        // The transport registers there without a token and drops one it is lent: named, the
        // person stored a token for a listener that proves nothing about who it is.
        assertThat(LentEntry.name("t", address)).as(address).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "https://localhost:8448, t.localhost",
        "https://localhost.example.org, t.localhost.example.org",
        "https://localhost-gate.example.org, t.localhost-gate.example.org",
        "https://example.localhost.org, t.example.localhost.org",
    })
    void anHttpsServerThatIsNoHostsGateStillLendsForItsHost(
            final String address, final String entry) {
        assertThat(LentEntry.name("t", address)).as(address).isEqualTo(entry);
    }

    @Test
    void aLoopbackHttpNameCannotBeMistakenForAnHttpsOneOfTheSameHost() {
        assertThat(LentEntry.name("t", "https://127.0.0.1:8008"))
                .as("https names the host alone")
                .isEqualTo("t.127.0.0.1");
        assertThat(LentEntry.name("t", "http://127.0.0.1:8008"))
                .isNotEqualTo(LentEntry.name("t", "https://127.0.0.1:8008"))
                .isNotEqualTo(LentEntry.name("t", "https://127.0.0.1"));
        assertThat(LentEntry.name("t", "http://localhost:8008"))
                .isNotEqualTo(LentEntry.name("t", "https://localhost:8008"));
        assertThat(LentEntry.name("t", "http://[::1]:8008"))
                .isNotEqualTo(LentEntry.name("t", "https://[::1]:8008"));
    }

    @Test
    void twoPortsOfOneLoopbackHostAreTwoServers() {
        assertThat(LentEntry.name("t", "http://127.0.0.1:8008"))
                .isNotEqualTo(LentEntry.name("t", "http://127.0.0.1:8009"));
        assertThat(LentEntry.name("t", "http://127.0.0.1:8008"))
                .as("another loopback address is another listener")
                .isNotEqualTo(LentEntry.name("t", "http://127.0.0.2:8008"))
                .isNotEqualTo(LentEntry.name("t", "http://localhost:8008"));
    }
}
