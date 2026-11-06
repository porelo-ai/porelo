package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.file.RepositorySettings.Asked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryAllowanceTest {

    private static final List<Asked> ASKED =
            List.of(
                    new Asked("egress.domains[build.example.org]", "build.example.org", "", false),
                    new Asked("limits.memory", "8g", "4g", false));

    @TempDir Path dir;

    @Test
    void afterAYesOnlyWhatChangedIsAskedAgain() {
        final RepositoryAllowance allowance = new RepositoryAllowance(context());

        assertThat(allowance.notAllowed("shop", ASKED))
                .as("the first time, all of it")
                .isEqualTo(ASKED);
        allowance.allow("shop", ASKED);

        assertThat(allowance.notAllowed("shop", ASKED)).isEmpty();
        assertThat(allowance.notAllowed("other", ASKED))
                .as("one repository's yes is its own")
                .isEqualTo(ASKED);
        final Asked more = new Asked("limits.memory", "16g", "4g", false);
        assertThat(allowance.notAllowed("shop", List.of(ASKED.getFirst(), more)))
                .as("only the changed value")
                .containsExactly(more);
        assertThat(allowance.notAllowed("shop", List.of(ASKED.getFirst())))
                .as("asking for less asks nothing")
                .isEmpty();
        assertThat(
                        allowance.notAllowed(
                                "shop", List.of(new Asked("limits.memory", "8g", "2g", false))))
                .as("what the project said before is not part of the yes")
                .isEmpty();
    }

    @Test
    void theHashTellsFieldsApart() {
        assertThat(RepositoryAllowance.hash(List.of(new Asked("a", "b\tc", "", false))))
                .isNotEqualTo(RepositoryAllowance.hash(List.of(new Asked("a", "b", "c", false))))
                .hasSize(64);
        assertThat(RepositoryAllowance.hash(List.of()))
                .isEqualTo(RepositoryAllowance.hash(List.of()));
    }

    @Test
    void refusesANameThatIsNoFileName() {
        final RepositoryAllowance allowance = new RepositoryAllowance(context());

        assertThatThrownBy(() -> allowance.allow("../shop", ASKED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(allowance.notAllowed(".hidden", ASKED)).isEqualTo(ASKED);
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new org.fuin.porelo.core.process.ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }
}
