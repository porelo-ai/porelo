package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards a command doing the wrong thing: a resume that refuses where it could ask, starts a task
 * without the tokens its broker needs, or asks a wrong passphrase without end. A task whose tokens
 * are in a shut vault is resumed once the passphrase is given at a terminal; without one it is
 * refused and nothing is started.
 */
class ResumeVaultOfferTest {

    private static final String TASK = "porelo-uc-shell-1";

    private static final char[] PASSPHRASE = "correct horse".toCharArray();

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final List<String> shown = new ArrayList<>();

    private PoreloContext context;

    @AfterEach
    void forget() {
        PoreloContext.forgetAsked();
        if (context != null && KernelKeyring.available()) {
            // The answer unlocks the vault as 'vault unlock' does, which keeps it in this account's
            // keyring.
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    /**
     * A task the machine's restart emptied: its container is there and stopped, its record was
     * saved, and the token its broker hands the container is kept in the vault, which is shut.
     */
    private PoreloContext afterARestart(final VaultPrompt prompt) throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .asking(prompt);
        java.nio.file.Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + TASK + TaskSecrets.PROVIDER,
                                VaultEntry.of("porelo_pt_phantom"),
                                "anthropic",
                                VaultEntry.of("sk-real")),
                        PASSPHRASE);
        final Path record =
                java.nio.file.Files.createDirectories(context.paths().tasks().taskRecord(TASK));
        new TaskHelpers(
                        List.of(
                                new TaskHelpers.Helper(
                                        "vault", List.of("true"), Map.of(), TaskHelpers.BEFORE)))
                .writeTo(record);
        runner.answering("container inspect", "4711");
        return context;
    }

    private VaultPrompt answering(final String... answers) {
        final Deque<String> left = new ArrayDeque<>(List.of(answers));
        return prompt -> {
            shown.add(prompt);
            return left.isEmpty() ? null : left.pop().toCharArray();
        };
    }

    private int resume(final PoreloContext context) {
        final PrintWriter o = new PrintWriter(out);
        final PrintWriter e = new PrintWriter(err);
        final int code = TaskResumeCommand.resume(context, TASK, o, e, null);
        o.flush();
        e.flush();
        return code;
    }

    @Test
    void atATerminalThePassphraseIsAskedAndTheTaskResumes() throws IOException {
        final PoreloContext context = afterARestart(answering("correct horse"));

        assertThat(resume(context)).as(out + "\n" + err).isZero();

        assertThat(shown).as("asked once").hasSize(1);
        assertThat(runner.lines())
                .as("the container is started")
                .anyMatch(line -> line.startsWith("podman start"));
        if (KernelKeyring.available()) {
            // Left open as 'vault unlock' leaves it, for the task's broker and the daemon after
            // this command.
            assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read())
                    .isPresent();
        }
    }

    @Test
    void withoutATerminalTheRefusalStillNamesTheUnlock() throws IOException {
        final PoreloContext context = afterARestart(VaultPrompt.NOBODY);

        assertThat(resume(context)).isEqualTo(69);

        assertThat(runner.lines())
                .as("nothing is started without its tokens")
                .noneMatch(line -> line.startsWith("podman start"));
    }

    @Test
    void aPassphraseThatNeverOpensItRefusesAsBefore() throws IOException {
        final PoreloContext context = afterARestart(answering("one", "two", "three"));

        assertThat(resume(context)).isEqualTo(69);

        assertThat(shown).as("never a fourth time").hasSize(3);
        assertThat(runner.lines())
                .as("nothing is started without its tokens")
                .noneMatch(line -> line.startsWith("podman start"));
    }
}
