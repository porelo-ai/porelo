package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/**
 * A start names the tasks whose landed work it reads, and only by their plain names; absent and
 * empty differ.
 */
class TaskReadsMethodsTest {

    @Test
    void aStartNamingNoTaskLeavesItAbsentAndAnEmptyListNamesNone() {
        assertThat(RunPlaces.asked(Map.of()).readLanded()).as("readLanded is optional").isNull();
        assertThat(RunPlaces.asked(Map.of("readLanded", List.of())).readLanded())
                .as("named as none")
                .isEmpty();
    }

    @Test
    void aStartNamingTasksReadsThem() {
        assertThat(RunPlaces.asked(Map.of("readLanded", List.of("foo-1", "bar"))).readLanded())
                .containsExactly("foo-1", "bar");
    }

    @Test
    void aNameThatIsNoTasksPlainNameIsRefusedBeforeAnythingStarts() {
        // A path or a ref pattern would unhide more than one task's work at the gate.
        for (final Object given :
                List.of(List.of("foo/../bar"), List.of(""), List.of(1), "foo-1")) {
            assertThatThrownBy(() -> RunPlaces.asked(Map.of("readLanded", given)))
                    .as("%s", given)
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            refused ->
                                    assertThat(refused.getParameters())
                                            .isEqualTo(Map.of("parameter", "readLanded")))
                    .hasMessageContaining("InvalidParameter");
        }
    }
}
