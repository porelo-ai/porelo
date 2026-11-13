package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A verb added to the common executor must acquire the same fence as existing verbs. */
class TransportVerbsTest {
    @TempDir Path dir;

    @Test
    void theCommonVerbPathResolvesEachLaunchUsingItsSuppliedDnsInputs() throws Exception {
        // Harm: a wrapper discards the fakeable DNS inputs or retains one run's old addresses.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final List<String> answers = new java.util.ArrayList<>(List.of("192.0.2.10", "192.0.2.20"));
        final Path resolvConf =
                Files.writeString(dir.resolve("resolv.conf"), "nameserver 127.0.0.53\n");
        final TransportVerbs verbs =
                new TransportVerbs(
                                runner,
                                name -> Path.of("/usr/bin", name),
                                name ->
                                        new InetAddress[] {
                                            InetAddress.ofLiteral(answers.removeFirst())
                                        },
                                resolvConf)
                        .reaching(List.of("server.example.org"), false);

        verbs.run(Command.of("/transport", "poll"));
        verbs.run(Command.of("/transport", "send"), line -> {});

        assertThat(script(runner.invocations().getFirst()))
                .as("the first verb is fenced to its own answer")
                .contains("ip daddr 192.0.2.10 accept")
                .doesNotContain("192.0.2.20");
        assertThat(script(runner.invocations().getLast()))
                .as("the next verb is fenced to the next answer")
                .contains("ip daddr 192.0.2.20 accept")
                .doesNotContain("192.0.2.10");
        assertThat(runner.invocations())
                .as("both launch paths retain the supplied loopback resolver configuration")
                .allSatisfy(
                        command ->
                                assertThat(command.arguments())
                                        .as("DNS port mappings")
                                        .containsSubsequence("-T", "53", "-U", "53"));
        assertThat(answers).as("each launch makes a lookup").isEmpty();
    }

