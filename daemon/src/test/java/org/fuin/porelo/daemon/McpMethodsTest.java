package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * The methods marked as meant for agents. Named here so that a method marked {@code true} is
     * named twice, and none is offered by a marker set by mistake: every one reads, scoped by a
     * task's socket, except {@code Start}, {@code Stop}, {@code Done} and {@code Report}, which a
     * task's rights bound.
     */
    static final Set<String> OFFERED =
            Set.of(
                    "AccountSettings",
                    "Arrivals",
                    "Done",
                    "Events",
                    "HandIns",
                    "Held",
                    "ImageSettings",
                    "Limits",
                    "List",
                    "LogBefore",
                    "Logs",
                    "McpMethods",
                    "Peers",
                    "Pending",
                    "ProjectFileSchema",
                    "Projects",
                    "ProviderModels",
                    "Reach",
                    "Report",
                    "Review",
                    "ReviewPage",
                    "Sets",
                    "Start",
                    "Stop",
                    "Tail",
                    "Usage",
                    "Watch",
                    "WatchProjects",
                    "WorkHeld");

    // A task must be able to request arrivals without gaining another project's history.
    @Test
    void arrivalsAreOfferedScopedToTheTasksOwnProject() {
        assertThat(McpMarkers.offered("Arrivals")).as("arrivals are offered").isTrue();
        assertThat(McpTaskSockets.takingAProject()).contains("Arrivals");
    }

    @Test
    void everyMethodCarriesAMarker() {
        assertThat(McpMarkers.daemon().keySet())
                .as("every method of Tasks1, and no other")
                .isEqualTo(McpMethods.described());
    }

    @Test
    void theOfferedMethodsAreExactlyThoseNamed() {
        final Set<String> offered = new TreeSet<>();
        McpMarkers.daemon()
                .forEach(
                        (name, marker) -> {
                            if (marker.isEmpty()) {
                                offered.add(name);
                            }
                        });

        assertThat(offered).isEqualTo(new TreeSet<>(OFFERED));
        assertThat(OFFERED)
                .as("every writing method a task's rights bound")
                .containsAll(org.fuin.porelo.app.EndpointRights.writing());
    }

    @Test
    void aPersonsJudgementIsNotMeantForAgents() {
        for (final String method :
                List.of(
                        "Approve",
                        "Reject",
                        "Decide",
                        "TrustHostKey",
                        "ConfirmFirstStart",
                        "Authorize",
                        "EnrollDevice",
                        "UnlockWithShare",
                        "Release")) {
            assertThat(McpMarkers.withheld(method))
                    .as(method)
                    .contains("the person's own judgement");
        }
    }

    @Test
    void whatReachesOutOrWritesForThePersonIsNotOffered() {
        // Each carries a marker of its own: an unmarked method would be withheld too, and this
        // test would not see the marker missing.
        assertThat(
                        List.of(
                                "Agents",
                                "CanStart",
                                "CheckProjectFile",
                                "Egress",
                                "FirstStart",
                                "Node",
                                "ReadHeld",
                                "Prepare",
                                "SyncUpstream",
                                "HostKeys",
                                "RefreshTask",
                                "Models",
                                "Credentials",
                                "Say",
                                "Tell"))
                .allSatisfy(
                        method ->
                                assertThat(McpMarkers.daemon().get(method))
                                        .as(method)
                                        .isNotNull()
                                        .isPresent());
    }

    // A project file names the methods it wants; a vault entry's existence, the person's
    // destinations and the transports a text would run are not among what a task may ask.
    @Test
    void checkingAProjectFileAndAProjectsReachAreNotMeantForAgents() {
        assertThat(McpMarkers.withheld("CheckProjectFile"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("vault entries"));
        assertThat(McpMarkers.withheld("Egress"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("installed agents"));
    }

    @Test
    void aMethodWithoutAMarkerIsWithheld() {
        assertThat(McpMarkers.offered("NoSuchMethod")).isFalse();
        assertThat(McpMarkers.withheld("NoSuchMethod"))
                .contains("it carries no marker saying it is meant for agents");
    }

    @Test
    void onlyAMarkerOfItsFormDirectlyAboveItsMethodCounts() {
        final String description =
                String.join(
                        "\n",
                        "# x-mcp: true",
                        "method Marked() -> ()",
                        "# x-mcp: false the reason",
                        "method Refused() -> ()",
                        "method Unmarked() -> ()",
                        "# x-mcp: true",
                        "# a comment between",
                        "method Separated() -> ()",
                        "# x-mcp: yes",
                        "method Malformed() -> ()",
                        "# x-mcp: false " + "r".repeat(201),
                        "method TooLong() -> ()",
                        "");

        assertThat(McpMarkers.read(description))
                .containsOnly(
                        Map.entry("Marked", Optional.empty()),
                        Map.entry("Refused", Optional.of("the reason")));
    }

    @Test
    void aTasksSectionNamingAMethodNotMeantForAgentsIsRefusedWithTheReason() {
        assertThat(McpTaskSockets.refusals(List.of("Approve", "List", "Prepare")))
                .containsExactly(
                        "mcp.methods: 'Approve' is not meant for agents: the person's own judgement",
                        "mcp.methods: 'Prepare' is not meant for agents: reaches the network or"
                                + " the person's forge with the person's credentials, and builds"
                                + " for minutes");
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
        assertThat(McpMethods.NEVER)
                .as("never offered, so never marked true")
                .noneMatch(McpMarkers::offered)
                .allSatisfy(name -> assertThat(McpMarkers.daemon()).containsKey(name));
    }

    @Test
    void aMarkedTrueMethodTheNeverListNamesIsFound() {
        final String description = "# Stops everything.\n# x-mcp: true\nmethod Panic() -> ()\n";

        assertThat(McpMarkers.read(description)).containsEntry("Panic", Optional.empty());
        assertThat(McpMethods.NEVER).contains("Panic");
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
