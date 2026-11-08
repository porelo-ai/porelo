package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for removing and replacing a vault entry over the socket. */
class VaultEntryMethodsTest {

    private static final char[] PASSPHRASE = "fixture passphrase".toCharArray();

    private static final String OLD_VALUE = "old-secret-4f1c9a";

    private static final String NEW_VALUE = "new-secret-8d2e7b";

    @TempDir Path dir;

    private PoreloContext context;

    private PoreloContext context(final boolean asking) {
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
        final PoreloContext made =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        return asking ? made.asking(prompt -> PASSPHRASE.clone()) : made;
    }

    /** Writes a vault holding the entries and opens it the way a person at the machine did. */
    private void openVault(final Map<String, VaultEntry> entries) throws Exception {
        context = context(true);
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(entries, PASSPHRASE);
        assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault without a kernel keyring")
                .isPresent();
    }

    @AfterEach
    void forgetThePassphrase() {
        if (context != null) {
            context.forgetTyped();
        }
    }

    private Map<String, Object> call(final String method, final Map<String, Object> parameters)
            throws Exception {
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context, socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    private VarlinkException refusal(final String method, final Map<String, Object> parameters) {
        return catchThrowableOfType(VarlinkException.class, () -> call(method, parameters));
    }

    @Test
    void aRemovedEntryIsGoneFromTheVaultFileWithItsValue() throws Exception {
        openVault(Map.of("forge", VaultEntry.of(OLD_VALUE), "keep", VaultEntry.of("kept")));

        final Map<String, Object> reply = call("CredentialRemove", Map.of("name", "forge"));

        final Map<String, VaultEntry> left = context.vault().read(PASSPHRASE);
        assertThat(left)
                .as("only the other entry stays in the vault file")
                .containsOnlyKeys("keep");
        assertThat(left.values())
                .as("no entry left in the vault file holds the removed value")
                .noneMatch(entry -> entry.value().equals(OLD_VALUE));
        assertThat(Json.write(reply))
                .as("the reply carries no part of the value")
                .doesNotContain(OLD_VALUE);
    }

    @Test
    void aReplacedValueIsReadBackAndAppearsInNoReplyAndNoLogLine() throws Exception {
        openVault(
                Map.of(
                        "forge",
                        new VaultEntry(
                                OLD_VALUE, "token", Map.of("token_url", "https://a.invalid/t"))));
        final ByteArrayOutputStream logged = new ByteArrayOutputStream();
        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final Map<String, Object> reply;
        System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            reply = call("CredentialReplace", Map.of("name", "forge", "value", NEW_VALUE));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        final VaultEntry read = context.vault().read(PASSPHRASE).get("forge");
        assertThat(read.value()).as("the new value is read back").isEqualTo(NEW_VALUE);
        assertThat(read.type()).as("the entry keeps its kind").isEqualTo("token");
        assertThat(read.settings())
                .as("the entry keeps its settings")
                .containsEntry("token_url", "https://a.invalid/t");
        assertThat(reply).as("the reply says the vault was written").containsEntry("changed", true);
        assertThat(Json.write(reply))
                .as("the reply carries no part of either value")
                .doesNotContain(NEW_VALUE)
                .doesNotContain(OLD_VALUE);
        assertThat(logged.toString(StandardCharsets.UTF_8))
                .as("no line printed while replacing carries the value")
                .doesNotContain(NEW_VALUE);
    }

    @Test
    void aShutVaultIsRefusedByNameAndTheRefusalCarriesNoValue() throws Exception {
        openVault(Map.of("forge", VaultEntry.of(OLD_VALUE)));
        context.forgetTyped();
        context = context(false);

        final VarlinkException removing = refusal("CredentialRemove", Map.of("name", "forge"));
        final VarlinkException replacing =
                refusal("CredentialReplace", Map.of("name", "forge", "value", NEW_VALUE));

        assertThat(removing.getErrorName())
                .as("a shut vault refuses a removal by its own error")
                .isEqualTo(PoreloDaemon.INTERFACE + ".CredentialRefused");
        assertThat(removing.getParameters())
                .as("the refusal names the shut vault")
                .containsEntry("reason", "VAULT_LOCKED")
                .containsEntry("name", "forge");
        assertThat(replacing.getParameters())
                .as("a replace into a shut vault is refused the same way")
                .containsEntry("reason", "VAULT_LOCKED");
        assertThat(Json.write(replacing.getParameters()) + replacing.getMessage())
                .as("the refusal carries no part of the value")
                .doesNotContain(NEW_VALUE);
        assertThat(context.vault().read(PASSPHRASE).get("forge").value())
                .as("nothing changed in the shut vault")
                .isEqualTo(OLD_VALUE);
    }

    @Test
    void anUnknownEntryIsRefusedByItsOwnName() throws Exception {
        openVault(Map.of("keep", VaultEntry.of("kept")));

        final VarlinkException removing = refusal("CredentialRemove", Map.of("name", "absent"));
        final VarlinkException replacing =
                refusal("CredentialReplace", Map.of("name", "absent", "value", NEW_VALUE));

        assertThat(removing.getParameters())
                .as("removing an entry the vault does not hold is refused by name")
                .containsEntry("reason", "NO_SUCH_CREDENTIAL")
                .containsEntry("name", "absent");
        assertThat(replacing.getParameters())
                .as("a replace does not create an entry")
                .containsEntry("reason", "NO_SUCH_CREDENTIAL");
        assertThat(Json.write(replacing.getParameters()))
                .as("the refusal carries no part of the value")
                .doesNotContain(NEW_VALUE);
        assertThat(context.vault().read(PASSPHRASE))
                .as("the vault is as it was")
                .containsOnlyKeys("keep");
    }

    @Test
    void aTasksOwnEntryIsRefusedAsReserved() throws Exception {
        openVault(Map.of("task/porelo-uc-shell-1/route/a", VaultEntry.of(OLD_VALUE)));

        final VarlinkException removing =
                refusal("CredentialRemove", Map.of("name", "task/porelo-uc-shell-1/route/a"));

        assertThat(removing.getParameters())
                .as("an entry Credentials never lists is not a name here")
                .containsEntry("reason", "RESERVED");
        assertThat(context.vault().read(PASSPHRASE))
                .as("the task's own token stays")
                .containsKey("task/porelo-uc-shell-1/route/a");
    }
}
