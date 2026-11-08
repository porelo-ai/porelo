package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the key the gateway adds toward a task's porelo-mcp: kept as the task's own vault
 * entry, read back on a resume, and taken by the broker for that route alone.
 */
class McpKeyTest {

    private static final String TASK = "porelo-p-task";

    @TempDir Path dir;

    @Test
    void theKeyIsKeptAsTheTasksOwnEntryReadBackAndGoesWithTheTask() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), passphrase);
            keyring.store(passphrase);
            final TaskSecrets secrets = new TaskSecrets(context);
            final String key = "b".repeat(64);

            assertThat(secrets.keepMcpKey(TASK, key)).isEmpty();

            assertThat(secrets.mcpKey(TASK)).contains(key);
            assertThat(context.vault().read(passphrase)).containsKey(TaskSecrets.mcpKeyEntry(TASK));
            assertThat(TaskSecrets.reserved(TaskSecrets.mcpKeyEntry(TASK)))
                    .as("no listing of credentials shows it")
                    .isTrue();
            assertThat(secrets.mcpKey("porelo-p-none")).isEmpty();

            assertThat(secrets.forget(TASK)).isEmpty();
            assertThat(secrets.mcpKey(TASK)).isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void theBrokerTakesARoutesKeyFromTheTasksOwnEntry() {
        final Map<String, String> credentials = new LinkedHashMap<>();

        final String refused =
                VaultServeCommand.taskKeys(
                        Map.of("porelo-mcp", TaskSecrets.mcpKeyEntry(TASK)),
                        Map.of(TaskSecrets.mcpKeyEntry(TASK), VaultEntry.of("c".repeat(64))),
                        credentials);

        assertThat(refused).isNull();
        assertThat(credentials).containsExactly(Map.entry("porelo-mcp", "c".repeat(64)));
    }

    @Test
    void theBrokerRefusesAnEntryThatIsNoTasksOwn() {
        final Map<String, String> credentials = new LinkedHashMap<>();

        // A person's credential is a route's own: naming one here would hand it to any route.
        final String refused =
                VaultServeCommand.taskKeys(
                        Map.of("porelo-mcp", "anthropic"),
                        Map.of("anthropic", VaultEntry.of("sk-test-value")),
                        credentials);

        assertThat(refused).contains("anthropic", "no task's own");
        assertThat(credentials).isEmpty();
    }

    @Test
    void theBrokerSaysWhichKeyTheVaultLacks() {
        final String refused =
                VaultServeCommand.taskKeys(
                        Map.of("porelo-mcp", TaskSecrets.mcpKeyEntry(TASK)),
                        Map.of(),
                        new LinkedHashMap<>());

        assertThat(refused).contains("porelo-mcp");
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
