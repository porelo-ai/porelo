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

    // The transport marks a line headed "All agents:" so; it reaches every task, marked for each.
    @Test
    void aLineToAllAgentsIsMarkedSoForEveryTaskAndOnlyInTheRoom(@TempDir Path dir)
            throws IOException {
        final List<TransportConversations.Member> members = members(dir);
        final Path room = dir.resolve("room.json");
        Files.writeString(
                room,
                "{\"sender\":\"@alice\",\"account\":\"alice\",\"body\":\"All agents: stop\",\"to\":[],"
                        + "\"all\":true}");
        final Path direct = dir.resolve("direct.json");
        Files.writeString(
                direct,
                "{\"sender\":\"@alice\",\"account\":\"alice\",\"body\":\"All agents: stop\",\"to\":[],"
                        + "\"all\":true,\"direct\":true}");

        final PersonsWords inRoom =
                PersonsWords.read(room, members, Map.of(), Map.of(), Set.of("alice"));
        final PersonsWords inDirect =
                PersonsWords.read(direct, members, Map.of(), Map.of(), Set.of("alice"));

        assertThat(inRoom.readers()).containsExactlyElementsOf(members);
        assertThat(new String(inRoom.message(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"all\":true");
        assertThat(new String(inDirect.message(), java.nio.charset.StandardCharsets.UTF_8))
                .as("a direct chat is with one task")
                .doesNotContain("\"all\"");
    }

    // Waking every task costs each agent a turn: only a person the project admitted, named by the
    // host account the transport gives, may ask it of all of them.
    @Test
    void aLineToAllAgentsIsForAllOnlyFromAPersonTheProjectAdmitted(@TempDir Path dir)
            throws IOException {
        final List<TransportConversations.Member> members = members(dir);
        final Path noAccount = dir.resolve("no-account.json");
        Files.writeString(
                noAccount,
                "{\"sender\":\"@alice\",\"body\":\"All agents: stop\",\"to\":[],\"all\":true}");
        final Path notAdmitted = dir.resolve("not-admitted.json");
        Files.writeString(
                notAdmitted,
                "{\"sender\":\"@carol\",\"account\":\"carol\",\"body\":\"All agents: stop\","
                        + "\"to\":[],\"all\":true}");

        final PersonsWords joined =
                PersonsWords.read(
                        noAccount,
                        members,
                        Map.of(),
                        Map.of("alice", "@alice", "carol", "@carol"),
                        Set.of("alice"));
        final PersonsWords joinedOnly =
                PersonsWords.read(
                        notAdmitted,
                        members,
                        Map.of(),
                        Map.of("alice", "@alice", "carol", "@carol"),
                        Set.of("alice"));

        assertThat(new String(joined.message(), java.nio.charset.StandardCharsets.UTF_8))
                .as("no host account named")
                .doesNotContain("\"all\"");
        assertThat(new String(joinedOnly.message(), java.nio.charset.StandardCharsets.UTF_8))
                .as("joined, but not admitted by the project")
                .doesNotContain("\"all\"");
    }

    @Test
    void aPersonLetInByTheRoomsMakerIsTakenByTheirHostAccount(@TempDir Path dir)
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
    void anAccountTheMembersDoNotNameIsNobody(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@eve:hs\",\"account\":\"eve\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(file, members(dir), Map.of(), Map.of(), Set.of("bob"));

        assertThat(words.refused()).contains("@eve:hs");
        assertThat(words.person()).isNull();
    }

    @Test
    void aPersonWhoJoinedUnderAnotherAccountIsNotTakenOver(@TempDir Path dir) throws IOException {
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
    void aTasksAccountIsNeverTakenForAPerson(@TempDir Path dir) throws IOException {
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
    void aBlankSenderIsNeverTakenForAPerson(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(file, "{\"sender\":\" \",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        final PersonsWords words =
                PersonsWords.read(file, members(dir), Map.of(), Map.of(), Set.of("bob"));

        assertThat(words.refused()).isNotNull();
        assertThat(words.learned()).isNull();
    }

    @Test
    void anAccountIsNothingWithoutTheMembers(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("person.json");
        Files.writeString(
                file, "{\"sender\":\"@bob:hs\",\"account\":\"bob\",\"body\":\"Hi\",\"to\":[]}");

        assertThat(PersonsWords.read(file, members(dir), Map.of(), Map.of()).refused())
                .as("the transport's word alone lets nobody in")
                .isNotNull();
    }

    private static List<TransportConversations.Member> members(Path dir) {
        return List.of(
                member("porelo-p-review", new Mailbox(dir.resolve("review")), "p", "room"),
                member("porelo-p-build", new Mailbox(dir.resolve("build")), "p", "room"));
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

    /** A canonical version-8 UUID, the lone shape the outbound filter masks as harmless. */
    private static final String UUID_V8 =
            "[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklm-12_",
                "$h-reserved",
                "$a/b",
                "foo",
                "$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void anEventsIdIsAVersion8Uuid(String event, @TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), event))
                .as("every event takes the same path to a canonical UUID")
                .matches("person-" + UUID_V8);
    }

    @Test
    void anEventReadTwiceKeepsItsId(@TempDir Path dir) throws IOException {
        final String event = "$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklm-12_";
        assertThat(id(dir.resolve("first.json"), event))
                .as("a line read on a later poll is still one message")
                .isEqualTo(id(dir.resolve("second.json"), event));
    }

    @Test
    void anEventsIdIsFixedByItsBytes(@TempDir Path dir) throws IOException {
        assertThat(id(dir.resolve("person.json"), "$a/b"))
                .as("SHA-256 of the fixed prefix and the event, cut to a version-8 UUID")
                .isEqualTo("person-" + uuidOf("porelo-person-message:$a/b"));
    }

    @Test
    void anIdLeavesNoRunTheFilterRefusesOnceItsUuidIsMasked(@TempDir Path dir) throws IOException {
        final String answered =
                "Re " + id(dir.resolve("person.json"), "$" + "a".repeat(200)) + ": done";
        assertThat(answered.replaceAll(UUID_V8, "UUID"))
                .as("once the lone UUID is masked, no run of hex or base64 is left")
                .doesNotContainPattern("[0-9A-Fa-f]{32,}")
                .doesNotContainPattern("[A-Za-z0-9+/_=-]{32,}");
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

    /** The expected UUID, computed apart from the code under test. */
    private static String uuidOf(String name) {
        final byte[] hash;
        try {
            hash =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        final String hex = java.util.HexFormat.of().formatHex(hash, 0, 16);
        final char version = '8';
        final char variant = "89ab".charAt(Character.digit(hex.charAt(16), 16) & 0x3);
        return hex.substring(0, 8)
                + "-"
                + hex.substring(8, 12)
                + "-"
                + version
                + hex.substring(13, 16)
                + "-"
                + variant
                + hex.substring(17, 20)
                + "-"
                + hex.substring(20, 32);
    }

    private static TransportConversations.Member member(
            String container, Mailbox mailbox, String project, String scheme) {
        return member(container, mailbox, project, scheme, null);
    }

    private static TransportConversations.Member member(
            String container,
            Mailbox mailbox,
            String project,
            String scheme,
            @org.jspecify.annotations.Nullable String display) {
        return new TransportConversations.Member(
                container,
                mailbox,
                project,
                scheme,
                display,
                org.fuin.porelo.runtime.ContainerName.taskIn(project, container));
    }
}
