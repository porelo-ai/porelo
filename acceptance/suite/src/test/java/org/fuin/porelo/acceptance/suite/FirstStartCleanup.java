package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import java.io.IOException;
import org.fuin.porelo.acceptance.World;
import org.fuin.porelo.machines.Ssh;

/** Keeps the first-start scenario's answers from reaching another scenario. */
public final class FirstStartCleanup {

    private final Script script;

    /** Creates the hook with the scenario's connection. */
    public FirstStartCleanup(World world) {
        this(world::run);
    }

    FirstStartCleanup(Script script) {
        this.script = script;
    }

    /** Removes the answers even when an earlier step failed. */
    @After("@first-start-answers")
    public void removeAnswers() throws IOException {
        final Ssh.Output removed =
                script.run(
                        "rm -f -- \"${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems/answers.json\"");
        assertThat(removed.status())
                .as("could not remove the first-start scenario's answers:%n%s", removed.all())
                .isZero();
    }

    @FunctionalInterface
    interface Script {
        Ssh.Output run(String command) throws IOException;
    }
}
