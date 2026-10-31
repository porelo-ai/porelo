package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A locked vault stopped every conversation without a word (2026-10-04). */
class VaultLockedConversationsTest {

    @TempDir private Path dir;

    private PoreloContext context() {
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
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aVaultThatIsThereAndShutIsSaidAsWhatTheConversationsWaitFor() throws IOException {
        final PoreloContext context = context();
        assertThat(Conversations.vaultWait(context))
                .as("no vault at all: nothing to wait for")
                .isEmpty();

        context.vault().write(Map.of(), "fixture passphrase".toCharArray());

        assertThat(Conversations.vaultWait(context))
                .contains("the vault is locked")
                .contains("porelo vault unlock");
    }

    @ParameterizedTest
    @CsvSource({"true, false, vault", "true, true, ''", "false, false, ''"})
    void aShutVaultNamesTheFileItChecked(boolean vaultExists, boolean unlocked, String waitsOn)
            throws IOException {
        // A missing or stale remedy leaves blocked messages without an action that can help.
        final char[] passphrase = "fixture passphrase".toCharArray();
        final PoreloContext context = context().asking(prompt -> passphrase.clone());
        if (vaultExists) {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), passphrase);
        }
        if (unlocked) {
            assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                    .as("the fixture opens its own vault without a kernel keyring")
                    .isPresent();
        }
        assertThat(context.opener().isPresent())
                .as("the fixture's opener matches the requested vault state")
                .isEqualTo(unlocked);
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: p
                                  security_class: guarded
                                image:
                                  base_image: "ubuntu:24.04"
                                mail:
                                  peers:
                                    reviewer: { address: "room:", trust: vouched }
                                """),
                        "fixture");

        final Map<String, Object> row = Conversations.row(context, project);

        assertThat(row)
                .as("the action and the sentence must agree about what blocks messages")
                .containsEntry(
                        "vault",
                        waitsOn.isEmpty() ? "" : context.paths().vault().vaultFile().toString())
                .containsEntry("waitsOn", waitsOn)
                .containsEntry("waits", waitsOn.isEmpty() ? "" : Conversations.VAULT_LOCKED);
    }
}
