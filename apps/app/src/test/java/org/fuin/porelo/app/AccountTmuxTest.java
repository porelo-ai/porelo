package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.runtime.Containerfile;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how a person's own tmux settings reach a task: copied in as root, so the agent reads
 * them and cannot change them, and nothing at all without the file.
 */
class AccountTmuxTest {

    private static PoreloPaths paths(final Path dir) {
        return new PoreloPaths(
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir),
                dir.resolve("bin"));
    }

    @Test
    void theAccountsFileIsTheOneBesideItsOtherPoreloSettings(@TempDir final Path dir) {
        assertThat(paths(dir).tasks().accountTmux())
                .isEqualTo(dir.resolve("config/porelo/tmux.conf"));
    }

    @Test
    void withoutTheFileNothingIsCopied(@TempDir final Path dir) {
        final TaskRunner runner = new TaskRunner(new FakeCommandRunner(), paths(dir));

        assertThat(runner.accountTmuxSteps("porelo-p-t")).isEmpty();
    }

    @Test
    void theFileIsWrittenAsRootAndLeftReadableOnly(@TempDir final Path dir) throws IOException {
        final PoreloPaths paths = paths(dir);
        Files.createDirectories(paths.tasks().accountTmux().getParent());
        Files.writeString(paths.tasks().accountTmux(), "bind -n F5 display-message mine\n");
        final TaskRunner runner = new TaskRunner(new FakeCommandRunner(), paths);

        final List<List<String>> steps = runner.accountTmuxSteps("porelo-p-t");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0))
                .containsSubsequence("exec", "--interactive", "--user", "root")
                .contains("of=" + Containerfile.ACCOUNT_TMUX_CONF);
        assertThat(steps.get(1))
                .containsSubsequence("exec", "--user", "root")
                .containsSubsequence("chmod", "0644", Containerfile.ACCOUNT_TMUX_CONF);
    }
}
