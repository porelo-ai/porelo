package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunFollowerPartsTest {

    @Test
    void linesAreFormattedInPartsFarBelowAVarlinkMessagesLimit() {
        // Four megabytes is a Varlink message's limit; a part, escaped at worst six times, stays
        // under it.
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add("x".repeat(RunFollower.MOST_LINE));
        }

        final List<List<String>> parts = RunFollower.parts(lines);

        assertThat(parts.stream().mapToInt(List::size).sum()).isEqualTo(400);
        assertThat(parts)
                .allSatisfy(
                        part ->
                                assertThat(part.stream().mapToLong(String::length).sum() * 6)
                                        .isLessThan(4L * 1024 * 1024));
    }
}
