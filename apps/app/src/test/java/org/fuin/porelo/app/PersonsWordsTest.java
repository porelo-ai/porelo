package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the filter's ID alphabet, bound and distinct person messages. */
class PersonsWordsTest {
    private String id(Path file, String event) throws IOException {
        Files.writeString(
                file, Json.write(Map.of("sender", "@alice", "body", "Hello", "eventId", event)));
        final PersonsWords words =
                PersonsWords.read(file, List.of(), Map.of(), Map.of("alice", "@alice"));
        final Map<?, ?> message =
                (Map<?, ?>) Json.parse(new String(words.message(), StandardCharsets.UTF_8));
        return (String) message.get("messageId");
    }

    @Test
    void aV4EventKeepsItsReadableForm(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklm-12_"))
                .as("base64url events stay readable")
                .isEqualTo("person-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklm-12_");
    }

    @Test
    void differentV3EventsStayDifferent(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$" + "a".repeat(42) + "+"))
                .as("plus and slash cannot discard another message as a duplicate")
                .isNotEqualTo(id(dir.resolve("person.json"), "$" + "a".repeat(42) + "/"));
    }

    @Test
    void aDollarIsPartOfTheOriginalIdentity(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$foo"))
                .as("unprefixed values do not alias Matrix events")
                .isNotEqualTo(id(dir.resolve("person.json"), "foo"));
    }

    @Test
    void anOversizedEventFitsTheFilter(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$" + "a".repeat(200)))
                .as("bounded filter-safe ID")
                .matches("person-h-[0-9a-f]{64}")
                .hasSizeLessThanOrEqualTo(128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "$"})
    void anEmptyEventUsesTheFileName(String event, @TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("arrival.json"), event))
                .as("empty event uses the same mapping as its filename")
                .isEqualTo(id(dir.resolve("other.json"), "arrival.json"));
        assertThat(id(dir.resolve("arrival.json"), event))
                .as("different arrivals have different fallback IDs")
                .isNotEqualTo(id(dir.resolve("other.json"), event));
    }

    @Test
    void aHashPrefixCannotAliasAReadableEvent(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$h-reserved"))
                .as("hash prefix is reserved")
                .matches("person-h-[0-9a-f]{64}");
    }

    @Test
    void unsafeEventsHashTheFullOriginalValue(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$a/b"))
                .as("SHA-256 includes the original dollar")
                .isEqualTo(
                        "person-h-214fdb214905eb080e5c0a19410c9d067946ca7b09b16725c0fb1587590fd5b7");
    }
}
