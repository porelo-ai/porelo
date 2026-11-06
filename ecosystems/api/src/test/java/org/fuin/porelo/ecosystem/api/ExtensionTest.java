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
        assertThatThrownBy(() -> new Extension(Map.of(" ", "v")))
                .isInstanceOf(IllegalArgumentException.class);
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
}
