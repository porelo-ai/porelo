package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the filter's ID alphabet, bound and distinct person messages. */
class PersonsWordsTest {
    @Test
    void aWordNamingOnlyHelpersWakesNoTask(@TempDir Path dir) throws IOException {
        // A word to a helper must not wake every agent to answer a question meant for nobody.
        final List<TransportConversations.Member> members = members(dir);
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file,
                "{\"sender\":\"@alice\",\"body\":\"Who are you?\",\"to\":[],\"helpers\":true}");

        final PersonsWords words =
                PersonsWords.read(file, members, Map.of(), Map.of("alice", "@alice"));

        assertThat(words.readers()).as("a helper mention wakes no task").isEmpty();
        assertThat(words.message()).as("no agent message is made for a helper mention").isNull();
        assertThat(words.answerTo())
                .as("the answer reaches the sender's account")
                .isEqualTo("@alice");
    }

    @Test
    void aDeveloperLetInByTheRoomsMakerIsTakenByTheirHostAccount(@TempDir Path dir)
            throws IOException {
        // Kept in the room by members alone, never through join: refused, they could not be heard.
        final List<TransportConversations.Member> members = members(dir);
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@bob:hs\",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(
                        file, members, Map.of(), Map.of("alice", "@alice"), Set.of("bob"));

        assertThat(words.refused()).isNull();
        assertThat(words.person()).isEqualTo("bob");
        assertThat(words.learned()).as("kept to answer him").isEqualTo("@bob:hs");
        assertThat(words.readers()).containsExactlyElementsOf(members);
    }

    @Test
    void anAccountTheAccessFileDoesNotNameIsNobody(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@eve:hs\",\"account\":\"eve\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(file, members(dir), Map.of(), Map.of(), Set.of("bob"));

        assertThat(words.refused()).contains("@eve:hs");
        assertThat(words.person()).isNull();
    }

    @Test
    void aDeveloperWhoJoinedUnderAnotherAccountIsNotTakenOver(@TempDir Path dir)
            throws IOException {
        // Somebody else's room account claiming bob must not speak as him.
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file,
                "{\"sender\":\"@mallory:hs\",\"account\":\"bob\",\"body\":\"Hi\"," + "\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(
                        file, members(dir), Map.of(), Map.of("bob", "@bob:hs"), Set.of("bob"));

        assertThat(words.refused()).contains("@mallory:hs");
    }

    @Test
    void aTasksAccountIsNeverTakenForADeveloper(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file,
                "{\"sender\":\"@porelo-p-build:hs\",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(
                        file,
                        members(dir),
                        Map.of("porelo-p-build", "@porelo-p-build:hs"),
                        Map.of(),
                        Set.of("bob"));

        assertThat(words.refused()).contains("@porelo-p-build:hs");
        assertThat(words.learned()).isNull();
    }

    @Test
    void aBlankSenderIsNeverTakenForADeveloper(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(file, "{\"sender\":\" \",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(file, members(dir), Map.of(), Map.of(), Set.of("bob"));

        assertThat(words.refused()).isNotNull();
        assertThat(words.learned()).isNull();
    }

    @Test
    void anAccountIsNothingWithoutTheAccessFile(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@bob:hs\",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        assertThat(PersonsWords.read(file, members(dir), Map.of(), Map.of()).refused())
                .as("the transport's word alone lets nobody in")
                .isNotNull();
    }

    private static List<TransportConversations.Member> members(Path dir) {
        return List.of(
                new TransportConversations.Member(
                        "porelo-p-review", new Mailbox(dir.resolve("review")), "p", "room"),
                new TransportConversations.Member(
                        "porelo-p-build", new Mailbox(dir.resolve("build")), "p", "room"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"helpers\":false", ",\"helpers\":\"true\""})
    void aWordWithoutTheBooleanHelperMarkerStillReachesEveryTask(String helpers, @TempDir Path dir)
            throws IOException {
        // Treating an absent or nonboolean marker as helpers would lose a person's room message.
        final List<TransportConversations.Member> members = members(dir);
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@alice\",\"body\":\"Hello\",\"to\":[]" + helpers + "}");

        final PersonsWords words =
                PersonsWords.read(file, members, Map.of(), Map.of("alice", "@alice"));

        assertThat(words.readers())
                .as("ordinary room words reach every task")
                .containsExactlyElementsOf(members);
        assertThat(words.message())
                .as("ordinary room words still become an agent message")
                .isNotNull();
    }

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
