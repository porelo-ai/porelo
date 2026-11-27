package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A task started by another gets at most what that task holds, part by part. */
class TaskPartsExceedsTest {

    private static TaskParts parts(
            final TaskParts.Workspace workspace,
            final List<String> landed,
            final boolean services,
            final TaskParts.Account account,
            final Right... rights) {
        return new TaskParts(workspace, landed, services, account, Set.of(rights));
    }

    private static Optional<String> detail(final TaskParts child, final TaskParts parent) {
        return child.exceeds(parent)
                .map(
                        broken -> {
                            assertThat(broken.rule()).isEqualTo("exceedsParent");
                            return broken.detail();
                        });
    }

    @Test
    void aChildWithTheParentsOwnPartsOrFewerIsWithinThem() {
        final TaskParts lead =
                parts(
                        TaskParts.Workspace.OWN,
                        List.of("earlier"),
                        true,
                        TaskParts.Account.AUTHOR,
                        Right.PUSH,
                        Right.REPLY,
                        Right.START);

        assertThat(lead.exceeds(lead)).isEmpty();
        assertThat(TaskParts.ownWork().exceeds(lead)).isEmpty();
        assertThat(TaskParts.withoutGate(false, true).exceeds(lead))
                .as("one repository read only")
                .isEmpty();
        assertThat(TaskParts.withoutGate(false, false).exceeds(lead)).as("no workspace").isEmpty();
    }

    @Test
    void eachPartBeyondTheParentsIsNamed() {
        final TaskParts parent =
                parts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Right.REPLY);

        assertThat(detail(TaskParts.ownWork(), parent)).contains("rights push");
        assertThat(
                        detail(
                                parts(
                                        TaskParts.Workspace.OWN,
                                        List.of("other"),
                                        true,
                                        TaskParts.Account.AUTHOR),
                                parent))
                .contains("readLanded other");
        assertThat(
                        detail(
                                parts(
                                        TaskParts.Workspace.OWN,
                                        List.of(),
                                        true,
                                        TaskParts.Account.REVIEW),
                                parent))
                .contains("writeAs review");
        assertThat(
                        detail(
                                TaskParts.withoutGate(false, false),
                                parts(
                                        TaskParts.Workspace.NONE,
                                        List.of(),
                                        false,
                                        TaskParts.Account.AUTHOR)))
                .contains("projectServices");
    }

    @Test
    void aWorkspaceBeyondTheParentsIsNamed() {
        final TaskParts reading = TaskParts.anothersWork(List.of(Right.START));

        assertThat(detail(TaskParts.ownWork(), reading))
                .as("own work under a task that reads another's")
                .contains("workspace own");
        assertThat(detail(TaskParts.anothersWork(List.of()), TaskParts.ownWork()))
                .as("another task's work under one at its own")
                .contains("workspace foreign");
        assertThat(detail(TaskParts.withoutGate(true, false), TaskParts.ownWork()))
                .as("every repository under one at its own")
                .contains("workspace allRepositories");
        assertThat(detail(TaskParts.withoutGate(false, true), TaskParts.withoutGate(true, false)))
                .as("one repository under one that reads them all")
                .isEmpty();
        assertThat(detail(TaskParts.withoutGate(true, false), TaskParts.withoutGate(false, true)))
                .contains("workspace allRepositories");
    }
}
