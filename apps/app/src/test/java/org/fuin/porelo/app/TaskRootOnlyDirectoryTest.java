package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.runtime.Containerfile;
import org.junit.jupiter.api.Test;

/** The directory only a task's root reaches holds nothing Porelo hands the agent. */
class TaskRootOnlyDirectoryTest {

    @Test
    void nothingTheAgentIsGivenLiesInTheDirectoryOnlyRootReaches() {
        // Harm: the agent cannot read its guide or mail, nor run the signing shim, behind 0700.
        final String rootOnly = Containerfile.TMUX_DIRECTORY + "/";
        for (final String given :
                List.of(
                        TaskGuide.MOUNT,
                        Mailbox.MOUNT,
                        SigningShim.MOUNT,
                        ScreenFile.MOUNT,
                        TaskResult.MOUNT,
                        TaskWiring.VAULT_SOCKET)) {
            assertThat(given)
                    .as("%s is mounted for the agent, so it cannot lie under %s", given, rootOnly)
                    .doesNotStartWith(rootOnly);
        }
    }
}
