package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for room names that need space for a distinguishing bracket. */
class TransportLifecycleDisplayTest {

    @Test
    void aFiftyFiveCharacterTaskNameGetsAUniqueRoomNameOnAClash() {
        // Without a room name, a person's addressed message cannot reach this task.
        final String task = "a".repeat(55);
        final String held = "Agent A" + "a".repeat(54);

        final String shown =
                TransportLifecycle.display(
                        "p", "porelo-p-" + task, "scout", Map.of("porelo-p-other", held));

        assertThat(shown)
                .as("the longest task in project p gets a distinct name the room can hold")
                .isNotNull()
                .isNotEqualToIgnoringCase(held)
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void aSixtyFourCharacterRoomNameGetsAUniqueBracketOnAClash() {
        // A full room name must not leave a clashing task reachable only by its account.
        final String agent = "a".repeat(58);
        final String held = "Agent A" + "a".repeat(57);

        final String shown =
                TransportLifecycle.display("p", "custom-box", agent, Map.of("other-box", held));

        assertThat(shown)
                .as("a container named by its agent still gets a unique name at the room limit")
                .isNotNull()
                .isNotEqualToIgnoringCase(held)
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void aTaskWithTwoHyphensKeepsBothInItsRoomName() {
        // Dropping the empty word leaves addressed messages to this legal task with no recipient.
        assertThat(TransportLifecycle.display("p", "porelo-p-foo--bar", "scout", Map.of()))
                .as("the room preserves the task's two hyphens")
                .isEqualTo("Agent Foo--Bar");
    }

    @Test
    void twoLongTaskNamesSharingTheirFirstFiftyFourCharactersStayDistinct() {
        // Two tasks given the same shortened name would take each other's addressed messages.
        final String prefix = "a".repeat(54);
        final String first = "porelo-p-" + prefix + "b";
        final String second = "porelo-p-" + prefix + "c";
        final Map<String, String> held = new LinkedHashMap<>();
        held.put("porelo-p-other", "Agent A" + "a".repeat(53) + "b");
        held.put("porelo-p-another", "Agent A" + "a".repeat(53) + "c");

        final String firstShown = TransportLifecycle.display("p", first, "scout", held);
        assertThat(firstShown)
                .as("the first clashing task gets a bounded name no room member holds")
                .isNotNull()
                .isNotIn(held.values())
                .hasSizeLessThanOrEqualTo(64);
        held.put(first, firstShown);
        final String secondShown = TransportLifecycle.display("p", second, "scout", held);

        assertThat(secondShown)
                .as("the second task remains distinct even when shortening removes its last letter")
                .isNotNull()
                .isNotIn(held.values())
                .isNotEqualToIgnoringCase(firstShown)
                .hasSizeLessThanOrEqualTo(64);
    }
}
