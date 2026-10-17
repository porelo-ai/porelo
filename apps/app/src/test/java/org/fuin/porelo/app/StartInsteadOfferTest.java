package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Tests that a start refused for earlier work of its name offers the next free name, and one
 * refused for want of an agent or a gate offers a shell, instead of naming what to type.
 */
class StartInsteadOfferTest {

    private Offer answering(final String answer) {
        return new Offer(
                question -> answer, false, false, new PrintWriter(new StringWriter(), true));
    }

    @Test
    void earlierWorkAtTheGateOffersTheNextFreeName() {
        assertThat(
                        TaskLaunch.nameInstead(
                                "work of an earlier task called app waits at the gate",
                                "app-2",
                                answering("")))
                .isEqualTo("name:app-2");
        assertThat(TaskLaunch.nameInstead("work …", "app-2", answering("n"))).isNull();
        assertThat(TaskLaunch.nameInstead("work …", null, answering("")))
                .as("no free name found")
                .isNull();
    }

    @Test
    void noAgentOrNoGateOffersAShell() {
        assertThat(TaskLaunch.shellInstead("no agent to attach", answering(""))).isEqualTo("shell");
        assertThat(TaskLaunch.shellInstead("no agent to attach", answering("n"))).isNull();
    }

    @Test
    void nobodyToAskIsTheRefusalAsBefore() {
        assertThat(TaskLaunch.nameInstead("work …", "app-2", Offer.NOBODY)).isNull();
        assertThat(TaskLaunch.shellInstead("no agent to attach", Offer.NOBODY)).isNull();
    }
}
