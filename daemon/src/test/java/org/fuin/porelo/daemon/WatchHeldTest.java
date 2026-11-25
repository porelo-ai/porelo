package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Watch sends a task's row again when the number of its held items changes. */
class WatchHeldTest {

    @Test
    void aChangeOfTheHeldCountIsAChangeWatchSends() {
        final Map<String, Object> before = row(0);

        assertThat(PoreloDaemon.withoutAge(row(1)))
                .as("the row with one item held, against none")
                .isNotEqualTo(PoreloDaemon.withoutAge(before));
        assertThat(PoreloDaemon.withoutAge(row(0)))
                .as("the same row again")
                .isEqualTo(PoreloDaemon.withoutAge(before));
    }

    private static Map<String, Object> row(final int held) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "porelo-demo-fix");
        row.put("state", "Up 4 minutes");
        row.put("held", held);
        return row;
    }
}