    @Test
    void setupDoesNotReadDnsInputsOrResolveSavedNames() {
        // Harm: the accepted setup exception accidentally depends on the endpoint it establishes.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportVerbs verbs =
                new TransportVerbs(
                                runner,
                                name -> {
                                    throw new AssertionError("setup needs no fence tools");
                                },
                                name -> {
                                    throw new AssertionError("setup needs no reach lookup");
                                },
                                dir.resolve("absent-resolv.conf"))
                        .reaching(List.of("server.example.org"), false);

        verbs.run(Command.of("/transport", "setup"));

        assertThat(runner.invocations()).as("setup runs once").hasSize(1);
        assertThat(runner.invocations().getFirst().arguments())
                .as("the accepted setup exception is executed directly")
                .containsExactly("/transport", "setup");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "describe", "settings", "enroll", "retire", "join", "part", "members", "clear",
                "admit", "dismiss", "poll", "send", "read", "receipt", "rename", "future-verb"
            })
    void everyVerbExceptSetupStartsBehindTheFence(final String verb) {
        // Harm: a new or overlooked verb inherits unrestricted host networking.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportVerbs verbs = new TransportVerbs(runner, name -> Path.of("/usr/bin", name));

        verbs.reaching(List.of("127.0.0.1:8008", "192.0.2.10"), false)
                .run(Command.of("/transport", verb));

        final Command command = runner.invocations().getFirst();
        assertThat(command.program()).as("%s must enter pasta", verb).isEqualTo("/usr/bin/pasta");
        assertThat(command.arguments())
                .as("the verb and its only saved loopback port survive wrapping")
                .containsSubsequence("-T", "8008")
                .endsWith("/transport", verb);
        assertThat(command.arguments().get(command.arguments().indexOf("-c") + 1))
                .as("the saved literal address is the only external allowance")
                .contains("policy drop;", "ip daddr 192.0.2.10 accept");
    }

    @Test
    void sendingUsesItsTasksSavedNetworkAllowance() throws Exception {
        // Harm: message delivery bypasses the fence or maps another task's host services.
        final FakeCommandRunner runner =
                new TransportTestRunner()
                        .answering("describe", "{\"scheme\":\"room\"}")
                        .answering(" send ", "{}");
        final PoreloContext context = context(runner);
        lifecycle(context);
        final TransportDirectory transports =
                new TransportDirectory(List.of(dir.resolve("adapters")));
        final Mailbox mailbox = new Mailbox(dir.resolve("mailbox"));
        mailbox.create();
        Files.createDirectories(mailbox.queueActive("room"));
        Files.writeString(mailbox.queueActive("room").resolve("m.json"), "{}");
        Files.writeString(mailbox.queueActive("room").resolve("m.json.sig"), "fixture signature");
        Files.writeString(mailbox.queueActive("room").resolve("m.json.to"), "!room");
        final TransportSend.Acting acting =
                new TransportSend.Acting(
                        Map.of(),
                        "!room",
                        Map.of(),
                        List.of("127.0.0.1:8008", "192.0.2.10"),
                        false);

        new TransportSend(runner, transports, (scheme, container) -> acting).send(mailbox, "room");

        final Command command = runner.only(" send ");
        assertThat(command.program()).as("delivery enters pasta").isEqualTo("/usr/bin/pasta");
        assertThat(command.arguments())
                .as("the saved homeserver port is explicitly mapped")
                .containsSubsequence("-T", "8008");
        assertThat(script(command))
                .as("delivery has only the saved external reach")
                .contains("ip daddr 192.0.2.10 accept");
    }

    @Test
    void mailboxPollingWithoutSetupHasNoHostPortOrExternalAllowance() throws Exception {
        // Harm: a transport with no saved reach inherits unrestricted network access during
        // polling.
        final FakeCommandRunner runner =
                new TransportTestRunner()
                        .answering("describe", "{\"scheme\":\"room\",\"poll\":true}");
        final PoreloContext context = context(runner);
        lifecycle(context);
        final Mailbox mailbox = new Mailbox(dir.resolve("mailbox"));
        mailbox.create();

        new TransportPoll(runner, new TransportDirectory(List.of(dir.resolve("adapters"))))
                .poll(mailbox);

        final Command command = runner.only(" poll ");
        assertThat(command.program())
                .as("mailbox polling enters pasta too")
                .isEqualTo("/usr/bin/pasta");
        assertThat(command.arguments())
                .as("absent saved ports disable forwarding")
                .containsSubsequence("-T", "none");
        assertThat(script(command))
                .as("absent reaches leave only namespace loopback")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain("ip daddr", "ip6 daddr");
    }

    @Test
    void setupIsTheOnlyAcceptedUnfencedVerb() {
        // Harm: an expanding exemption list silently leaves other verbs on the host network.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportVerbs verbs = new TransportVerbs(runner, name -> Path.of("/usr/bin", name));
        final List<String> names = List.of("setup", "setup-extra", "pre-setup", "future-verb");

        for (final String name : names) verbs.run(Command.of("/transport", name));

        assertThat(
                        runner.invocations().stream()
                                .filter(command -> !"/usr/bin/pasta".equals(command.program()))
                                .map(command -> command.arguments().get(1)))
                .as("the sole exception is exact setup, and arbitrary new verbs are fenced")
                .containsExactly("setup");
        assertThat(runner.invocations())
                .as("setup retains the chosen environment too")
                .allSatisfy(command -> assertThat(command.inheritsEnvironment()).isFalse());
    }

    @Test
    void lifecycleCallsUseSavedReachesRatherThanTheProjectsNewSettings() throws Exception {
        // Harm: a lifecycle operation can accidentally contact a server setup never named.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context = context(runner);
        final Path adapters = Files.createDirectories(dir.resolve("adapters"));
        final Path adapter =
                Files.writeString(adapters.resolve(TransportDirectory.PREFIX + "room"), "");
        assertThat(adapter.toFile().setExecutable(true))
                .as("the fixture adapter is executable")
                .isTrue();
        final TransportDirectory transports = new TransportDirectory(List.of(adapters));
        final TransportLifecycle lifecycle =
                new TransportLifecycle(
                        context,
                        transports,
                        dir.resolve("host"),
                        name -> Path.of("/usr/bin", name));
        runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"join\"]}");
        runner.answering(" join ", "{}");
        remember(context, "p", List.of("127.0.0.1:8008", "192.0.2.10"));
        remember(context, "other", List.of("127.0.0.1:8009", "192.0.2.20"));
        register(context, "guarded");

        lifecycle.join("room", "p", "alice", false, Map.of("homeserver", "https://192.0.2.30"));

        final Command command = runner.only(" join ");
        assertThat(command.program())
                .as("a real lifecycle call enters pasta")
                .isEqualTo("/usr/bin/pasta");
        assertThat(command.arguments())
                .as("only this project's saved loopback port is mapped")
                .containsSubsequence("-T", "8008")
                .doesNotContain("8009");
        assertThat(script(command))
                .as("neither another project's server nor new settings widen reaches")
                .contains("ip daddr 192.0.2.10 accept")
                .doesNotContain("192.0.2.20", "192.0.2.30");
    }

    @Test
    void theCurrentOfflineClassOverridesAnOnlineConversationsSavedReaches() throws Exception {
        // Harm: changing a project to offline leaves its earlier external destinations open.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context = context(runner);
        remember(context, "p", List.of("127.0.0.1:8008", "192.0.2.10"));
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n"
                                + "  name: p\n"
                                + "  security_class: offline\n"
                                + "image:\n"
                                + "  from: ubuntu:24.04\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("p", project);
        final TransportVerbs verbs = new TransportVerbs(runner, name -> Path.of("/usr/bin", name));

        verbs.project(context, "room", "p", false).run(Command.of("/transport", "poll"));

        final Command command = runner.invocations().getFirst();
        assertThat(command.arguments())
                .as("offline still maps its own saved homeserver port")
                .containsSubsequence("-T", "8008");
        assertThat(script(command))
                .as("the old external destination is now refused")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain("192.0.2.10");
    }

    @Test
    void unavailableProjectPolicyCannotRestoreSavedExternalReaches() throws Exception {
        // Harm: losing the project file silently reopens an offline project's old network access.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context = context(runner);
        remember(context, "p", List.of("127.0.0.1:8008", "192.0.2.10"));
        register(context, "offline");
        Files.delete(dir.resolve("project.yml"));

        new TransportVerbs(runner, name -> Path.of("/usr/bin", name))
                .project(context, "room", "p", false)
                .run(Command.of("/transport", "poll"));

        assertThat(script(runner.invocations().getFirst()))
                .as("unknown policy grants no external access")
                .doesNotContain("192.0.2.10", "ip daddr", "ip6 daddr");
    }

    @Test
    void aFailedFencedDescriptionCannotBeMistakenForAnUnsupportedClear() throws Exception {
        // Harm: failed namespace/rules startup makes cleanup erase credentials without retiring
        // them.
        final FakeCommandRunner runner =
                new FakeCommandRunner().failing("/usr/bin/pasta", 42, "rules did not load");
        final PoreloContext context = context(runner);
        final TransportLifecycle lifecycle = lifecycle(context);
        remember(context, "p", List.of("127.0.0.1:8008"));

        assertThatThrownBy(() -> lifecycle.clear("room", "p", Map.of()))
                .as("description failure stops cleanup rather than declaring clear unsupported")
                .isInstanceOf(TransportLifecycle.Refused.class);
        assertThat(context.paths().xdg().state().resolve("transport/room/p.json"))
                .as("the conversation remains available for a retry")
                .exists();
        assertThat(runner.invocations()).as("nothing follows the failed description").hasSize(1);
    }

    @Test
    void accountCleanupRetainsSetupReachesWhileProjectCleanupRemovesTheirFiles() throws Exception {
        // Harm: account cleanup loses its mapped homeserver after the last project is cleared.
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering("describe", "{\"lifecycle\":[\"clear\"]}")
                        .answering(" clear", "{}");
        final PoreloContext context = context(runner);
        final TransportLifecycle lifecycle = lifecycle(context);
        register(context, "guarded");
        remember(context, "p", List.of("127.0.0.1:8008", "192.0.2.10"));
        lifecycle.retainAccountReaches();
        Files.delete(context.paths().xdg().state().resolve("transport/room/p.json"));

        lifecycle.clear("room", null, Map.of());

        final Command clear = runner.only(" clear");
        assertThat(clear.arguments())
                .as("the account still reaches the saved local listener")
                .containsSubsequence("-T", "8008");
        assertThat(script(clear))
                .as("account cleanup retains the last setup's external allowance too")
                .contains("ip daddr 192.0.2.10 accept");
    }

    private TransportLifecycle lifecycle(final PoreloContext context) throws Exception {
        final Path adapters = Files.createDirectories(dir.resolve("adapters"));
        final Path adapter =
                Files.writeString(adapters.resolve(TransportDirectory.PREFIX + "room"), "");
        assertThat(adapter.toFile().setExecutable(true))
                .as("the fixture adapter is executable")
                .isTrue();
        return new TransportLifecycle(
                context,
                new TransportDirectory(List.of(adapters)),
                dir.resolve("host"),
                name -> Path.of("/usr/bin", name));
    }

    private void register(final PoreloContext context, final String securityClass)
            throws Exception {
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n  name: p\n  security_class: "
                                + securityClass
                                + "\nimage:\n  from: ubuntu:24.04\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("p", project);
    }

    @Test
    void transportCallersCannotKeepAnOrdinaryRunnerForNewVerbs() throws Exception {
        // Harm: adding a verb beside an existing call bypasses the shared executor.
        final Path sources =
                Path.of(
                        "..",
                        "app-messaging",
                        "src",
                        "main",
                        "java",
                        "org",
                        "fuin",
                        "porelo",
                        "app");
        for (final String caller :
                List.of(
                        "TransportDescription",
                        "TransportLifecycle",
                        "TransportSend",
                        "TransportPoll",
                        "TransportConversations",
                        "HelperAnswer")) {
            final String source = Files.readString(sources.resolve(caller + ".java"));
            assertThat(source)
                    .as("%s must execute through TransportVerbs", caller)
                    .contains("TransportVerbs")
                    .doesNotContain("private final CommandRunner runner")
                    .doesNotContainPattern(
                            "(?s)(?:context\\.runner\\(\\)|\\brunner)\\s*\\.run(?:OrFail)?\\(");
        }
    }

    private PoreloContext context(final FakeCommandRunner runner) {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static void remember(
            final PoreloContext context, final String project, final List<String> reaches)
            throws Exception {
        final Path directory =
                Files.createDirectories(context.paths().xdg().state().resolve("transport/room"));
        Files.writeString(
                directory.resolve(project + ".json"),
                Json.write(Map.of("conversation", "!room", "reaches", reaches)));
    }

    private static String script(final Command command) {
        return command.arguments().get(command.arguments().indexOf("-c") + 1);
    }
}
