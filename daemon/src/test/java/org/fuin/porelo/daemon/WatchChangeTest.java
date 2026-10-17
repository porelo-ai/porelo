package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.AgentWaiting;
import org.fuin.porelo.app.HandIns;
import org.fuin.porelo.app.TaskBuilds;
import org.fuin.porelo.app.TaskInventory;
import org.junit.jupiter.api.Test;

class WatchChangeTest {

    private static TaskInventory.Task task(
            final String state, final HandIns.Summary handIns, final TaskBuilds.View builds) {
        return new TaskInventory.Task(
                "porelo-uc-shell-2",
                "uc",
                "online",
                state,
                true,
                1,
                "example",
                "AGENT",
                null,
                "refs/heads/shell",
                "2026-10-06T12:00:00Z",
                TaskInventory.Activity.WORKING,
                null,
                "prompt",
                null,
                0,
                "RUNNING",
                "",
                "uc",
                null,
                AgentWaiting.Derived.NOTHING,
                Map.of(),
                Map.of(),
                null,
                null,
                handIns,
                builds);
    }

    private static final TaskBuilds.Seen RUNNING =
            new TaskBuilds.Seen(
                    "c".repeat(40),
                    "running",
                    List.of(),
                    "2026-10-06T12:00:00Z",
                    "",
                    "0123456789ab");

    @Test
    void theAgeOfTheStateIsNoChange() {
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        TaskBuilds.View.none())))
                .isEqualTo(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 4 seconds",
                                        HandIns.Summary.none(),
                                        TaskBuilds.View.none())));
    }

    @Test
    void everythingAClientIsSentIsAChangeTheBuildsAndTheHandedFilesAmongIt() {
        final String before =
                PoreloDaemon.withoutAge(
                        task(
                                "Up 3 seconds",
                                HandIns.Summary.none(),
                                new TaskBuilds.View("stub-forge", "", List.of())));

        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        new TaskBuilds.View("stub-forge", "", List.of(RUNNING)))))
                .as("a push's build")
                .isNotEqualTo(before);
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        new TaskBuilds.View(
                                                "stub-forge", "not installed", List.of()))))
                .as("a reader's problem")
                .isNotEqualTo(before);
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        new HandIns.Summary(
                                                "0123456789ab",
                                                64,
                                                List.of(
                                                        new HandIns.HandedFile(
                                                                "spec.pdf",
                                                                3,
                                                                "a".repeat(64),
                                                                "2026-10-06T12:00:00Z",
                                                                "core",
                                                                "0123456789ab"))),
                                        new TaskBuilds.View("stub-forge", "", List.of()))))
                .as("a handed-in file")
                .isNotEqualTo(before);
    }
}
