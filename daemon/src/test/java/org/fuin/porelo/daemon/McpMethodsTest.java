package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                    "RefreshStories",
                    "Start",
                    "CanStart",
                    "FirstStart",
                    "Node",
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
                    "CredentialForget",
                    "ImportCredential",
                    "RemoveDestination",
                    "KeepHostVaultShare",
                    "ForgetHostVaultShare",
                    "PendingBundle",
                    "Landed",
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
    void savingBoardMovesRequiresThePersonsJudgementAndList(@TempDir final Path dir)
            throws Exception {
        assertThat(McpMethods.JUDGEMENT)
                .contains("SaveMoves")
                .doesNotContain("MoveStory", "MoveIssues");
        final Path list = dir.resolve(McpMethods.LOCAL_LIST);
        assertThat(McpMethods.answer(list, McpMethods.described()).get("writing"))
                .isEqualTo(List.of());
        Files.writeString(list, "SaveMoves\nMoveStory\nMoveIssues\n");
        assertThat(McpMethods.answer(list, McpMethods.described()).get("writing"))
                .isEqualTo(List.of("SaveMoves"));
    }

    // A task must be able to request arrivals without gaining another project's history.
    @Test
    void arrivalsAreReadingScopedToTheTasksOwnProject() throws Exception {
        assertThat(
                        McpMethods.answer(Path.of("target/no-mcp-writing"), McpMethods.described())
                                .get("reading"))
                .as("arrivals are available as reading")
                .asList()
                .contains("Arrivals");
        assertThat(McpTaskSockets.takingAProject()).contains("Arrivals");
    }

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
    void whatHandsOutASecretOrRedirectsACredentialIsNeverOffered() {
        assertThat(McpMethods.NEVER)
                .as("a held message's text, secrets included; where a credential is sent")
                .contains("ReadHeld", "WriteDestination", "CredentialDeclare");
    }

    @Test
    void aTaskIsNeverOfferedAnAgentsSignIn() {
        // A sign-in runs on the host's own network with the real credential being made in it, and
        // its wait holds a call for half an hour: neither is a task's to start.
        assertThat(McpMethods.NEVER)
                .as("starting or waiting on a sign-in")
                .contains("AgentSignInStart", "AgentSignInWait");
    }

    @Test
    void whatWritesAnythingIsNotReading() {
        assertThat(McpMethods.READING)
                .as("each writes a file, fetches, or takes a project")
                .doesNotContain("CanStart", "FirstStart", "Node", "ReadHeld")
                .contains("McpMethods");
        assertThat(McpMethods.JUDGEMENT).as("dropping pending work").contains("Reject");
    }

    @Test
    void aListThatIsALinkIsRefused(@TempDir final Path dir) throws Exception {
        final Path real = Files.writeString(dir.resolve("elsewhere"), "Start\n");
        final Path list = Files.createSymbolicLink(dir.resolve(McpMethods.LOCAL_LIST), real);

        assertThatThrownBy(() -> McpMethods.answer(list, McpMethods.described()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("link");
    }

    @Test
    void aListThatIsAPipeIsRefusedWithoutWaiting(@TempDir final Path dir) throws Exception {
        final Path list = dir.resolve(McpMethods.LOCAL_LIST);
        assertThat(new ProcessBuilder("mkfifo", list.toString()).start().waitFor()).isZero();

        assertThat(
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                McpMethods.answer(list, McpMethods.described());
                                                return "read";
                                            } catch (java.io.IOException ex) {
                                                return "refused";
                                            }
                                        })
                                .get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo("refused");
    }

    @Test
    void aListLargerThanAnyListOfMethodsIsRefused(@TempDir final Path dir) throws Exception {
        final Path list =
                Files.writeString(dir.resolve(McpMethods.LOCAL_LIST), "# x\n".repeat(100_000));

        assertThatThrownBy(() -> McpMethods.answer(list, McpMethods.described()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("large");
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
