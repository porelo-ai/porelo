package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/** A start names the tasks whose landed work it reads, and only by their plain names. */
class TaskReadsMethodsTest {

    @Test
    void aStartNamingNoTaskReadsNothing() {
        assertThat(TaskMethods.reads(Map.of())).as("reads is optional").isEmpty();
    }

    @Test
    void aStartNamingTasksReadsThem() {
        assertThat(TaskMethods.reads(Map.of("reads", List.of("foo-1", "bar"))))
                .containsExactly("foo-1", "bar");
    }

    @Test
    void aNameThatIsNoTasksPlainNameIsRefusedBeforeAnythingStarts() {
        // A path or a ref pattern would unhide more than one task's work at the gate.
        for (final Object given :
                List.of(List.of("foo/../bar"), List.of(""), List.of(1), "foo-1")) {
            assertThatThrownBy(() -> TaskMethods.reads(Map.of("reads", given)))
                    .as("%s", given)
                    .isInstanceOf(VarlinkException.class)
                    .hasMessageContaining("InvalidParameter");
        }
    }
}
