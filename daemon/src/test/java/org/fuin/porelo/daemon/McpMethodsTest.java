package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

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
                    "CanStart",
                    "FirstStart",
                    "Node",
                    "Certify",
                    "RefreshTask",
                    "Stop",
                    "Done",
                    "Report",
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
                    // Runs every ecosystem adapter on a checkout, which may look up through the
                    // person's proxy; and records the person's answer.
                    "ProposeProject",
                    "TakeProposal",
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

    // A task must be able to request arrivals without gaining another project's history.
    @Test
    void arrivalsAreReadingScopedToTheTasksOwnProject() throws Exception {
        assertThat(McpMethods.READING).as("arrivals are available as reading").contains("Arrivals");
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
    void aTasksSocketIsNeverOfferedTheReviewsCommentMethods() {
        // A task answers a thread through its mailbox; through these it would write as the owner
        // account, in any push's review, resolve its own threads, or read what is still open in
        // whoever decides it.
        final List<String> methods =
                List.of(
                        "Comment",
                        "Reply",
                        "Comments",
                        "ResolveThread",
                        "ReviewSummary",
                        "ReviewOpen");
        assertThat(McpMethods.NEVER).as("never offered").containsAll(methods);
        assertThat(McpTaskSockets.refusals(methods))
                .as("a task's section naming one is refused")
                .hasSize(6)
                .allSatisfy(refusal -> assertThat(refusal).contains("is never offered to MCP"));
    }

    @Test
    void aTasksSocketIsNeverOfferedTheHeldItemsOrTheForgeInstances() {
        // A task deciding its own held items would release its own words; choosing how a forge's
        // comments are read would choose what it is shown.
        final List<String> methods = List.of("HeldItems", "DecideItem", "Forges", "ForgeSet");
        assertThat(McpMethods.NEVER).as("never offered").containsAll(methods);
        assertThat(McpTaskSockets.refusals(methods))
                .as("a task's section naming one is refused")
                .hasSize(4)
                .allSatisfy(refusal -> assertThat(refusal).contains("is never offered to MCP"));
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
    void theReplyIsTheNeverListAlone() {
        final Map<String, Object> reply = McpMethods.answer();

        assertThat(reply).containsOnlyKeys("never");
        assertThat((List<Object>) reply.get("never"))
                .as("sorted, every name of NEVER")
                .isSortedAccordingTo((x, y) -> ((String) x).compareTo((String) y))
                .containsExactlyInAnyOrderElementsOf(McpMethods.NEVER);
    }

    // The second guard: a marker cannot offer what the hand-kept list forbids.
    @Test
    void noMethodTheNeverListNamesIsMarkedTrue() {
        final Map<String, String> markers = markers(PoreloDaemon.description());

        assertThat(markers).as("the description carries markers").isNotEmpty();
        assertThat(McpMethods.NEVER)
                .as("never offered, so never marked true")
                .noneMatch(name -> "true".equals(markers.get(name)));
    }

    @Test
    void aMarkedTrueMethodTheNeverListNamesIsFound() {
        final String description = "# Stops everything.\n# x-mcp: true\nmethod Panic() -> ()\n";

        assertThat(markers(description)).containsEntry("Panic", "true");
        assertThat(McpMethods.NEVER).contains("Panic");
    }

    private static final java.util.regex.Pattern MARKED =
            java.util.regex.Pattern.compile(
                    "(?m)^# x-mcp: (true|false)\\b.*\\Rmethod\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    private static Map<String, String> markers(final String description) {
        final Map<String, String> markers = new java.util.TreeMap<>();
        final java.util.regex.Matcher matcher = MARKED.matcher(description);
        while (matcher.find()) {
            markers.put(matcher.group(2), matcher.group(1));
        }
        return markers;
    }

    @Test
    void theDaemonAnswersTheNeverList(@TempDir final Path dir) throws Exception {
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
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply =
                        client.call(PoreloDaemon.INTERFACE + ".McpMethods", Map.of());

                assertThat(reply).containsOnlyKeys("never");
                assertThat((List<Object>) reply.get("never")).contains("Panic");
            }
        }
    }
}
