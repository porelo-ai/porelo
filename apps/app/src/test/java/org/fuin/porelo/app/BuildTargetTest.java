package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Holds the watch and the manual query to the same vault entry and target. */
class BuildTargetTest {
    private static final String UPSTREAM = "https://forge.example/acme/code.git";
    private static final String API = "https://forge.example/api";
    private PoreloContext context;
    private final String token = java.util.UUID.randomUUID().toString();

    @BeforeEach
    void prepare() throws Exception {
        final Path directory = Files.createTempDirectory(Path.of("target"), "bt-").toAbsolutePath();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> directory.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> directory.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> directory.resolve("run").toString();
                                    default -> null;
                                },
                        directory);
        context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, directory.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> "test passphrase".toCharArray());
        context.vault()
                .write(
                        Map.of(
                                "selected",
                                VaultEntry.of(token),
                                "other",
                                VaultEntry.of("wrong-entry")),
                        "test passphrase".toCharArray());
    }

    @AfterEach
    void forgetPassphrase() {
        PoreloContext.forgetAsked();
    }

    private Target watchTarget() throws Throwable {
        final TaskWatchBuildsCommand watch = new TaskWatchBuildsCommand();
        watch.setContext(context);
        new CommandLine(watch)
                .parseArgs(
                        "task",
                        "--branch=main",
                        "--upstream=" + UPSTREAM,
                        "--api=" + API,
                        "--forge=test-forge",
                        "--credential=selected");
        final var method = TaskWatchBuildsCommand.class.getDeclaredMethod("target");
        method.setAccessible(true);
        try {
            return (Target) method.invoke(watch);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @Test
    void theWatchAndTheQueryReadExactlyTheNamedCredentialForEachQuestion() throws Throwable {
        // Sharing target construction must not change the repository, API or credential a watch
        // uses.
        context.openIfShut(true, true, new PrintWriter(new StringWriter()));
        final Target watched = watchTarget();
        assertThat(watched)
                .as("watch and query ask the same target")
                .isEqualTo(BuildTarget.of(context, UPSTREAM, API, "selected"));
        assertThat(watched.upstream()).as("the upstream is preserved").isEqualTo(UPSTREAM);
        assertThat(watched.api()).as("the configured API is preserved").isEqualTo(API);
        assertThat(watched.token().equals(token)).as("only the named credential was read").isTrue();
        context.vault()
                .write(
                        Map.of("selected", VaultEntry.of("replacement-entry")),
                        "test passphrase".toCharArray());
        assertThat(watchTarget().token().equals("replacement-entry"))
                .as("a later question reads the replacement")
                .isTrue();
    }

    @Test
    void aShutVaultKeepsTheWatchWaitingForAnUnlock() {
        // Extracting the target must not turn an unavailable vault into an empty forge credential.
        context = context.asking(VaultPrompt.NOBODY);
        assertThatThrownBy(this::watchTarget)
                .as("the watch retries later")
                .isInstanceOf(BuildWatch.NotNow.class)
                .hasMessageContaining("vault is shut");
    }

    @Test
    void aMissingEntryKeepsTheWatchWaitingForTheNamedEntry() {
        // A watch must not fall back to another credential when its configured entry disappears.
        context.openIfShut(true, true, new PrintWriter(new StringWriter()));
        context.vault()
                .write(
                        Map.of("other", VaultEntry.of("wrong-entry")),
                        "test passphrase".toCharArray());
        assertThatThrownBy(this::watchTarget)
                .as("the watch names the entry to store")
                .isInstanceOf(BuildWatch.NotNow.class)
                .hasMessageContaining("porelo vault put selected");
    }
}
