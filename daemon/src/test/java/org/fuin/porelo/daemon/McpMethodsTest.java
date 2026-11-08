package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which methods porelo-mcp may offer a client as tools. */
@SuppressWarnings("unchecked")
class McpMethodsTest {

    /**
     * Methods that change something and are neither destructive nor a person's judgement: offered
     * only when the person lists them. Named here so that a new method has to be sorted into one of
     * the four, and none falls into reading by being forgotten.
     */
    static final Set<String> WRITING_ONLY_FROM_THE_LIST =
            Set.of(
                    "Start",
                    "Certify",
                    "RefreshTask",
                    "Stop",
                    "Lock",
                    "AllowSettings",
                    "Label",
                    "Prepare",
                    "LogArchive",
                    "HandIn",
                    "TakeBack",
                    "TakeProject",
                    "SyncUpstream",
                    "AddToDefault",
                    "RemoveFromDefault",
                    "DeployKey",
                    "SetEgress",
                    "SetClearance",
                    "NarrowTask",
                    "WidenTask",
                    "CredentialDeclare",
                    "CredentialForget",
                    "ImportCredential",
                    "WriteDestination",
                    "RemoveDestination",
                    "KeepHostVaultShare",
                    "ForgetHostVaultShare",
                    "PendingBundle",
                    "Landed",
                    "Reject",
                    "CredentialTryPush",
                    "MessageKey",
                    "Say",
                    "Tell",
                    "Moderate",
                    "SetUpMessages",
                    "JoinMessages",
                    "AdmitMachine",
                    "DismissMachine",
                    "RemoveMessageMember",
                    "Tighten");

    @Test
    void everyMethodIsSortedIntoExactlyOneSet() {
        final List<Set<String>> sets =
                List.of(
                        McpMethods.READING,
                        McpMethods.NEVER,
                        McpMethods.JUDGEMENT,
                        WRITING_ONLY_FROM_THE_LIST);
        final Set<String> sorted = new TreeSet<>();
        int count = 0;
        for (final Set<String> set : sets) {
            sorted.addAll(set);
            count += set.size();
        }
        final Set<String> described = new TreeSet<>(McpMethods.described());
        described.remove("McpMethods");

        assertThat(sorted).as("every method of Tasks1, and no other").isEqualTo(described);
        assertThat(count).as("no method in two sets").isEqualTo(sorted.size());
    }

    @Test
    void theDestructiveMethodsAreNeverOffered() {
        assertThat(McpMethods.NEVER)
                .contains(
                        "Panic",
                        "Clear",
                        "VaultClear",
                        "Prune",
                        "RemoveProject",
                        "Remove",
                        "DeleteBackup",
                        "RestoreBackup",
                        "CredentialRemove",
                        "CredentialReplace",
                        "RevokeKeyslot",
                        "ReadHostVaultShare");
    }

    @Test
    void writingIsThePersonsListLessNeverReadingAndUnknownNames(@TempDir final Path dir)
            throws Exception {
        final Path list = dir.resolve(McpMethods.LOCAL_LIST);
        Files.writeString(
                list,
                "# what my own client may do\n"
                        + "Start\n"
                        + "  Stop   # only my tasks\n"
                        + "\n"
                        + "Panic\n"
                        + "List\n"
                        + "org.fuin.porelo.Tasks1.Label\n"
                        + "NoSuchMethod\n"
                        + "Approve\n");

        final Map<String, Object> reply = McpMethods.answer(list, McpMethods.described());

        assertThat(reply.get("writing"))
                .as("listed, less never, reading, a full name and an unknown one")
                .isEqualTo(List.of("Approve", "Start", "Stop"));
    }

    @Test
    void withNoListNothingIsWriting(@TempDir final Path dir) throws Exception {
        final Map<String, Object> reply =
                McpMethods.answer(dir.resolve(McpMethods.LOCAL_LIST), McpMethods.described());

        assertThat(reply.get("writing")).isEqualTo(List.of());
        assertThat((List<Object>) reply.get("reading")).contains("List", "Watch", "Events");
    }

    @Test
    void theDaemonAnswersFromThePersonsConfigDirectory(@TempDir final Path dir) throws Exception {
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
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        Files.createDirectories(dir.resolve("config/porelo"));
        Files.writeString(dir.resolve("config/porelo/mcp-writing"), "Start\n");

        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply =
                        client.call(PoreloDaemon.INTERFACE + ".McpMethods", Map.of());

                assertThat(reply.get("writing")).isEqualTo(List.of("Start"));
                assertThat((List<Object>) reply.get("never")).contains("Panic");
                assertThat((List<Object>) reply.get("judgement")).contains("Approve");
            }
        }
    }
}
