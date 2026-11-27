package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What a start names of its parts, named and absent kept apart, and the parts that follow. */
class TaskPartsAskedTest {

    @Test
    void aStartNamingNothingAtItsOwnWorkGetsTheDefaultSet() {
        final TaskParts parts = TaskParts.Asked.nothing().atOwnWork();

        assertThat(parts).as("the default parts").isEqualTo(TaskParts.ownWork());
        assertThat(TaskParts.Asked.nothing().namesAny()).as("nothing named").isFalse();
    }

    @Test
    void anEmptyListOfRightsHoldsNoRightAndNeverTheDefaultSet() {
        final TaskParts parts = asked(Set.of(), null, null).atOwnWork();

        assertThat(parts.holds(Right.PUSH)).as("no push").isFalse();
        assertThat(parts.holds(Right.REPLY)).as("no reply").isFalse();
        assertThat(parts.document().get("rights")).as("written out as none").isEqualTo(List.of());
    }

    @Test
    void namedRightsReplaceTheDefaultSet() {
        final TaskParts parts =
                asked(Set.of(Right.REPLY, Right.OPEN_THREADS), null, null).atOwnWork();

        assertThat(parts.holds(Right.PUSH)).as("push is not added").isFalse();
        assertThat(parts.document().get("rights"))
                .as("the rights named, in the contract's order")
                .isEqualTo(List.of("reply", "openThreads"));
    }

    @Test
    void aStartAtAnothersWorkNamingNoRightsBreaksItsRuleAndHasNoParts() {
        final TaskParts.Asked asked = TaskParts.Asked.nothing();

        assertThat(asked.namesNoRights())
                .as("the rule broken")
                .contains(new TaskParts.Broken("foreignWorkNamesNoRights", "rights"));
        assertThatThrownBy(asked::atAnothersWork)
                .as("no default rights there")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStartAtAnothersWorkHasNoServicesAndWritesAsTheOpenerByDefault() {
        final TaskParts parts = asked(Set.of(Right.REPLY), null, null).atAnothersWork();

        assertThat(parts.readsAnothersWork()).as("another task's work").isTrue();
        assertThat(parts.projectServices()).as("no services").isFalse();
        assertThat(parts.writesAsOpener()).as("absent is author").isTrue();
        assertThat(parts.broken()).as("breaks no rule").isEmpty();
    }

    @Test
    void servicesAskedOnAtAnothersWorkLeadOut() {
        final TaskParts parts = asked(Set.of(Right.REPLY), null, Boolean.TRUE).atAnothersWork();

        assertThat(parts.broken())
                .as("the rule its parts break")
                .contains(new TaskParts.Broken("foreignWorkLeadsOut", "projectServices"));
    }

    @Test
    void aStartWithoutAGateHoldsNoRightByDefaultAndPushThereBreaksARule() {
        final TaskParts none =
                new TaskParts.Asked(null, null, null, null, true, false, false).withoutGate();
        final TaskParts pushing =
                new TaskParts.Asked(Set.of(Right.PUSH), null, null, null, false, true, false)
                        .withoutGate();

        assertThat(none.document())
                .as("no workspace, no right, the services on")
                .containsEntry("workspace", "none")
                .containsEntry("rights", List.of())
                .containsEntry("projectServices", Boolean.TRUE);
        assertThat(pushing.broken())
                .as("push without own work")
                .contains(new TaskParts.Broken("pushWithoutOwnWork", "readOnly"));
    }

    @Test
    void aStartBringingATaskBackChangesOnlyWhatItNamesOtherwise() {
        final TaskParts recorded = asked(Set.of(Right.REPLY), null, null).atAnothersWork();

        assertThat(TaskParts.Asked.nothing().changes(recorded))
                .as("a start naming nothing keeps what was recorded")
                .isFalse();
        assertThat(asked(Set.of(Right.REPLY), null, null).changes(recorded))
                .as("the same rights named again")
                .isFalse();
        assertThat(asked(Set.of(Right.REPLY, Right.REJECT), null, null).changes(recorded))
                .as("other rights")
                .isTrue();
        assertThat(asked(null, "review", null).changes(recorded)).as("another account").isTrue();
    }

    @Test
    void eitherAccountIsTakenAtEachWorkAndAbsentIsAuthorAtBoth() {
        for (final String named : List.of("author", "review")) {
            final boolean opener = "author".equals(named);
            assertThat(asked(Set.of(Right.REPLY), named, null).atAnothersWork().writesAsOpener())
                    .as("%s at another task's work", named)
                    .isEqualTo(opener);
            assertThat(asked(null, named, null).atOwnWork().writesAsOpener())
                    .as("%s at its own work", named)
                    .isEqualTo(opener);
        }
        assertThat(asked(Set.of(Right.REPLY), null, null).atAnothersWork().document())
                .as("absent at another task's work")
                .containsEntry("writeAs", "author");
        assertThat(TaskParts.Asked.nothing().atOwnWork().document())
                .as("absent at its own work")
                .containsEntry("writeAs", "author");
    }

    @Test
    void anAccountNoStartNamesIsRefused() {
        assertThatThrownBy(() -> asked(null, "owner", null))
                .as("an account of no start")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTaskWhosePartsWereNeverRecordedShowsPartsThatHoldNothing() {
        assertThat(TaskParts.unrecorded().document())
                .as("written out, nothing held")
                .isEqualTo(
                        Map.of(
                                "workspace",
                                "none",
                                "readLanded",
                                List.of(),
                                "projectServices",
                                Boolean.FALSE,
                                "writeAs",
                                "author",
                                "rights",
                                List.of()));
    }

    private static TaskParts.Asked asked(
            final @Nullable Set<Right> rights,
            final @Nullable String writeAs,
            final @Nullable Boolean projectServices) {
        return new TaskParts.Asked(rights, null, writeAs, projectServices, false, false, false);
    }
}
