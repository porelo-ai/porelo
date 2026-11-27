package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.junit.jupiter.api.Test;

class StartRulesTest {

    private static final TaskParts READS_ANOTHER =
            TaskParts.anothersWork(List.of(Right.REPLY, Right.OPEN_THREADS));

    @Test
    void aTaskAtAnothersWorkIsRefusedAddedCredentials() {
        assertThat(StartRules.broken(READS_ANOTHER, true, "prompt"))
                .as("credentials beyond the project's lead out")
                .contains(new TaskParts.Broken("foreignWorkLeadsOut", "credentials"));
    }

    @Test
    void aTaskAtAnothersWorkIsRefusedAClearanceThatLetsABlockedConnectionThrough() {
        assertThat(StartRules.broken(READS_ANOTHER, false, "allow"))
                .as("clearance allow")
                .contains(new TaskParts.Broken("foreignWorkLeadsOut", "clearance allow"));
        assertThat(StartRules.broken(READS_ANOTHER, false, "off"))
                .as("clearance off")
                .contains(new TaskParts.Broken("foreignWorkLeadsOut", "clearance off"));
    }

    @Test
    void aTaskAtAnothersWorkStartsWithAClearanceThatAsksOrDenies() {
        assertThat(StartRules.broken(READS_ANOTHER, false, "prompt")).as("prompt").isEmpty();
        assertThat(StartRules.broken(READS_ANOTHER, false, "deny")).as("deny").isEmpty();
        assertThat(StartRules.broken(READS_ANOTHER, false, null)).as("none named").isEmpty();
    }

    @Test
    void aTaskAtItsOwnWorkKeepsItsCredentialsAndEveryClearance() {
        assertThat(StartRules.broken(TaskParts.ownWork(), true, "allow"))
                .as("own work with added credentials and clearance allow")
                .isEmpty();
        assertThat(StartRules.broken(TaskParts.withoutGate(true, false), true, "off"))
                .as("a task that only reads landed work, with clearance off")
                .isEmpty();
    }

    @Test
    void aRuleThePartsBreakThemselvesIsNamedBeforeTheCredentials() {
        final TaskParts pushing = TaskParts.anothersWork(List.of(Right.PUSH));

        assertThat(StartRules.broken(pushing, true, "allow"))
                .as("another task's work with push")
                .contains(new TaskParts.Broken("foreignWorkLeadsOut", "push"));
    }

    @Test
    void aBrokenRuleIsSaidByTheContractsErrorItsRuleAndItsDetail() {
        assertThat(StartRules.said(new TaskParts.Broken("foreignWorkLeadsOut", "clearance off")))
                .as("what a person is told")
                .isEqualTo("StartRuleBroken: foreignWorkLeadsOut (clearance off)");
    }
}
