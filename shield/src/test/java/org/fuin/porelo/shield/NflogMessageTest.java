package org.fuin.porelo.shield;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NflogMessage}.
 *
 * <p>The fixture is not synthetic. It is the raw output of a Linux kernel, captured from NFLOG
 * group 1 inside a real container network namespace while an agent's connections were being dropped
 * by the generated ruleset. A fixture written by hand would only prove that the parser agrees with
 * whoever wrote it. Its prefix attribute was rewritten when the product was renamed, with the
 * length and padding the kernel gives it; every other byte is as the kernel sent it.
 */
class NflogMessageTest {

    private List<byte[]> captured() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/nflog/captured.hex")) {
            assertThat(in).as("fixture /nflog/captured.hex").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> HexFormat.of().parseHex(line.strip()))
                    .toList();
        }
    }

    private List<BlockedConnection> parseAll() throws IOException {
        return captured().stream()
                .flatMap(datagram -> NflogMessage.parse(datagram, Instant.EPOCH).stream())
                .toList();
    }

    @Test
    void readsTheDestinationsTheKernelDropped() throws IOException {

        assertThat(parseAll())
                .extracting(BlockedConnection::describe)
                .contains("1.1.1.1:443", "9.9.9.9:80");
    }

    @Test
    void readsTheLogPrefixTheRuleSet() throws IOException {

        // The prefix is how an event is attributed to a rule, and it arrives NUL-padded.
        assertThat(parseAll())
                .isNotEmpty()
                .allSatisfy(
                        event ->
                                assertThat(event.prefix()).isEqualTo(NftRuleset.DROP_PREFIX + " "));
    }

    @Test
    void readsSeveralMessagesFromOneDatagram() throws IOException {

        // The kernel batches events. A parser that stopped after the first would silently lose
        // most of them, and nothing downstream would notice.
        assertThat(
                        captured().stream()
                                .map(datagram -> NflogMessage.parse(datagram, Instant.EPOCH).size())
                                .max(Integer::compareTo))
                .contains(3);
    }

    @Test
    void readsBothAddressFamilies() throws IOException {

        final List<String> destinations =
                parseAll().stream().map(BlockedConnection::destination).toList();

        assertThat(destinations).anyMatch(address -> address.contains("."));
        assertThat(destinations).anyMatch(address -> address.contains(":"));
    }

    @Test
    void readsProtocolAndPortForTcp() throws IOException {

        assertThat(parseAll())
                .filteredOn(event -> "1.1.1.1".equals(event.destination()))
                .isNotEmpty()
                .allSatisfy(
                        event -> {
                            assertThat(event.protocol()).isEqualTo(BlockedConnection.TCP);
                            assertThat(event.port()).isEqualTo(443);
                        });
    }

    @Test
    void groupsRepeatsOfTheSameDestinationUnderOneKey() throws IOException {

        // An agent retrying produces one event per attempt. Prompting for each would make the
        // machine unusable, so the key is what the hub deduplicates on.
        assertThat(
                        parseAll().stream()
                                .filter(event -> "9.9.9.9".equals(event.destination()))
                                .map(BlockedConnection::key)
                                .distinct()
                                .toList())
                .containsExactly("tcp/9.9.9.9/80");
    }

    @Test
    void ignoresRatherThanGuessesAtSomethingItCannotRead() {

        assertThat(NflogMessage.parse(new byte[0], Instant.EPOCH)).isEmpty();
        assertThat(NflogMessage.parse(new byte[] {1, 2, 3}, Instant.EPOCH)).isEmpty();
        // A header claiming more bytes than arrived must not be believed.
        assertThat(
                        NflogMessage.parse(
                                HexFormat.of().parseHex("ff0000000004000000000000000000000a000001"),
                                Instant.EPOCH))
                .isEmpty();
    }

    @Test
    void keepsTheAgentsConnectionsAndDropsTheNamespaceNoise() throws IOException {

        // A container namespace produces router solicitations and multicast listener reports that
        // the default-deny chain drops too. An operator who learns to dismiss prompts is worse
        // than no prompts at all.
        final List<BlockedConnection> worth =
                parseAll().stream().filter(BlockedConnection::worthAsking).toList();

        assertThat(worth).isNotEmpty();
        assertThat(worth)
                .extracting(BlockedConnection::describe)
                .containsOnly("1.1.1.1:443", "9.9.9.9:80");
        assertThat(parseAll()).hasSizeGreaterThan(worth.size());
    }

    @Test
    void classifiesTheAddressesThatShouldNeverRaiseAPrompt() {

        assertThat(ask("224.0.0.251", BlockedConnection.UDP)).as("mDNS multicast").isFalse();
        assertThat(ask("255.255.255.255", BlockedConnection.UDP)).as("broadcast").isFalse();
        assertThat(ask("169.254.1.1", BlockedConnection.TCP)).as("link-local").isFalse();
        assertThat(ask("ff02:0:0:0:0:0:0:2", BlockedConnection.UDP)).as("IPv6 multicast").isFalse();
        assertThat(ask("fe80:0:0:0:0:0:0:1", BlockedConnection.TCP))
                .as("IPv6 link-local")
                .isFalse();

        assertThat(ask("1.1.1.1", BlockedConnection.TCP)).as("a real destination").isTrue();
        assertThat(ask("2606:4700:4700:0:0:0:0:1111", BlockedConnection.TCP))
                .as("a real IPv6 destination")
                .isTrue();
        assertThat(ask("1.1.1.1", 58)).as("ICMPv6 is not a connection").isFalse();
    }

    private boolean ask(String destination, int protocol) {
        return new BlockedConnection("p", protocol, destination, 443, Instant.EPOCH).worthAsking();
    }

    @Test
    void rendersAnEventAsTheLineProtocol() {

        final BlockedConnection event =
                new BlockedConnection(
                        "porelo-drop ", BlockedConnection.TCP, "1.1.1.1", 443, Instant.EPOCH);

        assertThat(event.toJson())
                .contains("\"protocol\":\"tcp\"")
                .contains("\"destination\":\"1.1.1.1\"")
                .contains("\"port\":443");
    }

    @Test
    void readsTheSourcePortThatTellsARetryFromANewAttempt() throws IOException {

        assertThat(parseAll())
                .filteredOn(event -> event.protocol() == BlockedConnection.TCP)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.sourcePort()).isPositive());
    }

    @Test
    void stampsAPacketWithTheKernelsTimeWhereTheLogCarriesOne() throws IOException {

        // The kernel batches logged packets, and the time a batch was read made a SYN and its retry
        // two drops at one
        // instant (found on 2026-10-13). One captured message, with the kernel's timestamp
        // attribute added.
        final byte[] first = captured().get(0);
        final java.nio.ByteBuffer original =
                java.nio.ByteBuffer.wrap(first).order(java.nio.ByteOrder.nativeOrder());
        final int length = original.getInt(0);
        final java.nio.ByteBuffer stamped =
                java.nio.ByteBuffer.allocate(length + 20).order(java.nio.ByteOrder.nativeOrder());
        stamped.put(first, 0, length);
        stamped.putShort((short) 20).putShort((short) 3);
        stamped.order(java.nio.ByteOrder.BIG_ENDIAN).putLong(1_791_905_487L).putLong(250_000L);
        stamped.order(java.nio.ByteOrder.nativeOrder()).putInt(0, length + 20);

        assertThat(NflogMessage.parse(stamped.array(), Instant.EPOCH))
                .singleElement()
                .extracting(BlockedConnection::at)
                .isEqualTo(Instant.ofEpochSecond(1_791_905_487L, 250_000_000L));
        assertThat(NflogMessage.parse(first, Instant.EPOCH))
                .first()
                .extracting(BlockedConnection::at)
                .as("no timestamp in the log: the time it was read")
                .isEqualTo(Instant.EPOCH);
    }
}
