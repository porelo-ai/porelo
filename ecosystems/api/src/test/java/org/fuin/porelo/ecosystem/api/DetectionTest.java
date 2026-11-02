package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetectionTest {

    private static final Finding FINDING =
            new Finding(
                    Finding.Part.RULES,
                    "credential",
                    "repository/corp",
                    ".m2/settings.xml",
                    3,
                    Finding.Origin.YOURS,
                    false,
                    "a note");

    @Test
    void readsBackWhatItPutsOnTheWire() {
        final Detection detection =
                new Detection(List.of(FINDING), List.of(new Unplaced("pom.xml", 40, "a profile")));

        assertThat(Detection.of(detection.asMap())).isEqualTo(detection);
    }

    @Test
    void putsThePartsAndOriginsOnTheWireByTheirNamesInTheContract() {
        assertThat(FINDING.asMap()).containsEntry("part", "RULES").containsEntry("origin", "YOURS");
    }

    @Test
    void leavesAsideAFieldANewerContractAdded() {
        final Map<String, Object> carried =
                Map.of(
                        "findings",
                        List.of(
                                Map.of(
                                        "part", "RULES",
                                        "key", "credential",
                                        "value", "repository/corp",
                                        "file", ".m2/settings.xml",
                                        "line", 3L,
                                        "origin", "YOURS",
                                        "unknown", false,
                                        "note", "a note",
                                        "since", "a later version")),
                        "unplaced",
                        List.of());

        assertThat(Detection.of(carried).findings()).containsExactly(FINDING);
    }

    @Test
    void refusesWhatIsNoDetection() {
        assertThatThrownBy(() -> Detection.of(Map.of("unplaced", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("findings");
        assertThatThrownBy(
                        () ->
                                Detection.of(
                                        Map.of(
                                                "findings",
                                                List.of(wire("part", "NEITHER")),
                                                "unplaced",
                                                List.of())))
                .as("a part the contract does not name")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFindingNeedsAKeyAFileAndALineThatCanBeOne() {
        assertThatThrownBy(() -> finding("", "f", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finding("k", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finding("k", "f", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBuiltInDefaultIsAMachineValueFromNoFile() {
        final Finding builtIn =
                new Finding(
                        Finding.Part.TOOLS,
                        "java.version",
                        "25",
                        "",
                        0,
                        Finding.Origin.MACHINE,
                        false,
                        "");

        assertThat(builtIn.asMap())
                .containsEntry("origin", "MACHINE")
                .containsEntry("file", "")
                .containsEntry("line", 0);
        assertThat(Finding.of(builtIn.asMap())).isEqualTo(builtIn);
    }

    @Test
    void aMachineValueFromAFileNamesItRelativeToTheHome() {
        final Finding parent =
                new Finding(
                        Finding.Part.TOOLS,
                        "java.version",
                        "25",
                        ".m2/repository/o/p/1/p-1.pom",
                        7,
                        Finding.Origin.MACHINE,
                        false,
                        "");

        assertThat(Finding.of(parent.asMap())).isEqualTo(parent);
    }

    @Test
    void onlyAMachineValueMayHaveNoFileAndThenNoLine() {
        for (final Finding.Origin origin : List.of(Finding.Origin.YOURS, Finding.Origin.CHECKOUT)) {
            assertThatThrownBy(() -> Finding.of(wire("file", "", "origin", origin.name())))
                    .as("a plugin's answer from %s with no file", origin)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("needs a file");
        }
        assertThatThrownBy(
                        () ->
                                new Finding(
                                        Finding.Part.TOOLS,
                                        "k",
                                        "",
                                        "",
                                        3,
                                        Finding.Origin.MACHINE,
                                        false,
                                        ""))
                .as("a line of no file")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 3");
        assertThatThrownBy(
                        () ->
                                new Finding(
                                        Finding.Part.TOOLS,
                                        "k",
                                        "",
                                        " ",
                                        0,
                                        Finding.Origin.MACHINE,
                                        false,
                                        ""))
                .as("a blank name is not the absence of a file")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueThatIsUnknownHoldsNoValue() {
        assertThatThrownBy(
                        () ->
                                new Finding(
                                        Finding.Part.RULES,
                                        "k",
                                        "a value",
                                        "f",
                                        1,
                                        Finding.Origin.CHECKOUT,
                                        true,
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
        assertThat(
                        new Finding(
                                        Finding.Part.RULES,
                                        "k",
                                        "",
                                        "f",
                                        1,
                                        Finding.Origin.CHECKOUT,
                                        true,
                                        "")
                                .unknown())
                .isTrue();
    }

    @Test
    void refusesAFieldOfTheContractThatIsMissingOrOfAnotherType() {
        assertThat(Finding.of(wire())).as("the fixture itself is a finding").isNotNull();
        for (final String field :
                List.of("part", "key", "value", "file", "line", "origin", "unknown", "note")) {
            final Map<String, Object> missing = wire();
            missing.remove(field);
            assertThatThrownBy(() -> Finding.of(missing))
                    .as("without %s", field)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field);
        }
        assertThatThrownBy(() -> Finding.of(wire("unknown", "true")))
                .as("a string is not a bool")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.of(wire("value", 25L)))
                .as("a number is not a string")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.of(wire("line", 1.5d)))
                .as("a line is whole")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.of(wire("line", 1e12d)))
                .as("a line is an int")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Finding.of(wire("line", 14d)).line()).as("JSON's whole number").isEqualTo(14);
    }

    @Test
    void refusesAnUnplacedValueWithoutItsFields() {
        for (final String field : List.of("file", "line", "note")) {
            final Map<String, Object> carried = new HashMap<>();
            carried.put("file", "f");
            carried.put("line", 1L);
            carried.put("note", "n");
            carried.remove(field);
            assertThatThrownBy(() -> Unplaced.of(carried))
                    .as("without %s", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A whole finding on the wire, with the fields given set instead. */
    private static Finding finding(final String file, final Finding.Origin origin) {
        return new Finding(Finding.Part.TOOLS, "a-key", "a-value", file, 0, origin, false, "");
    }

    @Test
    void onlyAMachineValueNamesAFileByAnAbsolutePath() {
        assertThat(finding("/mnt/repository/parent.pom", Finding.Origin.MACHINE).file())
                .isEqualTo("/mnt/repository/parent.pom");

        assertThatThrownBy(() -> finding("/etc/a-file", Finding.Origin.CHECKOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> finding("/etc/a-file", Finding.Origin.YOURS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void aRelativeFileStaysInsideWhatItIsRelativeTo() {
        for (final Finding.Origin origin : Finding.Origin.values()) {
            assertThatThrownBy(() -> finding("../a-file", origin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("..");
            assertThatThrownBy(() -> finding("a/../../a-file", origin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("..");
        }
    }

    private static Map<String, Object> wire(final Object... instead) {
        final Map<String, Object> map = new HashMap<>();
        map.put("part", "TOOLS");
        map.put("key", "k");
        map.put("value", "v");
        map.put("file", "f");
        map.put("line", 1L);
        map.put("origin", "CHECKOUT");
        map.put("unknown", Boolean.FALSE);
        map.put("note", "");
        for (int i = 0; i < instead.length; i += 2) {
            map.put(String.valueOf(instead[i]), instead[i + 1]);
        }
        return map;
    }

    private static Finding finding(final String key, final String file, final int line) {
        return new Finding(
                Finding.Part.TOOLS, key, "", file, line, Finding.Origin.CHECKOUT, false, "");
    }
}
