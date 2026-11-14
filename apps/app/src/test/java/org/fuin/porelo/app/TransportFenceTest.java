package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** A transport's mistake must not inherit the account's network access. */
class TransportFenceTest {

    @TempDir Path dir;

    @Test
    void aSavedNameAllowsExactlyItsResolvedAddresses() throws IOException {
        // Harm: resolving a declared name opens destinations beyond its own answers.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final List<String> asked = new ArrayList<>();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org:443", "server.example.org:8448"),
                        false,
                        name -> {
                            asked.add(name);
                            return new InetAddress[] {
                                InetAddress.getByAddress(
                                        "ignored.example.org",
                                        InetAddress.ofLiteral("192.0.2.10").getAddress()),
                                InetAddress.ofLiteral("2001:db8::10"),
                                InetAddress.ofLiteral("192.0.2.10")
                            };
                        },
                        resolvConf("nameserver 192.0.2.53\n"));

        fenced.run(Command.of("/transport", "members"));

        assertThat(asked)
                .as("each distinct host is resolved once, without its port")
                .containsExactly("server.example.org");
        assertThat(accepts(runner.invocations().getFirst()))
                .as(
                        "only answer addresses, bounded discovery, loopback and the exact DNS"
                                + " endpoint pass")
                .containsExactly(
                        "oifname \"lo\" accept",
                        "ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip daddr 192.0.2.10 accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:10 accept",
                        "ip daddr 192.0.2.53 udp dport 53 accept",
                        "ip daddr 192.0.2.53 tcp dport 53 accept");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnresolvedNameRefusesTheVerbBeforeAnyProcessStarts(final boolean lookupFails)
            throws IOException {
        // Harm: an unavailable name silently falls back to an unfenced transport.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("192.0.2.10", "missing.example.org"),
                        false,
                        name -> {
                            if (lookupFails) throw new UnknownHostException(name);
                            return new InetAddress[0];
                        },
                        resolvConf("nameserver 192.0.2.53\n"));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("an empty answer and a failed lookup both refuse the launch")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("missing.example.org")
                .hasMessageContaining("no addresses");
        assertThat(runner.invocations()).as("no namespace or unfenced fallback starts").isEmpty();
    }

    @Test
    void eachVerbResolvesAgainWithoutKeepingThePreviousRunsAddresses() throws IOException {
        // Harm: a reused fence permanently retains a destination after its name moves away.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final List<String> answers = new ArrayList<>(List.of("192.0.2.10", "192.0.2.20"));
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral(answers.removeFirst())},
                        resolvConf("nameserver 127.0.0.53\n"));

        fenced.run(Command.of("/transport", "poll"));
        fenced.run(Command.of("/transport", "send"), line -> {});

        assertThat(scriptOf(runner.invocations().getFirst()))
                .as("the first run permits its own answer only")
                .contains("ip daddr 192.0.2.10 accept")
                .doesNotContain("192.0.2.20");
        assertThat(scriptOf(runner.invocations().getLast()))
                .as("the second run replaces the first run's allowance")
                .contains("ip daddr 192.0.2.20 accept")
                .doesNotContain("192.0.2.10");
        assertThat(answers).as("both launch paths resolve at their start").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.53", "::1", "127.0.0.53\nnameserver ::1"})
    void loopbackResolversMapOnlyDnsBesideTheSavedHomeserverPorts(final String resolvers)
            throws IOException {
        // Harm: DNS forwarding replaces the homeserver mapping or exposes unrelated host ports.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(
                        runner,
                        List.of("127.0.0.1:8008", "server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")},
                        resolvConf("# resolver fixture\n  nameserver " + resolvers + " # DNS\n"))
                .run(Command.of("/transport", "poll"));

        final Command command = runner.invocations().getFirst();
        assertThat(forwardedPorts(command))
                .as("DNS is added once and none is never mixed with a specific port")
                .containsExactly("8008", "53");
        assertThat(command.arguments())
                .as("only DNS gets a UDP loopback mapping")
                .containsSubsequence("-U", "53")
                .doesNotContainSequence("-U", "none");
        assertThat(accepts(command))
                .as("loopback DNS needs no external allowance")
                .containsExactly("oifname \"lo\" accept", "ip daddr 192.0.2.10 accept");
    }

    @Test
    void aSavedDnsPortIsMappedOnlyOnce() throws IOException {
        // Harm: duplicate or conflicting forwarding options prevent the fenced verb from starting.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(
                        runner,
                        List.of("127.0.0.1:53", "server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")},
                        resolvConf("nameserver 127.0.0.53\n"))
                .run(Command.of("/transport", "poll"));

        assertThat(forwardedPorts(runner.invocations().getFirst()))
                .as("the saved and DNS forwarding requirements share one TCP mapping")
                .containsExactly("53");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nameserver 127.0.0.53\n"})
    void externalResolversGetOnlyDnsPortsAndNoDnsLoopbackMapping(final String localResolver)
            throws IOException {
        // Harm: a DNS exception allows every destination or every port on the resolver.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(
                        runner,
                        List.of("127.0.0.1:8008", "server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")},
                        resolvConf(
                                localResolver
                                        + "nameserver 192.0.2.53\n"
                                        + "nameserver 2001:db8::53\n"
                                        + "nameserver 192.0.2.53\n"))
                .run(Command.of("/transport", "poll"));

        final Command command = runner.invocations().getFirst();
        assertThat(forwardedPorts(command))
                .as("external DNS adds no host port mapping, including in mixed configurations")
                .containsExactly("8008");
        assertThat(command.arguments())
                .as("UDP host forwarding remains disabled")
                .containsSubsequence("-U", "none");
        assertThat(accepts(command))
                .as("IPv6 DNS alone needs bounded discovery; each resolver has only UDP/TCP 53")
                .containsExactly(
                        "oifname \"lo\" accept",
                        "ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip daddr 192.0.2.10 accept",
                        "ip daddr 192.0.2.53 udp dport 53 accept",
                        "ip daddr 192.0.2.53 tcp dport 53 accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:53 udp dport 53 accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:53 tcp dport 53 accept");
    }

    @Test
    void anOfflineVerbNeitherResolvesNamesNorReadsOrMapsDns() {
        // Harm: a stale online reach or resolver setting opens an offline project's network.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("127.0.0.1:8008", "server.example.org"), true)
                .run(Command.of("/transport", "poll"));

        final Command command = runner.invocations().getFirst();
        assertThat(forwardedPorts(command))
                .as("only the saved homeserver is mapped")
                .containsExactly("8008");
        assertThat(command.arguments())
                .as("no UDP DNS forwarding")
                .containsSubsequence("-U", "none");
        assertThat(accepts(command))
                .as("only namespace loopback passes")
                .containsExactly("oifname \"lo\" accept");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "search example.org\n",
                "nameserver server.example.org\n",
                "nameserver 192.0.2.53; accept\n",
                "nameserver fe80::53%lo\n"
            })
    void missingOrInvalidNameserversCannotStartANameUsingVerb(final String configuration)
            throws IOException {
        // Harm: an unusable resolver file is mistaken for permission to open arbitrary DNS.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")},
                        resolvConf(configuration));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("DNS configuration must provide usable literal nameserver addresses")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("nameserver");
        assertThat(runner.invocations()).as("the verb never starts").isEmpty();
    }

    @Test
    void anUnreadableResolverFileRefusesANameUsingVerb() {
        // Harm: failure to inspect the DNS allowance is silently ignored on the launch path.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org"),
                        false,
                        name -> new InetAddress[] {InetAddress.ofLiteral("192.0.2.10")},
                        dir.resolve("absent-resolv.conf"));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("a resolver file read failure refuses before launching")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("resolver");
        assertThat(runner.invocations()).as("no fallback starts").isEmpty();
    }

    @Test
    void everyReachIsValidatedBeforeAnyNameIsResolved() throws IOException {
        // Harm: a malformed request can cause host-side DNS queries before being refused.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org", "192.0.2.10; accept"),
                        false,
                        name -> {
                            throw new AssertionError("Invalid reaches must be refused before DNS");
                        },
                        resolvConf("nameserver 192.0.2.53\n"));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("all reach syntax is checked before any lookup")
                .isInstanceOf(CommandException.class);
        assertThat(runner.invocations()).as("invalid reaches start nothing").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "[::]:443",
                "224.0.0.251",
                "[ff02::1]",
                "255.255.255.255",
                "169.254.169.254:80"
            })
    void aReachCannotNameAnUnspecifiedMulticastBroadcastOrLinkLocalAddress(final String reach) {
        // Harm: a reach opens a multicast group, every host's listener, or a link's metadata
        // service.
        final FakeCommandRunner runner = new FakeCommandRunner();

        assertThatThrownBy(
                        () ->
                                fence(runner, List.of(reach), false)
                                        .run(Command.of("/transport", "members")))
                .as("such a reach refuses before any process starts")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("cannot be reached");
        assertThat(runner.invocations()).as("nothing starts").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "0.0.0.0", "224.0.0.251", "169.254.169.254"})
    void aNamesAnswerCannotBeLoopbackOrAnAddressNoReachMayName(final String answer)
            throws IOException {
        // Harm: a name the transport controls resolves to loopback and maps a host port, or to an
        // address a literal reach is refused.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org:8008"),
                        false,
                        name ->
                                new InetAddress[] {
                                    InetAddress.ofLiteral("192.0.2.10"),
                                    InetAddress.ofLiteral(answer)
                                },
                        resolvConf("nameserver 192.0.2.53\n"));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("the answer is refused before a port is mapped or a rule rendered")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("server.example.org")
                .hasMessageContaining("cannot be reached");
        assertThat(runner.invocations()).as("nothing starts").isEmpty();
    }

    @Test
    void aScopedResolverAnswerCannotReachTheRuleset() throws IOException {
        // Harm: a scoped address is interpreted as nft syntax or unexpectedly reaches a host
        // interface.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                fence(
                        runner,
                        List.of("server.example.org"),
                        false,
                        name ->
                                new InetAddress[] {
                                    Inet6Address.getByAddress(
                                            null, InetAddress.ofLiteral("fe80::10").getAddress(), 7)
                                },
                        resolvConf("nameserver 192.0.2.53\n"));

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("scoped answers are refused before being rendered")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("scoped");
        assertThat(runner.invocations()).as("no launch follows an unusable answer").isEmpty();
    }

    @Test
    void aVerbCannotStartBeforeItsNamespaceRulesAreLoaded() {
        // Harm: a transport reaches another host before its fence exists.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final Command verb = Command.of("/transport", "members", "--project", "p");

        fence(runner, List.of("192.0.2.10:443", "[2001:db8::10]:8448"), false).run(verb);

        final Command started = runner.invocations().getFirst();
        assertThat(started.arguments())
                .as("pasta creates the namespace and configures its network before the wrapper")
                .startsWith(
                        "/usr/bin/pasta",
                        "--config-net",
                        "-t",
                        "none",
                        "-u",
                        "none",
                        "-T",
                        "none",
                        "-U",
                        "none",
                        "--map-host-loopback",
                        "none",
                        "--map-guest-addr",
                        "none",
                        "--",
                        "/usr/bin/sh",
                        "-c")
                .endsWith(
                        "porelo-transport-fence",
                        "/usr/bin/nft",
                        "/usr/bin/setpriv",
                        "--bounding-set=-all",
                        "--inh-caps=-all",
                        "--ambient-caps=-all",
                        "--no-new-privs",
                        "--",
                        "/transport",
                        "members",
                        "--project",
                        "p");
        assertThat(scriptOf(started))
                .as("nft must succeed before exec, and consumes its own input")
                .contains("\"$1\" -f /dev/stdin <<'PORELO_TRANSPORT_RULES'", "&&", "exec \"$@\"")
                .contains(
                        "policy drop;",
                        "oifname \"lo\" accept",
                        "ip daddr 192.0.2.10 accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:10 accept")
                .doesNotContain("ct state", "dport 53", "policy accept");
    }

    @Test
    void onlyLoopbackPortsReportedBySetupReachTheHostsListeners() {
        // Harm: automatic forwarding exposes unrelated services of the account.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(
                        runner,
                        List.of(
                                "127.0.0.1:8008",
                                "[::1]:8009",
                                "localhost:8008",
                                "192.0.2.10:8448"),
                        false)
                .run(Command.of("/transport", "send"));

        assertThat(forwardedPorts(runner.invocations().getFirst()))
                .as("each saved loopback port is mapped once, with no automatic forwarding")
                .containsExactly("8008", "8009");
    }

    @Test
    void anOfflineConversationCanReachItsOwnHomeserverButNoExternalAddress() {
        // Harm: denying host loopback breaks offline messaging; allowing external reaches leaks it.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("127.0.0.1:8008", "192.0.2.10:443", "server.example.org"), true)
                .run(Command.of("/transport", "poll"));

        final Command command = runner.invocations().getFirst();
        assertThat(forwardedPorts(command))
                .as("offline still needs the host's one homeserver listener")
                .containsExactly("8008");
        assertThat(
                        scriptOf(command)
                                .lines()
                                .map(String::strip)
                                .filter(line -> line.endsWith(" accept")))
                .as("only loopback passes, even with stale external reaches")
                .containsExactly("oifname \"lo\" accept");
    }

    @Test
    void anOfflineConversationWithoutALoopbackPortMapsNothing() {
        // Harm: an unspecified port is mistaken for permission to forward every host listener.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("127.0.0.1", "[::1]", "localhost", "192.0.2.10:8008"), true)
                .run(Command.of("/transport", "receipt"));

        assertThat(forwardedPorts(runner.invocations().getFirst()))
                .as("no explicit loopback port means no host mapping")
                .containsExactly("none");
    }

    private static List<String> forwardedPorts(final Command command) {
        final List<String> ports = new ArrayList<>();
        final List<String> arguments = command.arguments();
        for (int index = 0; index < arguments.indexOf("--"); index++) {
            if ("-T".equals(arguments.get(index))) ports.add(arguments.get(index + 1));
        }
        return ports;
    }

    @Test
    void aDeclaredGatewayCannotBeTranslatedIntoTheHostsLoopback() {
        // Harm: allowing a gateway literal accidentally grants the account's local services.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("192.0.2.1"), false).run(Command.of("/transport", "members"));

        assertThat(runner.invocations().getFirst().arguments())
                .as("a declared address must keep its destination on the host side of pasta")
                .containsSubsequence("--map-host-loopback", "none", "--map-guest-addr", "none");
    }

    @Test
    void anOfflineProjectCannotOpenAnExternalReachEvenWhenTheTransportNamesIt() {
        // Harm: a stale or mistaken reach opens an offline project's network.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("192.0.2.10", "2001:db8::10", "server.example.org"), true)
                .run(Command.of("/transport", "members"));

        assertThat(scriptOf(runner.invocations().getFirst()))
                .as("offline rules have one accept, for namespace loopback alone")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain(
                        "ip daddr",
                        "ip6 daddr",
                        "icmpv6",
                        "192.0.2.10",
                        "2001:db8",
                        "server.example.org");
    }

    @Test
    void neighborDiscoveryCannotOpenOtherIcmpv6TypesOrGlobalTraffic() {
        // Harm: an IPv6 discovery exception opens arbitrary control traffic beyond declared
        // reaches.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("2001:db8::10"), false).run(Command.of("/transport", "members"));

        final String script = scriptOf(runner.invocations().getFirst());
        assertThat(script.lines().map(String::strip).filter(line -> line.endsWith(" accept")))
                .as("only loopback, the declared reach and bounded discovery traffic pass")
                .containsExactly(
                        "oifname \"lo\" accept",
                        "ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134, 135, 136 }"
                                + " accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:10 accept");
        assertThat(script)
                .as("other IPv6 traffic beyond the declared reach falls through to the drop policy")
                .contains("type filter hook output priority filter; policy drop;")
                .doesNotContain("policy accept", "ct state");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[server.example.org]:443",
                "192.0.2.999",
                "",
                "192.0.2.0/24",
                "192.0.2.1; accept",
                "192.0.2.1\naccept",
                "fe80::1%lo",
                "[::1].example.org",
                "192.0.2.10:0",
                "192.0.2.10:65536"
            })
    void anUnsupportedReachCannotInjectRulesOrSilentlyRunUnfenced(final String reach) {
        // Harm: interpreting an invalid reach as nft syntax widens the allow-list.
        final FakeCommandRunner runner = new FakeCommandRunner();

        assertThatThrownBy(
                        () ->
                                fence(runner, List.of(reach), false)
                                        .run(Command.of("/transport", "members")))
                .as("unsupported reaches refuse before any process starts")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("literal");
        assertThat(runner.invocations()).as("no fallback starts the verb").isEmpty();
    }

    @Test
    void missingPastaRefusesWithoutStartingTheVerb() {
        // Harm: a machine lacking the namespace helper runs a transport on the host network.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                new TransportFence(
                        runner,
                        List.of(),
                        false,
                        name -> {
                            if ("pasta".equals(name))
                                throw new UncheckedIOException(new IOException("pasta is missing"));
                            return Path.of("/usr/bin", name);
                        });

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("the refusal names the missing dependency")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("pasta");
        assertThat(runner.invocations()).as("no unfenced fallback").isEmpty();
    }

    @Test
    void missingSetprivRefusesRatherThanRunningTheVerbWithTheNamespacesCapabilities() {
        // Harm: a verb keeps CAP_NET_ADMIN in pasta's namespace and flushes its own fence.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                new TransportFence(
                        runner,
                        List.of(),
                        false,
                        name -> {
                            if ("setpriv".equals(name))
                                throw new UncheckedIOException(
                                        new IOException("setpriv is missing"));
                            return Path.of("/usr/bin", name);
                        });

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("the refusal names the missing dependency")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("setpriv");
        assertThat(runner.invocations()).as("no verb runs with its capabilities").isEmpty();
    }

    @Test
    void failingPastaCannotFallBackToTheHostNetwork() {
        // Harm: a namespace creation failure is mistaken for permission to run without one.
        final FakeCommandRunner runner =
                new FakeCommandRunner().failing("/usr/bin/pasta", 1, "namespace refused");

        final CommandResult result =
                fence(runner, List.of(), false).run(Command.of("/transport", "members"));

        assertThat(result.exitCode()).as("the namespace failure is returned").isEqualTo(1);
        assertThat(runner.invocations()).as("only the fenced launch was attempted").hasSize(1);
    }

    @Test
    void wrappingCannotLoseCredentialsInputLimitsOrLiveDiagnostics() {
        // Harm: the wrapper leaks credentials into arguments or drops a timeout or stderr stream.
        final List<String> lines = new ArrayList<>();
        final List<Command> seen = new ArrayList<>();
        final CommandRunner runner =
                new CommandRunner() {
                    @Override
                    public CommandResult run(final Command command) {
                        throw new AssertionError("the streaming overload must be retained");
                    }

                    @Override
                    public CommandResult run(
                            final Command command,
                            final java.util.function.Consumer<String> output) {
                        seen.add(command);
                        output.accept("arriving now");
                        return new CommandResult(command, 75, "answer", "arriving now");
                    }
                };
        final byte[] input = "fixture input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Command verb =
                Command.of("/transport", "members", "a 'quoted' $(argument)")
                        .withChosenEnvironment(Map.of("LANG", "C.UTF-8"))
                        .withEnvironment(Map.of("TRANSPORT_CREDENTIAL", "fixture value"))
                        .withBinaryInput(input)
                        .in(dir)
                        .withTimeout(Duration.ofSeconds(7));

        final CommandResult result = fence(runner, List.of(), false).run(verb, lines::add);

        final Command wrapped = seen.getFirst();
        assertThat(wrapped.binaryInput()).as("stdin remains the verb's, not nft's").isSameAs(input);
        assertThat(wrapped.environment())
                .as("the chosen environment is retained")
                .isEqualTo(verb.environment());
        assertThat(wrapped.inheritsEnvironment()).as("caller environment stays excluded").isFalse();
        assertThat(wrapped.workingDirectory())
                .as("relative paths retain their meaning")
                .isEqualTo(dir);
        assertThat(wrapped.timeout())
                .as("the verb cannot outlive its limit")
                .isEqualTo(verb.timeout());
        assertThat(wrapped.arguments())
                .as("arguments remain data")
                .endsWith("/transport", "members", "a 'quoted' $(argument)");
        assertThat(wrapped.describe())
                .as("credentials never become arguments")
                .doesNotContain("fixture value", "fixture input");
        assertThat(lines)
                .as("diagnostics arrive through the streaming overload")
                .containsExactly("arriving now");
        assertThat(result.exitCode()).as("temporary failures retain their meaning").isEqualTo(75);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "fixture text")
    void absentBinaryInputCannotReplaceTextOrBecomeAnEmptyByteInput(final @Nullable String input) {
        // Harm: wrapping loses stdin credentials or turns absent input into a different input mode.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final Command verb = Command.of("/transport", "members");
        final Command given = input == null ? verb : verb.withInput(input);

        fence(runner, List.of(), false).run(given);

        final Command wrapped = runner.invocations().getFirst();
        assertThat(wrapped.input())
                .as("text input, including empty or absent text, is preserved")
                .isEqualTo(input);
        assertThat(wrapped.binaryInput()).as("null remains no binary input").isNull();
        assertThat(wrapped.timeout()).as("an absent per-command limit stays absent").isNull();
    }

    @Test
    void aFailedRulesetLoadCannotExecuteTheTransport() throws IOException {
        // Harm: a missing or broken nft leaves the namespace unrestricted when the verb starts.
        final Path nft = script("nft", "exit 42\n");
        final Path ran = dir.resolve("verb-ran");
        final Command wrapped =
                new TransportFence(
                                new FakeCommandRunner(),
                                List.of(),
                                false,
                                name ->
                                        "nft".equals(name)
                                                ? nft
                                                : "setpriv".equals(name)
                                                        ? setpriv()
                                                        : Path.of("/bin/sh"))
                        .command(
                                Command.of(
                                        "/bin/sh", "-c", "touch \"$1\"", "verb", ran.toString()));

        final CommandResult result = new ProcessCommandRunner().run(withoutPasta(wrapped));

        assertThat(result.exitCode()).as("nft's failure stops the wrapper").isEqualTo(42);
        assertThat(ran).as("the verb never ran").doesNotExist();
    }

    @Test
    void theRulesetCannotConsumeTheVerbsInputOrInterpretItsArguments() throws IOException {
        // Harm: stdin credentials are lost or an argument is executed by the shell wrapper.
        final Path rules = dir.resolve("loaded-rules");
        final Path nft = script("nft", "cat > '" + rules + "'\n");
        final String argument = "$(touch " + dir.resolve("injected") + ") ' ; false";
        final Command wrapped =
                new TransportFence(
                                new FakeCommandRunner(),
                                List.of("192.0.2.10"),
                                false,
                                name ->
                                        "nft".equals(name)
                                                ? nft
                                                : "setpriv".equals(name)
                                                        ? setpriv()
                                                        : Path.of("/bin/sh"))
                        .command(
                                Command.of(
                                                "/bin/sh",
                                                "-c",
                                                "cat; printf '%s' \"$1\"; exit 75",
                                                "verb",
                                                argument)
                                        .withInput("fixture stdin\n"));

        final CommandResult result = new ProcessCommandRunner().run(withoutPasta(wrapped));

        assertThat(result.exitCode()).as("the verb's status survives exec").isEqualTo(75);
        assertThat(result.standardOutput())
                .as("nft consumes only its rules, not the verb's stdin")
                .isEqualTo("fixture stdin\n" + argument);
        assertThat(Files.readString(rules))
                .as("nft received the actual ruleset")
                .contains("ip daddr 192.0.2.10 accept")
                .doesNotContain("fixture stdin");
        assertThat(dir.resolve("injected")).as("arguments cannot become shell code").doesNotExist();
    }

    private TransportFence fence(
            final CommandRunner runner, final List<String> reaches, final boolean offline) {
        return fence(
                runner,
                reaches,
                offline,
                name -> {
                    throw new AssertionError("Unexpected DNS lookup: " + name);
                },
                dir.resolve("absent-resolv.conf"));
    }

    private static TransportFence fence(
            final CommandRunner runner,
            final List<String> reaches,
            final boolean offline,
            final TransportFence.Resolver resolver,
            final Path resolvConf) {
        return new TransportFence(
                runner, reaches, offline, name -> Path.of("/usr/bin", name), resolver, resolvConf);
    }

    private Path resolvConf(final String configuration) throws IOException {
        return Files.writeString(dir.resolve("resolv.conf"), configuration);
    }

    private static List<String> accepts(final Command command) {
        return scriptOf(command)
                .lines()
                .map(String::strip)
                .filter(line -> line.endsWith(" accept"))
                .toList();
    }

    private static String scriptOf(final Command command) {
        return command.arguments().get(command.arguments().indexOf("-c") + 1);
    }

    private static Command withoutPasta(final Command command) {
        final Command unwrapped =
                new Command(
                        command.arguments()
                                .subList(
                                        command.arguments().indexOf("--") + 1,
                                        command.arguments().size()),
                        command.workingDirectory(),
                        command.environment(),
                        command.input(),
                        command.inheritsEnvironment());
        final byte @Nullable [] binaryInput = command.binaryInput();
        final Command withInput =
                binaryInput == null ? unwrapped : unwrapped.withBinaryInput(binaryInput);
        final @Nullable Duration timeout = command.timeout();
        return timeout == null ? withInput : withInput.withTimeout(timeout);
    }

    /** Stands in for setpriv: drops its own options up to {@code --} and runs the rest. */
    private Path setpriv() {
        try {
            return script(
                    "setpriv", "while [ \"$1\" != -- ]; do shift; done\nshift\nexec \"$@\"\n");
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path script(final String name, final String body) throws IOException {
        final Path file = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + body);
        assertThat(file.toFile().setExecutable(true))
                .as("the fixture executable is usable")
                .isTrue();
        return file;
    }
}
