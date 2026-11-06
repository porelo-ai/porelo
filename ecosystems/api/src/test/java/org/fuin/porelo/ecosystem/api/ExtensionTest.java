package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtensionTest {

    @Test
    void refusesABlockLargerThanTheContractCarries() {

        // A project file is read before a task starts: a block of any size would reach every
        // adapter's memory and the socket unbounded.
        final Map<String, Object> large = Map.of("k", "v".repeat(Extension.MOST_BYTES));

        assertThatThrownBy(() -> new Extension(large))
                .as("refused by its size, never echoing it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Extension.MOST_BYTES))
                .hasMessageNotContaining("vvvv");
    }

    @Test
    void refusesABlockNestedDeeperThanTheContractCarries() {
        Map<String, Object> deep = Map.of("leaf", "x");
        for (int level = 1; level < Extension.MOST_DEPTH; level++) {
            deep = Map.of("k", deep);
        }
        final Map<String, Object> fits = deep;
        assertThat(new Extension(fits).values()).as("exactly as deep as allowed").isEqualTo(fits);

        final Map<String, Object> deeper = Map.of("k", deep);
        assertThatThrownBy(() -> new Extension(deeper))
                .as("one level more")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Extension.MOST_DEPTH));
    }

    @Test
    void refusesWhatJsonCannotCarryOrABlankKey() {
        final Map<String, Object> withNull = new HashMap<>();
        withNull.put("k", null);

        assertThatThrownBy(() -> new Extension(withNull))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Extension(Map.of("k", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Extension(Map.of(" ", "secret-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-value");
        assertThatThrownBy(() -> new Extension(Map.of("k", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsItsOwnCopySoWhatWasSentCannotChangeAfterwards() {
        final List<Object> list = new ArrayList<>(List.of("a"));
        final Map<String, Object> map = new HashMap<>(Map.of("list", list));

        final Extension extension = new Extension(map);
        list.add("b");
        map.put("other", "c");

        assertThat(extension.values())
                .as("a later change to the caller's maps reaches no adapter")
                .isEqualTo(Map.of("list", List.of("a")));
        assertThatThrownBy(() -> extension.values().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void takesNoNumberThatCanChangeAfterItWasChecked() {

        // A number checked finite and small could become NaN or twenty digits long before the
        // block is written to the socket, past the very limits it was checked against.
        final java.util.concurrent.atomic.DoubleAdder adder =
                new java.util.concurrent.atomic.DoubleAdder();
        final java.util.concurrent.atomic.AtomicLong counter =
                new java.util.concurrent.atomic.AtomicLong(1);

        assertThatThrownBy(() -> new Extension(Map.of("k", adder)))
                .as("a number that changes is refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Extension(Map.of("k", counter)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesTruthValuesAndFiniteNumbersAndRefusesInfinity() {
        assertThat(new Extension(Map.of("flag", true, "count", 3, "ratio", 1.5)).values())
                .as("plain JSON values pass")
                .containsEntry("flag", true)
                .containsEntry("ratio", 1.5);
        assertThatThrownBy(() -> new Extension(Map.of("k", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Extension(Map.of("k", java.util.Arrays.asList((Object) null))))
                .as("null inside a list too")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsDepthThroughListsAndSizeInBytesNotCharacters() {
        Object deep = "x";
        for (int level = 1; level < Extension.MOST_DEPTH; level++) {
            deep = List.of(deep);
        }
        final Object eight = deep;
        assertThat(new Extension(Map.of("k", eight)).values())
                .as("a map holding lists seven deep is eight levels")
                .containsKey("k");
        assertThatThrownBy(() -> new Extension(Map.of("k", List.of(eight))))
                .as("lists count as levels as maps do")
                .isInstanceOf(IllegalArgumentException.class);

        // 8189 two-byte characters are fewer characters than the limit and more bytes.
        assertThatThrownBy(() -> new Extension(Map.of("k", "\u00e4".repeat(8189))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsNestedListsAndMapsUnchangeableToo() {
        final Extension extension =
                new Extension(Map.of("list", List.of("a"), "map", Map.of("k", "v")));

        assertThatThrownBy(() -> ((List<Object>) extension.values().get("list")).add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () -> ((Map<String, Object>) extension.values().get("map")).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
